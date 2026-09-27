package com.chris64233.cc.schemaregistry.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.cc.schemaregistry.registry.VersionAuditEntity.Event;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.ConsumerUpdateResult;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.DeleteResult;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.DeletionEligibility;

@SpringBootTest
class VersionLifecycleServiceTest {

    @Autowired
    private SchemaRegistryService registry;

    @Autowired
    private VersionLifecycleService lifecycle;

    private static final String CONTRACT_V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String CONTRACT_V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\"}}}";

    private void subjectWithTwoVersions(String name) {
        registry.createSubject(name, CompatibilityMode.BACKWARD);
        registry.publish(name, CONTRACT_V1, null);
        registry.publish(name, CONTRACT_V2, null);
    }

    private static Instant inOneHour() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    private static Instant oneHourAgo() {
        return Instant.now().minus(1, ChronoUnit.HOURS);
    }

    // ---------- 消费者依赖登记 ----------

    @Test
    void staleUpdateDoesNotOverwriteNewerDependency() {
        registry.createSubject("lc-stale", CompatibilityMode.BACKWARD);
        registry.publish("lc-stale", CONTRACT_V1, null);
        registry.publish("lc-stale", CONTRACT_V2, null);

        ConsumerUpdateResult first = lifecycle.upsertConsumerDependency("lc-stale", "consumer-a", 2, 10,
                inOneHour(), null);
        assertThat(first.applied()).isTrue();
        assertThat(first.version()).isEqualTo(2);

        // 迟到的心跳：更小的序号、指向旧版本，不得覆盖较新的依赖
        ConsumerUpdateResult late = lifecycle.upsertConsumerDependency("lc-stale", "consumer-a", 1, 9,
                inOneHour(), null);
        assertThat(late.applied()).isFalse();
        assertThat(late.stale()).isTrue();
        assertThat(late.version()).isEqualTo(2);
        assertThat(late.updateSeq()).isEqualTo(10);

        // 相同序号但没有幂等键：同样视为迟到，不生效
        ConsumerUpdateResult sameSeq = lifecycle.upsertConsumerDependency("lc-stale", "consumer-a", 1, 10,
                inOneHour(), null);
        assertThat(sameSeq.applied()).isFalse();
        assertThat(sameSeq.stale()).isTrue();

        // 更大序号才生效
        ConsumerUpdateResult newer = lifecycle.upsertConsumerDependency("lc-stale", "consumer-a", 1, 11,
                inOneHour(), null);
        assertThat(newer.applied()).isTrue();
        assertThat(newer.version()).isEqualTo(1);
    }

