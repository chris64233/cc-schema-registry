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

import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerRegistration;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerView;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.DeleteEligibility;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.DeprecationResult;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.LifecycleView;

@SpringBootTest
class LifecycleServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTRACT_V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String CONTRACT_V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"default\": \"\"}}}";

    @Autowired
    private SchemaRegistryService service;

    @Autowired
    private TimeProvider timeProvider;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MutableClock clock;

    @AfterEach
    void resetClock() {
        // TimeProvider 是跨测试类共享的单例，结束后恢复系统时钟，避免影响其它测试类。
        timeProvider.setClock(Clock.systemUTC());
    }

    @BeforeEach
    void setUp() {
        // 全局扫描会触及全部主题，逐测试清空数据避免相互干扰。
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("migration_confirmations", "migration_batch_members",
                "migration_batches", "compat_reference_edges", "version_audit_events", "operation_requests",
                "consumer_dependencies", "idempotency_records", "schema_versions", "subjects")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock = new MutableClock(T0);
        timeProvider.setClock(clock);
    }

    private void setupSubjectWithTwoVersions(String subject) {
        service.createSubject(subject, CompatibilityMode.BACKWARD);
        service.publish(subject, CONTRACT_V1, null);
        service.publish(subject, CONTRACT_V2, null);
    }

    private ConsumerRegistration registration(String consumer, int version, long seq,
            Integer expectedVersion, String requestId, Instant leaseExpiresAt) {
        return new ConsumerRegistration(consumer, version, leaseExpiresAt, seq, expectedVersion, requestId);
    }

    // ------------------------------------------------------------------
    // 消费者依赖登记
    // ------------------------------------------------------------------

    @Test
    void registersConsumerAndListsActiveLeases() {
        setupSubjectWithTwoVersions("lc-register");
        service.registerConsumer("lc-register",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(100)));

        List<ConsumerView> consumers = service.listConsumers("lc-register");
        assertThat(consumers).hasSize(1);
        assertThat(consumers.get(0).consumer()).isEqualTo("svc-a");
        assertThat(consumers.get(0).version()).isEqualTo(1);
        assertThat(consumers.get(0).active()).isTrue();
    }

    @Test
    void expiredLeaseIsNotActive() {
        setupSubjectWithTwoVersions("lc-expired");
        service.registerConsumer("lc-expired",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(10)));

        clock.advanceSeconds(20);

        List<ConsumerView> consumers = service.listConsumers("lc-expired");
        assertThat(consumers).hasSize(1);
        assertThat(consumers.get(0).active()).isFalse();
    }

    @Test
    void lateHeartbeatWithOlderSeqCannotOverwriteNewerDependencyVersion() {
        setupSubjectWithTwoVersions("lc-stale");
        // 初始登记 v1
        service.registerConsumer("lc-stale",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(100)));
        // 迁移到 v2（seq 更大，带版本条件 expectedVersion=1）
        service.registerConsumer("lc-stale",
                registration("svc-a", 2, 3, 1, null, clock.plusSeconds(200)));
        // 迟到心跳：仍以为自己在 v1，seq=2，尝试续租 v1
        assertThatThrownBy(() -> service.registerConsumer("lc-stale",
                registration("svc-a", 1, 2, 2, null, clock.plusSeconds(300))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.STALE_UPDATE);

        ConsumerView view = service.listConsumers("lc-stale").get(0);
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.updateSeq()).isEqualTo(3);
    }

    @Test
    void heartbeatWithNewerSeqButWrongExpectedVersionIsRejected() {
        setupSubjectWithTwoVersions("lc-cas");
        service.registerConsumer("lc-cas",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(100)));
        service.registerConsumer("lc-cas",
                registration("svc-a", 2, 2, 1, null, clock.plusSeconds(200)));

        // 即便 seq 更新，但版本条件基于过期认知（expectedVersion=1，实际已是 2），拒绝
        assertThatThrownBy(() -> service.registerConsumer("lc-cas",
                registration("svc-a", 1, 9, 1, null, clock.plusSeconds(300))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.VERSION_CONDITION_MISMATCH);

        // 被拒绝的更新不得推进 seq，后续正确心跳仍可进行
        service.registerConsumer("lc-cas",
                registration("svc-a", 2, 3, 2, null, clock.plusSeconds(400)));
        ConsumerView view = service.listConsumers("lc-cas").get(0);
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.updateSeq()).isEqualTo(3);
    }

    @Test
    void consumerUpdateRequestIdIsIdempotent() {
        setupSubjectWithTwoVersions("lc-consumer-idem");
        ConsumerRegistration first = registration("svc-a", 1, 1, null, "cu-1", clock.plusSeconds(100));
        var r1 = service.registerConsumer("lc-consumer-idem", first);
        var r2 = service.registerConsumer("lc-consumer-idem", first);

        assertThat(r1.created()).isTrue();
        assertThat(r2.created()).isFalse();
        assertThat(r2.version()).isEqualTo(1);
        assertThat(r2.updateSeq()).isEqualTo(1);

        // 同一请求号携带不同参数 -> 冲突
        assertThatThrownBy(() -> service.registerConsumer("lc-consumer-idem",
                registration("svc-a", 2, 1, null, "cu-1", clock.plusSeconds(100))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void cannotRegisterConsumerOnDeprecatedVersion() {
        setupSubjectWithTwoVersions("lc-dep-reg");
        service.requestDeprecation("lc-dep-reg", 1, clock.plusSeconds(-1), null, null);
        service.scanDeprecations();

        assertThatThrownBy(() -> service.registerConsumer("lc-dep-reg",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(100))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 废弃
    // ------------------------------------------------------------------

    @Test
    void deprecationWithoutConsumersBecomesDeprecatedImmediatelyWhenEffective() {
        setupSubjectWithTwoVersions("lc-dep-immediate");
        DeprecationResult result = service.requestDeprecation("lc-dep-immediate", 1,
                clock.plusSeconds(-1), null, null);

        assertThat(result.lifecycle()).isEqualTo(VersionLifecycle.DEPRECATED);
        assertThat(result.blockingConsumers()).isEmpty();
        LifecycleView view = service.getLifecycle("lc-dep-immediate", 1);
        assertThat(view.lifecycle()).isEqualTo(VersionLifecycle.DEPRECATED);
        assertThat(view.deprecatedAt()).isNotNull();
    }

    @Test
    void activeConsumerBlocksDeprecationUntilMigrationOrLeaseExpiry() {
        setupSubjectWithTwoVersions("lc-block");
        service.registerConsumer("lc-block",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(100)));

        DeprecationResult blocked = service.requestDeprecation("lc-block", 1, clock.plusSeconds(-1), null,
                null);
        assertThat(blocked.lifecycle()).isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);
        assertThat(blocked.blockingConsumers()).extracting(ConsumerView::consumer).containsExactly("svc-a");

        // 生效时间已到，扫描仍被有效租约阻断
        assertThat(service.scanDeprecations()).isZero();
        assertThat(service.getLifecycle("lc-block", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);

        // 消费者迁移到 v2 后，扫描推进废弃
        service.registerConsumer("lc-block",
                registration("svc-a", 2, 2, 1, null, clock.plusSeconds(200)));
        assertThat(service.scanDeprecations()).isEqualTo(1);
        assertThat(service.getLifecycle("lc-block", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void deprecationProceedsAfterLeaseExpiryWithoutMigration() {
        setupSubjectWithTwoVersions("lc-lease");
        service.registerConsumer("lc-lease",
                registration("svc-a", 1, 1, null, null, clock.plusSeconds(50)));
        service.requestDeprecation("lc-lease", 1, clock.plusSeconds(10), null, null);

        clock.advanceSeconds(30);
        assertThat(service.scanDeprecations()).isZero(); // 租约仍有效

        clock.advanceSeconds(30);
        assertThat(service.scanDeprecations()).isEqualTo(1); // 租约过期
        assertThat(service.getLifecycle("lc-lease", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void futureEffectiveAtStaysScheduledThenScanTransitions() {
        setupSubjectWithTwoVersions("lc-future");
        DeprecationResult scheduled = service.requestDeprecation("lc-future", 1, clock.plusSeconds(100),
                null, null);
        assertThat(scheduled.lifecycle()).isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);

        assertThat(service.scanDeprecations()).isZero();
        clock.advanceSeconds(101);
        assertThat(service.scanDeprecations()).isEqualTo(1);
        assertThat(service.getLifecycle("lc-future", 1).lifecycle()).isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void deprecationRequestIdIsIdempotent() {
        setupSubjectWithTwoVersions("lc-dep-idem");
        Instant eff = clock.plusSeconds(100);
        var r1 = service.requestDeprecation("lc-dep-idem", 1, eff, null, "dep-1");
        var r2 = service.requestDeprecation("lc-dep-idem", 1, eff, null, "dep-1");

        assertThat(r1.lifecycle()).isEqualTo(r2.lifecycle());
        assertThat(r2.effectiveAt()).isEqualTo(eff);

        Instant otherEff = clock.plusSeconds(200);
        assertThatThrownBy(() -> service.requestDeprecation("lc-dep-idem", 1, otherEff, null, "dep-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void concurrentDeprecationScanAndLeaseRenewalYieldsConsistentResult() throws Exception {
        // 高并发：一个线程反复尝试推进废弃，另一个线程反复续租。结果必须自洽：
        // 版本要么在租约有效期间保持待废弃，要么在续租停止后进入已废弃。
        setupSubjectWithTwoVersions("lc-race");
        Instant lease = clock.plusSeconds(100000);
        service.registerConsumer("lc-race",
                registration("svc-a", 1, 1, null, null, lease));
        service.requestDeprecation("lc-race", 1, clock.plusSeconds(-1), null, null);

        int attempts = 40;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Exception> errors = new ArrayList<>();

        Future<?> scanner = pool.submit(() -> {
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
        });
        Future<?> renewer = pool.submit(() -> {
            try {
                ready.countDown();
                start.await();
                for (int i = 2; i <= attempts + 1; i++) {
                    service.registerConsumer("lc-race",
                            registration("svc-a", 1, i, 1, null, clock.plusSeconds(100000 + i)));
                }
            } catch (ApiException e) {
                // 若扫描先于某次续租把版本置为已废弃，续租收到 LIFECYCLE_CONFLICT 是合法结局
                synchronized (errors) {
                    if (!ErrorCodes.LIFECYCLE_CONFLICT.equals(e.code())) {
                        errors.add(e);
                    }
                }
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        });

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        scanner.get(30, TimeUnit.SECONDS);
        renewer.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(errors).isEmpty();

        // 关键不变量：若版本已废弃，则最后一次续租必然没有成功（无有效依赖）；
        // 若仍待废弃，则依赖依然有效。两种状态都自洽。
        LifecycleView view = service.getLifecycle("lc-race", 1);
        if (view.lifecycle() == VersionLifecycle.DEPRECATION_SCHEDULED) {
            assertThat(view.blockingConsumers()).isNotEmpty();
        } else {
            assertThat(view.lifecycle()).isEqualTo(VersionLifecycle.DEPRECATED);
            assertThat(view.blockingConsumers()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 受控删除
    // ------------------------------------------------------------------

    @Test
    void deletedVersionKeepsDigestAndAuditButRemovesPayload() {
        setupSubjectWithTwoVersions("lc-delete");
        service.requestDeprecation("lc-delete", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();

        var result = service.requestDeletion("lc-delete", 2, null);
        assertThat(result.deleted()).isTrue();

        SchemaVersionEntity entity = service.getVersion("lc-delete", 2);
        assertThat(entity.getLifecycle()).isEqualTo(VersionLifecycle.TOMBSTONE);
        assertThat(entity.getContent()).isNull();
        assertThat(entity.getContentHash()).isNotBlank();
        assertThat(entity.getDeletedAt()).isNotNull();

        LifecycleView view = service.getLifecycle("lc-delete", 2);
        assertThat(view.contentHash()).isEqualTo(entity.getContentHash());
        assertThat(view.audit()).extracting(a -> a.eventType())
                .contains("DEPRECATION_REQUESTED", "DEPRECATED", "DELETE_REQUESTED", "DELETED");
    }

    @Test
    void cannotDeleteNonDeprecatedVersion() {
        setupSubjectWithTwoVersions("lc-del-active");
        DeleteEligibility eligibility = service.explainDeletion("lc-del-active", 1);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.reasons()).anyMatch(r -> r.contains("not DEPRECATED"));

        assertThatThrownBy(() -> service.requestDeletion("lc-del-active", 1, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.DELETE_NOT_ELIGIBLE);
    }

    @Test
    void cannotDeleteWhileReferencedByCompatibilityChecksOfOtherVersions() {
        setupSubjectWithTwoVersions("lc-del-ref");
        // v2 发布时针对 v1 做过检查：v1 被 v2 引用。删除 v1（最老版本）会被阻断；
        // v2 没有更新版本引用它。
        service.requestDeprecation("lc-del-ref", 1, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();

        DeleteEligibility blocked = service.explainDeletion("lc-del-ref", 1);
        assertThat(blocked.eligible()).isFalse();
        assertThat(blocked.referencedByVersions()).contains(2);

        // v2 可以删除（无入边），删除后其出边一并移除，v1 引用随之解除
        service.requestDeprecation("lc-del-ref", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();
        service.requestDeletion("lc-del-ref", 2, null);

        DeleteEligibility nowEligible = service.explainDeletion("lc-del-ref", 1);
        assertThat(nowEligible.referencedByVersions()).isEmpty();
        service.requestDeletion("lc-del-ref", 1, null);
        assertThat(service.getLifecycle("lc-del-ref", 1).lifecycle())
                .isEqualTo(VersionLifecycle.TOMBSTONE);
    }

    @Test
    void cannotDeleteBeforeRetentionElapses() {
        setupSubjectWithTwoVersions("lc-retention");
        long retentionMillis = 100_000;
        service.requestDeprecation("lc-retention", 2, clock.plusSeconds(-1), retentionMillis, null);
        service.scanDeprecations();

        DeleteEligibility early = service.explainDeletion("lc-retention", 2);
        assertThat(early.eligible()).isFalse();
        assertThat(early.reasons()).anyMatch(r -> r.contains("retention period not elapsed"));

        clock.advanceMillis(retentionMillis + 1);
        assertThat(service.explainDeletion("lc-retention", 2).eligible()).isTrue();
    }

    @Test
    void deleteRequestIdIsIdempotent() {
        setupSubjectWithTwoVersions("lc-del-idem");
        service.requestDeprecation("lc-del-idem", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();

        var r1 = service.requestDeletion("lc-del-idem", 2, "del-1");
        var r2 = service.requestDeletion("lc-del-idem", 2, "del-1");
        assertThat(r1.deleted()).isTrue();
        assertThat(r2.deleted()).isTrue();
        assertThat(r2.deletedAt()).isEqualTo(r1.deletedAt());

        assertThatThrownBy(() -> service.requestDeletion("lc-del-idem", 1, "del-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void republishingIdenticalContractAfterTombstoneMapsToSameVersion() {
        setupSubjectWithTwoVersions("lc-republish");
        service.requestDeprecation("lc-republish", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();
        service.requestDeletion("lc-republish", 2, null);

        // 相同契约（规范化后一致）再次发布，仍映射到版本 2，且不新建版本
        var result = service.publish("lc-republish", CONTRACT_V2, null);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.created()).isFalse();
        assertThat(service.listVersions("lc-republish")).hasSize(2);
    }

    /** 固定起点、可推进的时钟，用于确定性测试。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            advanceMillis(TimeUnit.SECONDS.toMillis(seconds));
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
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
