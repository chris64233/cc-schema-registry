package com.chris64233.cc.schemaregistry.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationBatchView;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationEvent;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerRegistration;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerView;

@SpringBootTest
class MigrationBatchServiceTest {

    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    private static final String CONTRACT_V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String CONTRACT_V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"required\": true, \"default\": \"\"}}}";
    private static final String CONTRACT_V3 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"required\": true, \"default\": \"\"},"
                    + " \"nickname\": {\"type\": \"string\"}}}";

    @Autowired
    private MigrationBatchService migrationService;

    @Autowired
    private SchemaRegistryService service;

    @Autowired
    private TimeProvider timeProvider;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MutableClock clock;

    @AfterEach
    void resetClock() {
        timeProvider.setClock(Clock.systemUTC());
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("migration_confirmations", "migration_batch_members",
                "migration_batches", "compat_reference_edges", "version_audit_events",
                "operation_requests", "consumer_dependencies", "idempotency_records",
                "schema_versions", "subjects")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock = new MutableClock(T0);
        timeProvider.setClock(clock);
    }

    private void setupSubjectWithVersions(String subject) {
        service.createSubject(subject, CompatibilityMode.BACKWARD);
        service.publish(subject, CONTRACT_V1, null);
        service.publish(subject, CONTRACT_V2, null);
        service.publish(subject, CONTRACT_V3, null);
    }

    private void register(String subject, String consumer, int version, long seq,
            Integer expectedVersion, long leaseSeconds) {
        service.registerConsumer(subject, new ConsumerRegistration(consumer, version,
                clock.plusSeconds(leaseSeconds), seq, expectedVersion, null));
    }

    private MigrationEvent event(String eventId, Integer targetVersion, long seq, long leaseSeconds) {
        return new MigrationEvent(eventId, targetVersion, clock.plusSeconds(leaseSeconds), seq);
    }

    // ------------------------------------------------------------------
    // 创建批次：冻结与兼容性
    // ------------------------------------------------------------------

    @Test
    void creatingBatchFreezesActiveConsumersOfSourceVersion() {
        setupSubjectWithVersions("mb-freeze");
        register("mb-freeze", "svc-a", 1, 1, null, 100);
        register("mb-freeze", "svc-b", 1, 1, null, 200);
        // 使用 v2 的消费者不在源版本 v1 的冻结集合内
        register("mb-freeze", "svc-other", 2, 1, null, 100);
        // 租约已过期的 v1 消费者不冻结
        register("mb-freeze", "svc-dead", 1, 1, null, 10);
        clock.advanceSeconds(50);

        MigrationBatchView batch = migrationService.createBatch("mb-freeze", 1, 2);

        assertThat(batch.status()).isEqualTo(MigrationBatchStatus.OPEN);
        assertThat(batch.sourceVersion()).isEqualTo(1);
        assertThat(batch.targetVersion()).isEqualTo(2);
        assertThat(batch.pendingConsumers()).extracting(MigrationBatchService.MigrationMemberView::consumer)
                .containsExactly("svc-a", "svc-b");
    }

    @Test
    void incompatibleTargetReportsConsumerSpecificDiffsAndCreatesNothing() {
        setupSubjectWithVersions("mb-incompat");
        register("mb-incompat", "svc-a", 2, 1, null, 100);

        // v2 -> v1：目标 v1 删除了 v2 的 required 属性 name，BACKWARD 不兼容。
        assertThatThrownBy(() -> migrationService.createBatch("mb-incompat", 2, 1))
                .isInstanceOf(MigrationIncompatibleException.class)
                .satisfies(ex -> {
                    MigrationIncompatibleException mie = (MigrationIncompatibleException) ex;
                    assertThat(mie.consumers()).containsExactly("svc-a");
                    assertThat(mie.diffs()).isNotEmpty();
                    assertThat(mie.diffs().get(0).property()).isEqualTo("name");
                });

        // 不允许产生部分可执行的批次
        assertThat(migrationService.listBatches("mb-incompat")).isEmpty();
    }