    @Test
    void consumerUpdateIdempotencyKeyReplaysAndConflicts() {
        registry.createSubject("lc-cidem", CompatibilityMode.BACKWARD);
        registry.publish("lc-cidem", CONTRACT_V1, null);

        Instant lease = inOneHour();
        ConsumerUpdateResult first = lifecycle.upsertConsumerDependency("lc-cidem", "consumer-a", 1, 1,
                lease, "cu-1");
        assertThat(first.applied()).isTrue();

        ConsumerUpdateResult replay = lifecycle.upsertConsumerDependency("lc-cidem", "consumer-a", 1, 1,
                lease, "cu-1");
        assertThat(replay.applied()).isFalse();
        assertThat(replay.stale()).isFalse();
        assertThat(replay.updateSeq()).isEqualTo(1);

        assertThatThrownBy(() -> lifecycle.upsertConsumerDependency("lc-cidem", "consumer-a", 1, 2,
                lease, "cu-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void consumerUpdateOnUnknownVersionFails() {
        registry.createSubject("lc-nover", CompatibilityMode.BACKWARD);
        registry.publish("lc-nover", CONTRACT_V1, null);

        assertThatThrownBy(() -> lifecycle.upsertConsumerDependency("lc-nover", "c", 9, 1, inOneHour(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.VERSION_NOT_FOUND);
    }

    // ---------- 废弃 ----------

    @Test
    void deprecationWaitsForActiveConsumersAndCompletesAfterLeaseExpiry() {
        registry.createSubject("lc-dep", CompatibilityMode.BACKWARD);
        registry.publish("lc-dep", CONTRACT_V1, null);
        lifecycle.upsertConsumerDependency("lc-dep", "consumer-a", 1, 1, inOneHour(), null);

        lifecycle.requestDeprecation("lc-dep", 1, Instant.now(), 0, null);
        assertThat(lifecycle.getLifecycle("lc-dep", 1).getLifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATING);

        // 生效时间已到，但存在有效租约：不得进入已废弃状态
        assertThat(lifecycle.applyDueDeprecations("lc-dep")).isEmpty();
        assertThat(lifecycle.getLifecycle("lc-dep", 1).getLifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATING);
        assertThat(lifecycle.deprecationBlockers("lc-dep", 1))
                .extracting(ConsumerDependencyEntity::getConsumerId)
                .containsExactly("consumer-a");

        // 租约过期后扫描完成废弃
        lifecycle.upsertConsumerDependency("lc-dep", "consumer-a", 1, 2, oneHourAgo(), null);
        assertThat(lifecycle.applyDueDeprecations("lc-dep")).containsExactly(1);
        SchemaVersionEntity entity = lifecycle.getLifecycle("lc-dep", 1);
        assertThat(entity.getLifecycle()).isEqualTo(VersionLifecycle.DEPRECATED);
        assertThat(entity.getDeprecatedAt()).isNotNull();
        assertThat(lifecycle.deprecationBlockers("lc-dep", 1)).isEmpty();
    }

    @Test
    void deprecationCompletesAfterConsumerMigrates() {
        subjectWithTwoVersions("lc-migrate");
        lifecycle.upsertConsumerDependency("lc-migrate", "consumer-a", 1, 1, inOneHour(), null);
        lifecycle.requestDeprecation("lc-migrate", 1, Instant.now(), 0, null);
        assertThat(lifecycle.applyDueDeprecations("lc-migrate")).isEmpty();

        // 消费者迁移到版本 2 后，版本 1 不再被阻断
        lifecycle.upsertConsumerDependency("lc-migrate", "consumer-a", 2, 2, inOneHour(), null);
        assertThat(lifecycle.applyDueDeprecations("lc-migrate")).containsExactly(1);
    }

    @Test
    void deprecationScanBeforeEffectiveTimeDoesNothing() {
        registry.createSubject("lc-future", CompatibilityMode.BACKWARD);
        registry.publish("lc-future", CONTRACT_V1, null);
        lifecycle.requestDeprecation("lc-future", 1, inOneHour(), 0, null);

        assertThat(lifecycle.applyDueDeprecations("lc-future")).isEmpty();
        assertThat(lifecycle.getLifecycle("lc-future", 1).getLifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATING);
    }

    @Test
    void deprecationRequestKeyIsIdempotent() {
        registry.createSubject("lc-didem", CompatibilityMode.BACKWARD);
        registry.publish("lc-didem", CONTRACT_V1, null);

        Instant effectiveAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        lifecycle.requestDeprecation("lc-didem", 1, effectiveAt, 60, "dep-1");
        SchemaVersionEntity replay = lifecycle.requestDeprecation("lc-didem", 1, effectiveAt, 60, "dep-1");
        assertThat(replay.getLifecycle()).isEqualTo(VersionLifecycle.DEPRECATING);
        assertThat(lifecycle.auditTrail("lc-didem", 1))
                .filteredOn(a -> a.getEvent() == Event.DEPRECATION_REQUESTED)
                .hasSize(1);

        assertThatThrownBy(() -> lifecycle.requestDeprecation("lc-didem", 1, effectiveAt, 120, "dep-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void deprecatedVersionRejectsNewConsumerDependency() {
        registry.createSubject("lc-depreg", CompatibilityMode.BACKWARD);
        registry.publish("lc-depreg", CONTRACT_V1, null);
        lifecycle.requestDeprecation("lc-depreg", 1, Instant.now(), 0, null);
        assertThat(lifecycle.applyDueDeprecations("lc-depreg")).containsExactly(1);

        assertThatThrownBy(() -> lifecycle.upsertConsumerDependency("lc-depreg", "late-consumer", 1, 1,
                inOneHour(), null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.VERSION_DEPRECATED);
    }

    @Test
    void deprecationScanAndConsumerRenewalAreConsistent() throws Exception {
        registry.createSubject("lc-race", CompatibilityMode.BACKWARD);
        registry.publish("lc-race", CONTRACT_V1, null);
        // 租约已过期：扫描与续租竞争，谁先拿到主题锁谁赢
        lifecycle.upsertConsumerDependency("lc-race", "consumer-a", 1, 1, oneHourAgo(), null);
        lifecycle.requestDeprecation("lc-race", 1, Instant.now(), 0, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object> renewal = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                return lifecycle.upsertConsumerDependency("lc-race", "consumer-a", 1, 2, inOneHour(), null);
            } catch (ApiException e) {
                return e;
            }
        });
        Future<List<Integer>> scan = pool.submit(() -> {
            ready.countDown();
            start.await();
            return lifecycle.applyDueDeprecations("lc-race");
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        Object renewalResult = renewal.get(30, TimeUnit.SECONDS);
        List<Integer> deprecated = scan.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        VersionLifecycle state = lifecycle.getLifecycle("lc-race", 1).getLifecycle();
        if (renewalResult instanceof ConsumerUpdateResult renewalApplied) {
            // 续租先提交：扫描必须看到有效租约并保持 DEPRECATING
            assertThat(renewalApplied.applied()).isTrue();
            assertThat(deprecated).isEmpty();
            assertThat(state).isEqualTo(VersionLifecycle.DEPRECATING);
            assertThat(lifecycle.deprecationBlockers("lc-race", 1)).hasSize(1);
        } else {
            // 扫描先提交：版本已废弃，续租被拒绝
            assertThat(((ApiException) renewalResult).code()).isEqualTo(ErrorCodes.VERSION_DEPRECATED);
            assertThat(deprecated).containsExactly(1);
            assertThat(state).isEqualTo(VersionLifecycle.DEPRECATED);
        }
    }

    // ---------- 删除 ----------

    @Test
    void deletionEligibilityExplainsEachBlocker() {
        subjectWithTwoVersions("lc-elig");
        lifecycle.upsertConsumerDependency("lc-elig", "consumer-a", 2, 1, inOneHour(), null);

        DeletionEligibility active = lifecycle.deletionEligibility("lc-elig", 2);
        assertThat(active.eligible()).isFalse();
        assertThat(active.reasons()).containsExactlyInAnyOrder(
                "VERSION_NOT_DEPRECATED", "ACTIVE_CONSUMERS_PRESENT");

        lifecycle.requestDeprecation("lc-elig", 2, Instant.now(), 3600, null);
        assertThat(lifecycle.applyDueDeprecations("lc-elig")).isEmpty();

        DeletionEligibility blocked = lifecycle.deletionEligibility("lc-elig", 2);
        assertThat(blocked.eligible()).isFalse();
        assertThat(blocked.reasons()).containsExactlyInAnyOrder(
                "VERSION_NOT_DEPRECATED", "ACTIVE_CONSUMERS_PRESENT");

        lifecycle.upsertConsumerDependency("lc-elig", "consumer-a", 2, 2, oneHourAgo(), null);
        assertThat(lifecycle.applyDueDeprecations("lc-elig")).containsExactly(2);

        DeletionEligibility retained = lifecycle.deletionEligibility("lc-elig", 2);
        assertThat(retained.eligible()).isFalse();
        assertThat(retained.reasons()).containsExactly("RETENTION_PERIOD_NOT_ELAPSED");
    }

    @Test
    void deletionRequiresNoCompatibilityDependents() {
        subjectWithTwoVersions("lc-depver");
        lifecycle.requestDeprecation("lc-depver", 1, Instant.now(), 0, null);
        assertThat(lifecycle.applyDueDeprecations("lc-depver")).containsExactly(1);

        // 版本 2 的兼容性检查依赖版本 1 的契约：版本 1 不可删除
        DeletionEligibility v1 = lifecycle.deletionEligibility("lc-depver", 1);
        assertThat(v1.eligible()).isFalse();
        assertThat(v1.reasons()).containsExactly("COMPATIBILITY_DEPENDENTS_PRESENT");
        assertThatThrownBy(() -> lifecycle.deleteVersion("lc-depver", 1, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.DELETION_NOT_ELIGIBLE);

        // 先废弃并删除版本 2 后，版本 1 才具备删除资格
        lifecycle.requestDeprecation("lc-depver", 2, Instant.now(), 0, null);
        assertThat(lifecycle.applyDueDeprecations("lc-depver")).containsExactly(2);
        assertThat(lifecycle.deleteVersion("lc-depver", 2, null).deleted()).isTrue();
        assertThat(lifecycle.deletionEligibility("lc-depver", 1).eligible()).isTrue();
    }

    @Test
    void deletionRemovesOnlyPayloadAndKeepsHashVersionAndAudit() {
        registry.createSubject("lc-del", CompatibilityMode.BACKWARD);
        registry.publish("lc-del", CONTRACT_V1, null);
        String hash = registry.getVersion("lc-del", 1).getContentHash();

        lifecycle.requestDeprecation("lc-del", 1, Instant.now(), 0, null);
        lifecycle.applyDueDeprecations("lc-del");
        DeleteResult result = lifecycle.deleteVersion("lc-del", 1, "del-1");
        assertThat(result.deleted()).isTrue();

        SchemaVersionEntity entity = registry.getVersion("lc-del", 1);
        assertThat(entity.getContent()).isNull();
        assertThat(entity.getContentHash()).isEqualTo(hash);
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getDeletedAt()).isNotNull();

        assertThat(lifecycle.auditTrail("lc-del", 1))
                .extracting(VersionAuditEntity::getEvent)
                .containsExactly(Event.PUBLISHED, Event.DEPRECATION_REQUESTED, Event.DEPRECATED,
                        Event.DELETED);
    }

    @Test
    void deletionRequestKeyIsIdempotent() {
        registry.createSubject("lc-delidem", CompatibilityMode.BACKWARD);
        registry.publish("lc-delidem", CONTRACT_V1, null);
        lifecycle.requestDeprecation("lc-delidem", 1, Instant.now(), 0, null);
        lifecycle.applyDueDeprecations("lc-delidem");

        DeleteResult first = lifecycle.deleteVersion("lc-delidem", 1, "del-1");
        DeleteResult replay = lifecycle.deleteVersion("lc-delidem", 1, "del-1");
        assertThat(first.deleted()).isTrue();
        assertThat(replay.deleted()).isFalse();
        assertThat(replay.deletedAt()).isEqualTo(first.deletedAt());
        assertThat(lifecycle.auditTrail("lc-delidem", 1))
                .filteredOn(a -> a.getEvent() == Event.DELETED)
                .hasSize(1);

        assertThatThrownBy(() -> lifecycle.deleteVersion("lc-delidem", 1, "del-2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.VERSION_DELETED);
    }

    @Test
    void deletedVersionIsExcludedFromCompatibilityHistory() {
        subjectWithTwoVersions("lc-hist");
        lifecycle.requestDeprecation("lc-hist", 2, Instant.now(), 0, null);
        lifecycle.applyDueDeprecations("lc-hist");
        lifecycle.deleteVersion("lc-hist", 2, null);

        // 版本 2 的载荷已删除，不再参与兼容性检查；发布等价于版本 2 的契约仍被
        // 内容哈希去重拦截，但与已删版本语义冲突的契约按剩余历史校验
        assertThat(registry.listVersions("lc-hist")).hasSize(2);
        var diffs = registry.checkCompatibility("lc-hist",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                        + " \"name\": {\"type\": \"string\"}, \"extra\": {\"type\": \"boolean\"}}}");
        assertThat(diffs).isEmpty();
    }
}
