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
import org.springframework.http.HttpStatus;

import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerRegistration;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.MemberView;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.MigrationBatchCreation;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.MigrationBatchRequest;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.MigrationBatchView;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.MigrationConfirmationRequest;

@SpringBootTest
class MigrationBatchServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CONTRACT_V1 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}";
    private static final String CONTRACT_V2 =
            "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                    + " \"name\": {\"type\": \"string\", \"default\": \"\"}}}";
    /** 与 v1 在 BACKWARD 下不兼容：删除了 required 的 id。 */
    private static final String CONTRACT_V3_INCOMPAT =
            "{\"properties\": {\"other\": {\"type\": \"string\"}}}";

    @Autowired
    private SchemaRegistryService service;

    @Autowired
    private TimeProvider timeProvider;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private SchemaVersionRepository versionRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    private MutableClock clock;

    @AfterEach
    void resetClock() {
        timeProvider.setClock(Clock.systemUTC());
    }

    @BeforeEach
    void setUp() {
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

    private void register(String subject, String consumer, int version, long seq, long leaseSeconds) {
        service.registerConsumer(subject,
                new ConsumerRegistration(consumer, version, clock.plusSeconds(leaseSeconds), seq, null, null));
    }

    private MigrationBatchView createBatch(String subject, int from, int to) {
        return createBatch(subject, from, to, null).batch();
    }

    private MigrationBatchCreation createBatch(String subject, int from, int to, String key) {
        return service.createMigrationBatch(subject, new MigrationBatchRequest(from, to, key));
    }

    private MigrationBatchView confirm(String subject, int batchNo, String consumer, String eventId) {
        return service.confirmMigration(subject, batchNo,
                new MigrationConfirmationRequest(consumer, eventId, null));
    }

    private MemberView pendingOf(MigrationBatchView batch, String consumer) {
        return batch.pendingConsumers().stream().filter(m -> m.consumer().equals(consumer)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // 创建批次与冻结
    // ------------------------------------------------------------------

    @Test
    void createsBatchAndFreezesActiveConsumersOnSourceVersion() {
        setupSubjectWithTwoVersions("mb-create");
        register("mb-create", "svc-a", 1, 1, 100);
        register("mb-create", "svc-b", 1, 1, 100);
        register("mb-create", "svc-on-v2", 2, 1, 100);

        MigrationBatchCreation creation = createBatch("mb-create", 1, 2, "batch-key-1");
        assertThat(creation.created()).isTrue();
        MigrationBatchView batch = creation.batch();
        assertThat(batch.batchNo()).isEqualTo(1);
        assertThat(batch.status()).isEqualTo(MigrationBatchStatus.IN_PROGRESS);
        assertThat(batch.pendingConsumers()).extracting(MemberView::consumer)
                .containsExactly("svc-a", "svc-b");
        assertThat(batch.sourceBlockReasons()).isNotEmpty();
    }

    @Test
    void expiredLeaseAndOtherVersionConsumersAreNotFrozen() {
        setupSubjectWithTwoVersions("mb-freeze");
        register("mb-freeze", "svc-active", 1, 1, 100);
        register("mb-freeze", "svc-expired", 1, 2, 10);
        register("mb-freeze", "svc-on-v2", 2, 1, 100);

        clock.advanceSeconds(20); // svc-expired 租约过期，其余仍有效
        MigrationBatchView batch = createBatch("mb-freeze", 1, 2);
        assertThat(batch.pendingConsumers()).extracting(MemberView::consumer).containsExactly("svc-active");
    }

    @Test
    void cannotCreateBatchWithIncompatibleTargetAndReturnsDiffs() {
        setupSubjectWithTwoVersions("mb-incompat");
        register("mb-incompat", "svc-a", 1, 1, 100);
        // 直接落库一个未走发布校验的 v3（与 v1 在 BACKWARD 下不兼容：删除了 required 的 id），
        // 模拟“目标版本与消费者当前版本不满足兼容策略”的场景（如主题策略曾收紧）。
        SubjectEntity subject = subjectRepository.findByName("mb-incompat").orElseThrow();
        versionRepository.save(new SchemaVersionEntity(subject, 3, CONTRACT_V3_INCOMPAT,
                "direct-hash-v3", T0));

        assertThatThrownBy(() -> createBatch("mb-incompat", 1, 3))
                .isInstanceOf(IncompatibleContractException.class)
                .satisfies(e -> {
                    var diffs = ((IncompatibleContractException) e).diffs();
                    assertThat(diffs).isNotEmpty();
                    assertThat(diffs.get(0).property()).isEqualTo("id");
                });
        // 不产生半成品批次
        assertThat(service.listMigrationBatches("mb-incompat")).isEmpty();
    }

    @Test
    void cannotCreateBatchWhenNoActiveConsumersOnSource() {
        setupSubjectWithTwoVersions("mb-empty");
        assertThatThrownBy(() -> createBatch("mb-empty", 1, 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_NO_ACTIVE_CONSUMERS);
    }

    @Test
    void cannotCreateSecondInProgressBatchForSameSource() {
        setupSubjectWithTwoVersions("mb-dup");
        register("mb-dup", "svc-a", 1, 1, 100);
        createBatch("mb-dup", 1, 2);

        assertThatThrownBy(() -> createBatch("mb-dup", 1, 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);
    }

    @Test
    void cannotTargetDeprecatedOrDeletedVersion() {
        setupSubjectWithTwoVersions("mb-target");
        register("mb-target", "svc-a", 1, 1, 100);
        service.requestDeprecation("mb-target", 2, clock.plusSeconds(-1), 0L, null);
        service.scanDeprecations();

        assertThatThrownBy(() -> createBatch("mb-target", 1, 2))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.LIFECYCLE_CONFLICT);
    }

    @Test
    void sourceAndTargetMustDiffer() {
        setupSubjectWithTwoVersions("mb-same");
        register("mb-same", "svc-a", 1, 1, 100);
        assertThatThrownBy(() -> createBatch("mb-same", 1, 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBatchIdempotencyKeyReplaysAndConflictsOnDifferentParams() {
        setupSubjectWithTwoVersions("mb-idem");
        register("mb-idem", "svc-a", 1, 1, 100);

        MigrationBatchCreation first = createBatch("mb-idem", 1, 2, "key-1");
        MigrationBatchCreation replay = createBatch("mb-idem", 1, 2, "key-1");
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.batch().batchNo()).isEqualTo(1);

        // 同键不同参数冲突
        assertThatThrownBy(() -> createBatch("mb-idem", 2, 1, "key-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 迁移确认
    // ------------------------------------------------------------------

    @Test
    void confirmationMovesConsumerDependencyToTargetAndCompletesBatch() {
        setupSubjectWithTwoVersions("mb-confirm");
        register("mb-confirm", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-confirm", 1, 2);

        MigrationBatchView after = confirm("mb-confirm", batch.batchNo(), "svc-a", "evt-1");
        assertThat(after.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(after.completedAt()).isNotNull();
        assertThat(after.confirmedConsumers()).extracting(MemberView::consumer).containsExactly("svc-a");
        assertThat(after.confirmations()).hasSize(1);
        assertThat(after.confirmations().get(0).eventId()).isEqualTo("evt-1");
        assertThat(after.confirmations().get(0).targetVersion()).isEqualTo(2);
        assertThat(after.sourceBlockReasons()).isEmpty();

        // 依赖被 CAS 到目标版本，沿用原更新号与租约
        var consumer = service.listConsumers("mb-confirm").get(0);
        assertThat(consumer.version()).isEqualTo(2);
        assertThat(consumer.updateSeq()).isEqualTo(1);
        assertThat(consumer.leaseExpiresAt()).isEqualTo(clock.plusSeconds(100));
        // 批次完成推动源版本进入已废弃
        assertThat(service.getLifecycle("mb-confirm", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void duplicateEventIsIdempotentAndDoesNotDuplicateHistory() {
        setupSubjectWithTwoVersions("mb-event-idem");
        register("mb-event-idem", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-event-idem", 1, 2);

        confirm("mb-event-idem", batch.batchNo(), "svc-a", "evt-1");
        MigrationBatchView replay = confirm("mb-event-idem", batch.batchNo(), "svc-a", "evt-1");
        assertThat(replay.confirmations()).hasSize(1);
        assertThat(replay.confirmedConsumers()).hasSize(1);
    }

    @Test
    void reusedEventIdForDifferentConfirmationConflicts() {
        setupSubjectWithTwoVersions("mb-event-reuse");
        register("mb-event-reuse", "svc-a", 1, 1, 100);
        register("mb-event-reuse", "svc-b", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-event-reuse", 1, 2);
        confirm("mb-event-reuse", batch.batchNo(), "svc-a", "evt-1");

        assertThatThrownBy(() -> confirm("mb-event-reuse", batch.batchNo(), "svc-b", "evt-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void confirmationTargetVersionMustMatchBatchTarget() {
        setupSubjectWithTwoVersions("mb-wrong-target");
        register("mb-wrong-target", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-wrong-target", 1, 2);

        assertThatThrownBy(() -> service.confirmMigration("mb-wrong-target", batch.batchNo(),
                new MigrationConfirmationRequest("svc-a", "evt-1", 1)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_MISMATCH);
    }

    @Test
    void consumerNotInFrozenSetCannotConfirm() {
        setupSubjectWithTwoVersions("mb-outsider");
        register("mb-outsider", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-outsider", 1, 2);
        // 批次创建后新登记到源版本的消费者不在冻结集合内
        register("mb-outsider", "svc-late", 1, 1, 100);

        assertThatThrownBy(() -> confirm("mb-outsider", batch.batchNo(), "svc-late", "evt-x"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_MISMATCH);
        // 依赖关系未被改变
        assertThat(service.listConsumers("mb-outsider")).filteredOn(c -> c.consumer().equals("svc-late"))
                .singleElement().extracting(c -> c.version()).isEqualTo(1);
    }

    @Test
    void lateConfirmationFromOldBatchCannotOverwriteNewerRegisteredVersion() {
        setupSubjectWithTwoVersions("mb-late");
        register("mb-late", "svc-a", 1, 1, 100);
        register("mb-late", "svc-b", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-late", 1, 2);

        // 消费者已自行迁移到 v2（后来登记的版本）
        service.registerConsumer("mb-late",
                new ConsumerRegistration("svc-a", 2, clock.plusSeconds(200), 2, 1, null));

        assertThatThrownBy(() -> confirm("mb-late", batch.batchNo(), "svc-a", "evt-late"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_VERSION_CONFLICT);
        assertThat(service.listConsumers("mb-late")).filteredOn(c -> c.consumer().equals("svc-a"))
                .singleElement().extracting(c -> c.version()).isEqualTo(2);

        // 该成员在视图中按当前依赖实时归类为 RELOCATED，批次随 svc-b 确认而完成
        MigrationBatchView view = service.getMigrationBatch("mb-late", batch.batchNo());
        assertThat(view.removedConsumers()).extracting(MemberView::consumer).contains("svc-a");
        confirm("mb-late", batch.batchNo(), "svc-b", "evt-b");
        MigrationBatchView done = service.getMigrationBatch("mb-late", batch.batchNo());
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
    }

    @Test
    void confirmationOnClosedBatchIsRejected() {
        setupSubjectWithTwoVersions("mb-closed");
        register("mb-closed", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-closed", 1, 2);
        service.cancelMigrationBatch("mb-closed", batch.batchNo());

        assertThatThrownBy(() -> confirm("mb-closed", batch.batchNo(), "svc-a", "evt-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_CLOSED);
    }

    // ------------------------------------------------------------------
    // 租约过期、取消与查询
    // ------------------------------------------------------------------

    @Test
    void leaseExpiryRemovesMemberAndCompletesBatchViaScan() {
        setupSubjectWithTwoVersions("mb-lease");
        register("mb-lease", "svc-a", 1, 1, 50);
        register("mb-lease", "svc-b", 1, 1, 50);
        MigrationBatchView batch = createBatch("mb-lease", 1, 2);
        confirm("mb-lease", batch.batchNo(), "svc-a", "evt-a");

        MigrationBatchView waiting = service.getMigrationBatch("mb-lease", batch.batchNo());
        assertThat(waiting.status()).isEqualTo(MigrationBatchStatus.IN_PROGRESS);
        assertThat(waiting.pendingConsumers()).extracting(MemberView::consumer).containsExactly("svc-b");

        // 租约未过期（+40 < +50）时扫描不推进
        clock.advanceSeconds(40);
        assertThat(service.scanDeprecations()).isZero();

        clock.advanceSeconds(11); // 来到 +51，租约过期
        assertThat(service.scanDeprecations()).isEqualTo(1);

        MigrationBatchView done = service.getMigrationBatch("mb-lease", batch.batchNo());
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        MemberView removed = done.removedConsumers().get(0);
        assertThat(removed.consumer()).isEqualTo("svc-b");
        assertThat(removed.status()).isEqualTo(MigrationMemberStatus.LEASE_EXPIRED);
        assertThat(removed.resolvedAt()).isNotNull();
        assertThat(service.getLifecycle("mb-lease", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void cancelStopsFurtherProgressButKeepsConfirmedVersions() {
        setupSubjectWithTwoVersions("mb-cancel");
        register("mb-cancel", "svc-a", 1, 1, 100);
        register("mb-cancel", "svc-b", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-cancel", 1, 2);
        confirm("mb-cancel", batch.batchNo(), "svc-a", "evt-a");

        MigrationBatchView cancelled = service.cancelMigrationBatch("mb-cancel", batch.batchNo());
        assertThat(cancelled.status()).isEqualTo(MigrationBatchStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
        // 重复取消幂等
        assertThat(service.cancelMigrationBatch("mb-cancel", batch.batchNo()).status())
                .isEqualTo(MigrationBatchStatus.CANCELLED);

        // 已确认的 svc-a 留在 v2，未确认的 svc-b 仍在 v1；源版本不被推进废弃
        assertThat(service.listConsumers("mb-cancel")).filteredOn(c -> c.consumer().equals("svc-a"))
                .singleElement().extracting(c -> c.version()).isEqualTo(2);
        assertThat(service.listConsumers("mb-cancel")).filteredOn(c -> c.consumer().equals("svc-b"))
                .singleElement().extracting(c -> c.version()).isEqualTo(1);
        assertThat(service.getLifecycle("mb-cancel", 1).lifecycle()).isEqualTo(VersionLifecycle.ACTIVE);
        // 扫描不再推进已取消批次
        clock.advanceSeconds(200);
        assertThat(service.scanDeprecations()).isZero();
        assertThat(service.getMigrationBatch("mb-cancel", batch.batchNo()).status())
                .isEqualTo(MigrationBatchStatus.CANCELLED);
    }

    @Test
    void cannotCancelCompletedBatch() {
        setupSubjectWithTwoVersions("mb-cancel-done");
        register("mb-cancel-done", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-cancel-done", 1, 2);
        confirm("mb-cancel-done", batch.batchNo(), "svc-a", "evt-a");

        assertThatThrownBy(() -> service.cancelMigrationBatch("mb-cancel-done", batch.batchNo()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_CLOSED);
    }

    @Test
    void batchHistoryIsImmutableAfterCompletion() {
        setupSubjectWithTwoVersions("mb-immutable");
        register("mb-immutable", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-immutable", 1, 2);
        confirm("mb-immutable", batch.batchNo(), "svc-a", "evt-a");

        // 完成后确认历史仍可查询且不可追加
        MigrationBatchView done = service.getMigrationBatch("mb-immutable", batch.batchNo());
        assertThat(done.confirmations()).hasSize(1);
        assertThatThrownBy(() -> confirm("mb-immutable", batch.batchNo(), "svc-a", "evt-other"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_CLOSED);
    }

    @Test
    void frozenSetOutsiderBlocksSourceDeprecationAfterBatchCompletion() {
        setupSubjectWithTwoVersions("mb-outsider-block");
        register("mb-outsider-block", "svc-a", 1, 1, 100);
        MigrationBatchView batch = createBatch("mb-outsider-block", 1, 2);
        // 冻结集合之外、后来登记到源版本的活跃使用者
        register("mb-outsider-block", "svc-new", 1, 1, 100);

        confirm("mb-outsider-block", batch.batchNo(), "svc-a", "evt-a");
        MigrationBatchView done = service.getMigrationBatch("mb-outsider-block", batch.batchNo());
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        // 源版本已进入废弃流程但被集合外使用者阻断
        assertThat(service.getLifecycle("mb-outsider-block", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);
        assertThat(done.sourceBlockReasons()).anyMatch(r -> r.contains("svc-new"));

        // 外部使用者迁移后，扫描推进废弃
        service.registerConsumer("mb-outsider-block",
                new ConsumerRegistration("svc-new", 2, clock.plusSeconds(200), 2, 1, null));
        assertThat(service.scanDeprecations()).isEqualTo(1);
        assertThat(service.getLifecycle("mb-outsider-block", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void futureDeprecationEffectiveAtKeepsSourceScheduledAfterBatchCompletion() {
        setupSubjectWithTwoVersions("mb-future-eff");
        register("mb-future-eff", "svc-a", 1, 1, 100);
        // 先登记一个未来生效的废弃请求
        service.requestDeprecation("mb-future-eff", 1, clock.plusSeconds(1000), null, null);
        MigrationBatchView batch = createBatch("mb-future-eff", 1, 2);

        confirm("mb-future-eff", batch.batchNo(), "svc-a", "evt-a");
        assertThat(service.getLifecycle("mb-future-eff", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATION_SCHEDULED);

        assertThat(service.scanDeprecations()).isZero();
        clock.advanceSeconds(1001);
        assertThat(service.scanDeprecations()).isEqualTo(1);
        assertThat(service.getLifecycle("mb-future-eff", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
    }

    @Test
    void unknownBatchReturnsNotFound() {
        setupSubjectWithTwoVersions("mb-404");
        assertThatThrownBy(() -> service.getMigrationBatch("mb-404", 99))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCodes.MIGRATION_BATCH_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 并发：迁移确认 / 续租 / 废弃扫描
    // ------------------------------------------------------------------

    @Test
    void concurrentScanAndConfirmationReachesSingleConsistentResult() throws Exception {
        // 扫描线程与确认线程并发：最终批次恰好完成一次，源版本恰好废弃一次，无异常、无活跃源使用者漏掉。
        setupSubjectWithTwoVersions("mb-race-confirm");
        register("mb-race-confirm", "svc-a", 1, 1, 100000);
        MigrationBatchView batch = createBatch("mb-race-confirm", 1, 2);

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
        Future<?> confirmer = pool.submit(() -> {
            try {
                ready.countDown();
                start.await();
                // 相同事件号重复提交必须保持幂等
                for (int i = 0; i < attempts; i++) {
                    confirm("mb-race-confirm", batch.batchNo(), "svc-a", "evt-race");
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
        confirmer.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(errors).isEmpty();

        MigrationBatchView done = service.getMigrationBatch("mb-race-confirm", batch.batchNo());
        assertThat(done.status()).isEqualTo(MigrationBatchStatus.COMPLETED);
        assertThat(done.confirmations()).hasSize(1);
        assertThat(service.getLifecycle("mb-race-confirm", 1).lifecycle())
                .isEqualTo(VersionLifecycle.DEPRECATED);
        assertThat(service.getLifecycle("mb-race-confirm", 1).blockingConsumers()).isEmpty();
    }

    @Test
    void concurrentScanAndLeaseRenewalKeepsActiveSourceUserInBatch() throws Exception {
        // 续租线程持续把租约续到未来：批次不能因扫描而把该成员当作过期剔除，源版本保持 ACTIVE。
        setupSubjectWithTwoVersions("mb-race-renew");
        register("mb-race-renew", "svc-a", 1, 1, 100000);
        MigrationBatchView batch = createBatch("mb-race-renew", 1, 2);

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
                    service.registerConsumer("mb-race-renew",
                            new ConsumerRegistration("svc-a", 1, clock.plusSeconds(100000 + i), i, 1, null));
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

        MigrationBatchView view = service.getMigrationBatch("mb-race-renew", batch.batchNo());
        assertThat(view.status()).isEqualTo(MigrationBatchStatus.IN_PROGRESS);
        assertThat(view.pendingConsumers()).extracting(MemberView::consumer).containsExactly("svc-a");
        assertThat(service.getLifecycle("mb-race-renew", 1).lifecycle()).isEqualTo(VersionLifecycle.ACTIVE);
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