    @Test
    void cannotCreateBatchWhenTargetVersionIsNotUsable() {
        setupSubjectWithVersions("mb-target-dep");
        // 没有 v2 的活跃消费者，v2 可立即废弃
        service.requestDeprecation("mb-target-dep", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();

        register("mb-target-dep", "svc-a", 1, 1, null, 100);
        assertThatThrownBy(() -> migrationService.createBatch("mb-target-dep", 1, 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);
    }

    @Test
    void cannotCreateSecondOpenBatchForSameSource() {
        setupSubjectWithVersions("mb-dup");
        register("mb-dup", "svc-a", 1, 1, null, 100);
        migrationService.createBatch("mb-dup", 1, 2);

        assertThatThrownBy(() -> migrationService.createBatch("mb-dup", 1, 3))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_ALREADY_OPEN);
    }

    @Test
    void creatingBatchWithoutActiveSourceConsumersIsRejected() {
        setupSubjectWithVersions("mb-empty");
        register("mb-empty", "svc-a", 2, 1, null, 100);
        assertThatThrownBy(() -> migrationService.createBatch("mb-empty", 1, 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.INVALID_REQUEST);
    }

    @Test
    void sourceAndTargetVersionsMustDiffer() {
        setupSubjectWithVersions("mb-same");
        register("mb-same", "svc-a", 1, 1, null, 100);
        assertThatThrownBy(() -> migrationService.createBatch("mb-same", 1, 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------
    // 确认迁移
    // ------------------------------------------------------------------

    @Test
    void confirmationsMoveConsumersAndCompleteBatchThenDeprecateSource() {
        setupSubjectWithVersions("mb-happy");
        register("mb-happy", "svc-a", 1, 1, null, 100);
        register("mb-happy", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-happy", 1, 2).batchId();

        var c1 = migrationService.confirm("mb-happy", batchId, "svc-a", event("evt-1", 2, 2, 200));
        assertThat(c1.toVersion()).isEqualTo(2);
        assertThat(c1.fromVersion()).isEqualTo(1);

        // 只有一个确认时批次仍开放
        MigrationBatchView afterOne = migrationService.getBatch("mb-happy", batchId);
        assertThat(afterOne.status()).isEqualTo(MigrationBatchStatus.OPEN);
        assertThat(afterOne.pendingConsumers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-b");

        // 依赖确实已切换
        ConsumerView a = service.listConsumers("mb-happy").stream()
                .filter(c -> c.consumer().equals("svc-a")).findFirst().orElseThrow();
        assertThat(a.version()).isEqualTo(2);
        assertThat(a.leaseExpiresAt()).isEqualTo(clock.plusSeconds(200));

        var c2 = migrationService.confirm("mb-happy", batchId, "svc-b", event("evt-2", 2, 2, 200));
        assertThat(c2.consumer()).isEqualTo("svc-b");

        MigrationBatchView done = migrationService.getBatch("mb-happy", batchId);
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.pendingConsumers()).isEmpty();
        assertThat(done.confirmations()).extracting(
                MigrationBatchService.MigrationConfirmationView::eventId).containsExactly("evt-1", "evt-2");
        // 冻结集合全部迁移且无其他使用者，源版本立即进入已废弃
        assertThat(service.getLifecycle("mb-happy", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void duplicateEventIsIdempotentAndDifferentParametersConflict() {
        setupSubjectWithVersions("mb-evt-idem");
        register("mb-evt-idem", "svc-a", 1, 1, null, 100);
        register("mb-evt-idem", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-evt-idem", 1, 2).batchId();

        MigrationEvent first = event("evt-dup", 2, 2, 200);
        var r1 = migrationService.confirm("mb-evt-idem", batchId, "svc-a", first);
        var r2 = migrationService.confirm("mb-evt-idem", batchId, "svc-a", first);
        assertThat(r2.confirmedAt()).isEqualTo(r1.confirmedAt());
        assertThat(r2.updateSeq()).isEqualTo(2);

        // 同事件号携带不同参数 -> 冲突
        assertThatThrownBy(() -> migrationService.confirm("mb-evt-idem", batchId, "svc-a",
                event("evt-dup", 2, 9, 900)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);

        // 同事件号用于另一消费者 -> 冲突
        assertThatThrownBy(() -> migrationService.confirm("mb-evt-idem", batchId, "svc-b",
                event("evt-dup", 2, 2, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void eventWithoutIdIsRejected() {
        setupSubjectWithVersions("mb-noevt");
        register("mb-noevt", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-noevt", 1, 2).batchId();

        assertThatThrownBy(() -> migrationService.confirm("mb-noevt", batchId, "svc-a",
                event(null, 2, 2, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.INVALID_REQUEST);
    }

    @Test
    void eventTargetVersionMustMatchBatchTarget() {
        setupSubjectWithVersions("mb-target");
        register("mb-target", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-target", 1, 2).batchId();

        assertThatThrownBy(() -> migrationService.confirm("mb-target", batchId, "svc-a",
                event("evt-x", 3, 2, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.INVALID_REQUEST);
    }

    @Test
    void nonFrozenConsumerCannotConfirmThroughBatch() {
        setupSubjectWithVersions("mb-outsider");
        register("mb-outsider", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-outsider", 1, 2).batchId();

        assertThatThrownBy(() -> migrationService.confirm("mb-outsider", batchId, "svc-outsider",
                event("evt-out", 2, 1, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_NOT_FROZEN_CONSUMER);

        // 依赖关系未被改动
        assertThat(service.listConsumers("mb-outsider")).extracting(ConsumerView::consumer)
                .doesNotContain("svc-outsider");
    }

    @Test
    void lateConfirmationAfterConsumerRegisteredAnotherVersionIsRejected() {
        setupSubjectWithVersions("mb-late");
        register("mb-late", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-late", 1, 2).batchId();

        // 消费者后来通过普通登记自行迁移到 v3
        register("mb-late", "svc-a", 3, 2, 1, 200);

        // 迟到的批次确认不能覆盖后来登记的 v3
        assertThatThrownBy(() -> migrationService.confirm("mb-late", batchId, "svc-a",
                event("evt-late", 2, 3, 300)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.VERSION_CONDITION_MISMATCH);

        ConsumerView view = service.listConsumers("mb-late").get(0);
        assertThat(view.consumer()).isEqualTo("svc-a");
        assertThat(view.version()).isEqualTo(3);
    }

    @Test
    void staleSeqConfirmationIsRejected() {
        setupSubjectWithVersions("mb-stale");
        register("mb-stale", "svc-a", 1, 5, null, 100);
        long batchId = migrationService.createBatch("mb-stale", 1, 2).batchId();

        assertThatThrownBy(() -> migrationService.confirm("mb-stale", batchId, "svc-a",
                event("evt-stale", 2, 5, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.STALE_UPDATE);
    }

    @Test
    void alreadyConfirmedConsumerCannotConfirmWithDifferentEvent() {
        setupSubjectWithVersions("mb-two-confirm");
        register("mb-two-confirm", "svc-a", 1, 1, null, 100);
        register("mb-two-confirm", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-two-confirm", 1, 2).batchId();
        migrationService.confirm("mb-two-confirm", batchId, "svc-a", event("evt-a", 2, 2, 200));

        assertThatThrownBy(() -> migrationService.confirm("mb-two-confirm", batchId, "svc-a",
                event("evt-a-other", 2, 3, 300)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_ALREADY_CONFIRMED);
    }

    // ------------------------------------------------------------------
    // 租约过期 / 自行迁出 / 扫描推进
    // ------------------------------------------------------------------

    @Test
    void leaseExpiryRemovesFrozenMemberAndCompletesBatchViaScan() {
        setupSubjectWithVersions("mb-expire");
        register("mb-expire", "svc-a", 1, 1, null, 100);
        register("mb-expire", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-expire", 1, 2).batchId();

        migrationService.confirm("mb-expire", batchId, "svc-a", event("evt-a", 2, 2, 500));

        // svc-b 租约自然过期
        clock.advanceSeconds(150);
        var scan = service.scanDeprecations();

        assertThat(scan.completedBatches()).isEqualTo(1);
        MigrationBatchView done = migrationService.getBatch("mb-expire", batchId);
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(done.leaseExpiredMembers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-b");
        assertThat(service.getLifecycle("mb-expire", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void memberWhoSelfMigratesBecomesDetachedAndBatchCompletes() {
        setupSubjectWithVersions("mb-detach");
        register("mb-detach", "svc-a", 1, 1, null, 100);
        register("mb-detach", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-detach", 1, 2).batchId();

        // svc-b 不经批次，直接通过普通登记切到目标版本
        register("mb-detach", "svc-b", 2, 2, 1, 200);
        // svc-a 确认
        migrationService.confirm("mb-detach", batchId, "svc-a", event("evt-a", 2, 2, 200));
        service.scanDeprecations();

        MigrationBatchView done = migrationService.getBatch("mb-detach", batchId);
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(done.detachedMembers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-b");
    }

    @Test
    void lateJoiningActiveSourceUserKeepsSourceUndeprecatableAfterBatchCompletes() {
        setupSubjectWithVersions("mb-latejoiner");
        register("mb-latejoiner", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-latejoiner", 1, 2).batchId();

        // 批次创建后新登记一个 v1 使用者（不在冻结集合中），租约很久
        register("mb-latejoiner", "svc-new", 1, 1, null, 1000);
        migrationService.confirm("mb-latejoiner", batchId, "svc-a", event("evt-a", 2, 2, 500));

        // 冻结集合全部解决 -> 批次完成，但源版本仍有活跃使用者，不能废弃
        MigrationBatchView done = migrationService.getBatch("mb-latejoiner", batchId);
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(service.getLifecycle("mb-latejoiner", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);
        assertThat(done.sourceDeprecationBlockReasons())
                .anyMatch(r -> r.contains("svc-new"));

        // 扫描仍不能漏掉该活跃使用者
        assertThat(service.scanDeprecations().deprecatedVersions()).isZero();

        // 新使用者迁移走之后，扫描推进废弃
        register("mb-latejoiner", "svc-new", 2, 2, 1, 1000);
        assertThat(service.scanDeprecations().deprecatedVersions()).isEqualTo(1);
        assertThat(service.getLifecycle("mb-latejoiner", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void scheduledSourceWithFutureEffectiveStaysScheduledWhenBatchCompletes() {
        setupSubjectWithVersions("mb-future-eff");
        register("mb-future-eff", "svc-a", 1, 1, null, 100);
        service.requestDeprecation("mb-future-eff", 1, clock.plusSeconds(500), null, null);
        long batchId = migrationService.createBatch("mb-future-eff", 1, 2).batchId();

        migrationService.confirm("mb-future-eff", batchId, "svc-a", event("evt-a", 2, 2, 500));
        assertThat(service.getLifecycle("mb-future-eff", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);

        clock.advanceSeconds(501);
        assertThat(service.scanDeprecations().deprecatedVersions()).isEqualTo(1);
        assertThat(service.getLifecycle("mb-future-eff", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    // ------------------------------------------------------------------
    // 取消
    // ------------------------------------------------------------------

    @Test
    void cancelStopsProgressButKeepsConfirmedConsumerVersions() {
        setupSubjectWithVersions("mb-cancel");
        register("mb-cancel", "svc-a", 1, 1, null, 100);
        register("mb-cancel", "svc-b", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-cancel", 1, 2).batchId();
        migrationService.confirm("mb-cancel", batchId, "svc-a", event("evt-a", 2, 2, 200));

        MigrationBatchView cancelled = migrationService.cancel("mb-cancel", batchId, "rollout paused");
        assertThat(cancelled.status()).isEqualTo(MigrationBatchStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("rollout paused");

        // 已确认的消费者版本不回退
        ConsumerView a = service.listConsumers("mb-cancel").stream()
                .filter(c -> c.consumer().equals("svc-a")).findFirst().orElseThrow();
        assertThat(a.version()).isEqualTo(2);
        // 未确认的消费者保持原状
        ConsumerView b = service.listConsumers("mb-cancel").stream()
                .filter(c -> c.consumer().equals("svc-b")).findFirst().orElseThrow();
        assertThat(b.version()).isEqualTo(1);
        // 源版本没有被推进废弃
        assertThat(service.getLifecycle("mb-cancel", 1).lifecycle()).isEqualTo(VersionLifecycle.ACTIVE);

        // 取消后不再接受确认
        assertThatThrownBy(() -> migrationService.confirm("mb-cancel", batchId, "svc-b",
                event("evt-b", 2, 2, 200)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);

        // 重复取消幂等
        assertThat(migrationService.cancel("mb-cancel", batchId, "again").status())
                .isEqualTo(MigrationBatchStatus.CANCELLED);
    }

    @Test
    void completedBatchCannotBeCancelled() {
        setupSubjectWithVersions("mb-nocancel");
        register("mb-nocancel", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-nocancel", 1, 2).batchId();
        migrationService.confirm("mb-nocancel", batchId, "svc-a", event("evt-a", 2, 2, 200));

        assertThatThrownBy(() -> migrationService.cancel("mb-nocancel", batchId, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);
    }

    @Test
    void cancellingJustAsLastMemberResolvesCompletesInstead() {
        setupSubjectWithVersions("mb-cancel-race");
        register("mb-cancel-race", "svc-a", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-cancel-race", 1, 2).batchId();
        clock.advanceSeconds(150); // 租约过期

        // 取消时冻结集合已全部解决（租约过期），批次照常完成
        MigrationBatchView view = migrationService.cancel("mb-cancel-race", batchId, null);
        assertThat(view.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(service.getLifecycle("mb-cancel-race", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    // ------------------------------------------------------------------
    // 查询视图
    // ------------------------------------------------------------------

    @Test
    void batchViewPartitionsPendingConfirmedExpiredAndDetachedMembers() {
        setupSubjectWithVersions("mb-view");
        register("mb-view", "svc-a", 1, 1, null, 100);
        register("mb-view", "svc-b", 1, 1, null, 100);
        register("mb-view", "svc-c", 1, 1, null, 10);
        register("mb-view", "svc-d", 1, 1, null, 100);
        long batchId = migrationService.createBatch("mb-view", 1, 2).batchId();

        migrationService.confirm("mb-view", batchId, "svc-a", event("evt-a", 2, 2, 500));
        register("mb-view", "svc-d", 2, 2, 1, 200);
        clock.advanceSeconds(50); // svc-c 过期
        service.scanDeprecations(); // 结算过期与迁出，但 svc-b 仍 PENDING

        MigrationBatchView view = migrationService.getBatch("mb-view", batchId);
        assertThat(view.status()).isEqualTo(MigrationBatchStatus.OPEN);
        assertThat(view.pendingConsumers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-b");
        assertThat(view.confirmations()).extracting(
                MigrationBatchService.MigrationConfirmationView::consumer).containsExactly("svc-a");
        assertThat(view.leaseExpiredMembers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-c");
        assertThat(view.detachedMembers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-d");
        // 源版本仍 ACTIVE，查询需说明尚不能废弃的原因
        assertThat(view.sourceDeprecationBlockReasons())
                .anyMatch(r -> r.contains("ACTIVE"));

        // 列表端点
        assertThat(migrationService.listBatches("mb-view")).hasSize(1);
    }

    @Test
    void missingBatchAndSubjectReturn404() {
        setupSubjectWithVersions("mb-404");
        assertThatThrownBy(() -> migrationService.getBatch("mb-404", 999))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_NOT_FOUND);
        assertThatThrownBy(() -> migrationService.listBatches("nope"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.SUBJECT_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 并发：确认、续租与废弃扫描
    // ------------------------------------------------------------------

    @Test
    void concurrentConfirmationsRenewalsAndScansYieldConsistentResult() throws Exception {
        setupSubjectWithVersions("mb-concurrent");
        register("mb-concurrent", "svc-a", 1, 1, null, 100_000);
        register("mb-concurrent", "svc-b", 1, 1, null, 100_000);
        long batchId = migrationService.createBatch("mb-concurrent", 1, 2).batchId();

        int attempts = 40;
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Exception> errors = new ArrayList<>();

        Runnable scanner = () -> {
            try {
                ready.countDown();
                start.await();
                for (int i = 0; i < attempts; i++) {
                    service.scanDeprecations();
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };
        Runnable renewer = () -> {
            try {
                ready.countDown();
                start.await();
                // svc-b 反复续租但停留在 v1
                for (int i = 2; i <= attempts + 1; i++) {
                    register("mb-concurrent", "svc-b", 1, i, 1, 100_000L + i);
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };
        Runnable confirmer = () -> {
            try {
                ready.countDown();
                start.await();
                // svc-a 反复重放同一确认事件（幂等）与扫描并发
                for (int i = 0; i < attempts; i++) {
                    migrationService.confirm("mb-concurrent", batchId, "svc-a",
                            event("evt-a", 2, 2, 200_000));
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };

        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(scanner));
        futures.add(pool.submit(renewer));
        futures.add(pool.submit(confirmer));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(errors).isEmpty();

        // svc-b 仍活跃使用源版本：批次未完成、源版本未废弃
        MigrationBatchView view = migrationService.getBatch("mb-concurrent", batchId);
        assertThat(view.status()).isEqualTo(MigrationBatchStatus.OPEN);
        assertThat(view.pendingConsumers()).extracting(
                MigrationBatchService.MigrationMemberView::consumer).containsExactly("svc-b");
        assertThat(service.getLifecycle("mb-concurrent", 1).lifecycle())
                .isEqualTo(VersionLifecycle.ACTIVE);

        // svc-a 的确认只生效一次
        assertThat(view.confirmations()).hasSize(1);
        ConsumerView a = service.listConsumers("mb-concurrent").stream()
                .filter(c -> c.consumer().equals("svc-a")).findFirst().orElseThrow();
        assertThat(a.version()).isEqualTo(2);

        // svc-b 最终确认后，批次完成并废弃源版本
        migrationService.confirm("mb-concurrent", batchId, "svc-b",
                event("evt-b", 2, attempts + 2, 300_000));
        assertThat(migrationService.getBatch("mb-concurrent", batchId).status())
                .isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(service.getLifecycle("mb-concurrent", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    /** 固定起点、可推进的时钟，用于确定性测试。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        Instant plusSeconds(long seconds) {
            return instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
