package com.chris64233.cc.schemaregistry.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.schemaregistry.compat.CompatibilityChecker;
import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.compat.VersionedContract;
import com.chris64233.cc.schemaregistry.contract.ContractParser;
import com.chris64233.cc.schemaregistry.contract.ObjectContract;

/**
 * 消费者迁移批次服务。
 *
 * <p>批次绑定同一主题的源版本与目标版本，创建时在主题悲观写锁内冻结仍在使用源版本的
 * 有效消费者集合，并校验目标版本与每个冻结消费者当前版本满足主题兼容策略——任何一个
 * 消费者不满足都返回具体差异并整体拒绝，不会产生部分可执行的批次。
 *
 * <p>确认迁移以唯一迁移事件完成，必须匹配批次、消费者和目标版本；重复事件幂等，
 * 迟到的旧批次确认不能覆盖消费者后来登记的版本，未冻结的消费者不能借此改变依赖。
 *
 * <p>只有冻结集合全部解决（确认或租约自然过期/自行迁出）后批次才完成，并推动源版本
 * 进入原有废弃流程。迁移确认、消费者续租与废弃扫描都在同一主题悲观写锁内串行化，
 * 因此以当前依赖和批次版本形成唯一结果，不会漏掉仍活跃的源版本使用者。
 */
@Service
public class MigrationBatchService {

    static final String AUDIT_BATCH_COMPLETED_PREFIX = "migration batch ";

    private final SubjectRepository subjects;
    private final SchemaVersionRepository versions;
    private final ConsumerDependencyRepository dependencies;
    private final MigrationBatchRepository batches;
    private final MigrationBatchMemberRepository members;
    private final MigrationConfirmationRepository confirmations;
    private final VersionAuditEventRepository auditEvents;
    private final ContractParser contractParser;
    private final CompatibilityChecker compatibilityChecker;
    private final TimeProvider timeProvider;

    public MigrationBatchService(SubjectRepository subjects, SchemaVersionRepository versions,
            ConsumerDependencyRepository dependencies, MigrationBatchRepository batches,
            MigrationBatchMemberRepository members, MigrationConfirmationRepository confirmations,
            VersionAuditEventRepository auditEvents, ContractParser contractParser,
            CompatibilityChecker compatibilityChecker, TimeProvider timeProvider) {
        this.subjects = subjects;
        this.versions = versions;
        this.dependencies = dependencies;
        this.batches = batches;
        this.members = members;
        this.confirmations = confirmations;
        this.auditEvents = auditEvents;
        this.contractParser = contractParser;
        this.compatibilityChecker = compatibilityChecker;
        this.timeProvider = timeProvider;
    }

    // ------------------------------------------------------------------
    // 创建批次
    // ------------------------------------------------------------------

    /**
     * 创建迁移批次：冻结源版本的全部有效消费者，并校验目标版本对每个冻结消费者
     * 当前版本满足主题兼容策略。任何差异都导致整体拒绝（{@link MigrationIncompatibleException}）。
     */
    @Transactional
    public MigrationBatchView createBatch(String subjectName, int sourceVersion, int targetVersion) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();

        if (sourceVersion == targetVersion) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "source version and target version must differ");
        }
        SchemaVersionEntity source = mustFindVersion(subject, subjectName, sourceVersion);
        SchemaVersionEntity target = mustFindVersion(subject, subjectName, targetVersion);

        if (source.getLifecycle() == VersionLifecycle.DEPRECATED
                || source.getLifecycle() == VersionLifecycle.TOMBSTONE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "source version " + sourceVersion + " is " + source.getLifecycle()
                            + "; a migration batch can only migrate away from a usable version");
        }
        if (target.getLifecycle() != VersionLifecycle.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "target version " + targetVersion + " is " + target.getLifecycle()
                            + " and is not usable as a migration target");
        }
        if (batches.findBySubjectIdAndSourceVersionAndStatus(subject.getId(), sourceVersion,
                MigrationBatchStatus.OPEN).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_BATCH_ALREADY_OPEN,
                    "an open migration batch already exists for source version " + sourceVersion
                            + " of subject '" + subjectName + "'");
        }

        // 冻结：创建时刻仍在使用源版本且租约有效的消费者集合。
        List<ConsumerDependencyEntity> frozen =
                dependencies.findActiveByVersion(subject.getId(), sourceVersion, now);
        if (frozen.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "no active consumers currently use source version " + sourceVersion
                            + "; there is nothing to migrate");
        }

        // 目标版本必须与每个冻结消费者的当前版本满足主题兼容策略；收集全部差异后整体拒绝。
        ObjectContract targetContract = contractParser.parse(target.getContent());
        List<String> failingConsumers = new ArrayList<>();
        List<CompatibilityDiff> diffs = new ArrayList<>();
        for (ConsumerDependencyEntity dep : frozen) {
            SchemaVersionEntity current = versions
                    .findBySubjectIdAndVersion(subject.getId(), dep.getContractVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "frozen consumer '" + dep.getConsumer() + "' references missing version "
                                    + dep.getContractVersion()));
            if (current.getContent() == null) {
                throw new IllegalStateException("frozen consumer '" + dep.getConsumer()
                        + "' references tombstone version " + current.getVersion());
            }
            ObjectContract currentContract = contractParser.parse(current.getContent());
            List<CompatibilityDiff> consumerDiffs = compatibilityChecker.check(
                    subject.getCompatibility(), targetContract,
                    List.of(new VersionedContract(current.getVersion(), currentContract)));
            if (!consumerDiffs.isEmpty()) {
                failingConsumers.add(dep.getConsumer());
                diffs.addAll(consumerDiffs);
            }
        }
        if (!diffs.isEmpty()) {
            throw new MigrationIncompatibleException(failingConsumers,
                    diffs.stream().distinct().sorted().toList());
        }

        MigrationBatchEntity batch = batches.save(
                new MigrationBatchEntity(subject, sourceVersion, targetVersion, frozen.size(), now));
        for (ConsumerDependencyEntity dep : frozen) {
            members.save(new MigrationBatchMemberEntity(batch, dep.getConsumer(), dep.getContractVersion(),
                    dep.getLeaseExpiresAt()));
        }
        return buildView(subject, batch, now);
    }

    // ------------------------------------------------------------------
    // 迁移确认
    // ------------------------------------------------------------------

    /**
     * 以唯一迁移事件确认消费者已切换到目标版本。确认必须匹配批次、消费者和目标版本。
     * 事件号（Idempotency-Key）全局唯一：重放幂等；迟到事件不能覆盖消费者后来登记的版本。
     * 同时把消费者依赖从源版本切换为目标版本并按事件续租、推进批次（全部解决即完成）。
     */
    @Transactional
    public MigrationConfirmationView confirm(String subjectName, long batchId, String consumer,
            MigrationEvent event) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();

        if (consumer == null || consumer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "consumer must not be blank");
        }
        String eventId = normalizeKey(event.eventId());
        if (eventId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "migration event id (Idempotency-Key header) is required");
        }
        if (event.updateSeq() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "updateSeq must not be negative");
        }
        if (event.leaseExpiresAt() == null || !event.leaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "leaseExpiresAt must be in the future");
        }

        // 事件号全局唯一：相同事件重放返回首次结果；同号携带不同参数冲突。
        Optional<MigrationConfirmationEntity> prior = confirmations.findByEventId(eventId);
        if (prior.isPresent()) {
            MigrationConfirmationEntity existing = prior.get();
            if (!sameEvent(existing, batchId, subject.getId(), consumer, event)) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                        "migration event id '" + eventId
                                + "' was already recorded with different parameters");
            }
            return toConfirmationView(existing);
        }

        MigrationBatchEntity batch = mustFindBatch(subject, batchId);
        if (event.targetVersion() != null && event.targetVersion() != batch.getTargetVersion()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "event target version " + event.targetVersion()
                            + " does not match batch target version " + batch.getTargetVersion());
        }
        if (batch.getStatus() != MigrationBatchStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "migration batch " + batchId + " is " + batch.getStatus()
                            + "; migration confirmations are no longer accepted");
        }

        MigrationBatchMemberEntity member = members.findByBatchIdAndConsumer(batchId, consumer)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        ErrorCodes.MIGRATION_NOT_FROZEN_CONSUMER,
                        "consumer '" + consumer + "' is not in the frozen set of batch " + batchId
                                + "; it cannot change dependencies through this batch"));
        if (member.getStatus() == MigrationMemberStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_ALREADY_CONFIRMED,
                    "consumer '" + consumer + "' already confirmed migration in batch " + batchId);
        }

        // 迟到保护：只能把源版本依赖切换为目标版本；消费者已登记到别的版本时不得覆盖。
        ConsumerDependencyEntity dependency =
                dependencies.findBySubjectIdAndConsumer(subject.getId(), consumer)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                ErrorCodes.VERSION_CONDITION_MISMATCH,
                                "consumer '" + consumer
                                        + "' has no dependency record; frozen dependencies cannot vanish"));
        int currentVersion = dependency.getContractVersion();
        if (currentVersion == batch.getSourceVersion()) {
            if (event.updateSeq() <= dependency.getUpdateSeq()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.STALE_UPDATE,
                        "updateSeq " + event.updateSeq() + " is not newer than seen sequence "
                                + dependency.getUpdateSeq() + " for consumer '" + consumer
                                + "'; late migration event cannot overwrite dependency version "
                                + currentVersion);
            }
            dependency.apply(batch.getTargetVersion(), event.leaseExpiresAt(), event.updateSeq(), now);
        } else if (currentVersion == batch.getTargetVersion()) {
            // 消费者此前已自行登记到目标版本：只补记确认事件，不改动其依赖与租约。
        } else {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONDITION_MISMATCH,
                    "consumer '" + consumer + "' has since registered version " + currentVersion
                            + "; late confirmation for batch " + batchId
                            + " (source " + batch.getSourceVersion() + " -> target "
                            + batch.getTargetVersion() + ") cannot overwrite it");
        }

        MigrationConfirmationEntity confirmation = confirmations.save(new MigrationConfirmationEntity(
                batch, eventId, consumer, batch.getSourceVersion(), batch.getTargetVersion(),
                event.updateSeq(), event.leaseExpiresAt(), now));
        member.markConfirmed(now, batch.getTargetVersion());

        progressBatch(subject, batch, now);
        return toConfirmationView(confirmation);
    }

    // ------------------------------------------------------------------
    // 取消
    // ------------------------------------------------------------------

    /**
     * 完成前取消批次：只停止后续迁移推进，不回退已经确认的消费者版本。
     * 已完成的批次不能取消；重复取消幂等。
     */
    @Transactional
    public MigrationBatchView cancel(String subjectName, long batchId, String reason) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        MigrationBatchEntity batch = mustFindBatch(subject, batchId);
        if (batch.getStatus() == MigrationBatchStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "migration batch " + batchId + " is already completed and cannot be cancelled");
        }
        if (batch.getStatus() == MigrationBatchStatus.OPEN) {
            // 取消前先结算一次：若冻结集合恰好已全部解决，则批次照常完成而不是取消。
            if (!progressBatch(subject, batch, now)) {
                batch.markCancelled(now, reason);
            }
        }
        return buildView(subject, batch, now);
    }

    // ------------------------------------------------------------------
    // 扫描推进（由废弃扫描在同一主题锁内调用）
    // ------------------------------------------------------------------

    /**
     * 推进主题下所有 OPEN 批次：按当前依赖结算 PENDING 成员（租约过期/自行迁出），
     * 全部解决即完成批次并推动源版本进入废弃流程。加入调用方已持有的事务与主题锁。
     *
     * @return 本次新完成的批次数
     */
    @Transactional
    public int progressOpenBatches(SubjectEntity subject, Instant now) {
        int completed = 0;
        for (MigrationBatchEntity batch : batches.findBySubjectIdOrderByIdAsc(subject.getId())) {
            if (batch.getStatus() == MigrationBatchStatus.OPEN && progressBatch(subject, batch, now)) {
                completed++;
            }
        }
        return completed;
    }

    /**
     * 结算单个批次。
     *
     * @return 批次在本次调用中是否已全部解决并完成
     */
    private boolean progressBatch(SubjectEntity subject, MigrationBatchEntity batch, Instant now) {
        boolean hasPending = false;
        for (MigrationBatchMemberEntity member : members.findByBatchIdOrderByConsumerAsc(batch.getId())) {
            if (member.getStatus() == MigrationMemberStatus.CONFIRMED) {
                continue;
            }
            Optional<ConsumerDependencyEntity> dep =
                    dependencies.findBySubjectIdAndConsumer(subject.getId(), member.getConsumer());
            if (dep.isEmpty()) {
                member.markResolved(MigrationMemberStatus.LEASE_EXPIRED, now, null);
                continue;
            }
            ConsumerDependencyEntity dependency = dep.get();
            if (dependency.getContractVersion() == batch.getSourceVersion()) {
                if (dependency.getLeaseExpiresAt().isAfter(now)) {
                    hasPending = true;
                } else {
                    // 租约自然过期：记录保留，成员从待迁移集合移除。
                    member.markResolved(MigrationMemberStatus.LEASE_EXPIRED, now,
                            dependency.getContractVersion());
                }
            } else {
                // 未通过本批次确认但已自行迁出（含直接登记到目标版本或其他版本）。
                member.markResolved(MigrationMemberStatus.DETACHED, now,
                        dependency.getContractVersion());
            }
        }

        if (hasPending) {
            return false;
        }
        if (batch.getStatus() != MigrationBatchStatus.OPEN) {
            return false;
        }
        batch.markCompleted(now);
        pushSourceIntoDeprecationFlow(subject, batch.getSourceVersion(), now, batch.getId());
        return true;
    }

    /**
     * 批次完成后推动源版本进入原有废弃流程：若仍为 ACTIVE 则登记待废弃（立即生效、
     * 默认保留期）；待废弃且生效时间已到且无任何有效消费者依赖时推进为已废弃。
     * 仍有活跃使用者（如非冻结消费者、或消费者在冻结后新登记回源版本）时保持待废弃，
     * 由后续扫描继续推进——不会漏掉任何源版本使用者。
     */
    private void pushSourceIntoDeprecationFlow(SubjectEntity subject, int sourceVersion, Instant now,
            long batchId) {
        SchemaVersionEntity source = versions
                .findBySubjectIdAndVersion(subject.getId(), sourceVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "migration batch references missing source version " + sourceVersion));
        if (source.getLifecycle() == VersionLifecycle.ACTIVE) {
            source.setLifecycle(VersionLifecycle.DEPRECATION_SCHEDULED);
            source.setDeprecateEffectiveAt(now);
            source.setRetentionMillis(SchemaRegistryService.DEFAULT_RETENTION_MILLIS);
            auditEvents.save(new VersionAuditEventEntity(subject, sourceVersion,
                    SchemaRegistryService.AUDIT_DEPRECATION_REQUESTED, null,
                    AUDIT_BATCH_COMPLETED_PREFIX + batchId
                            + " completed; deprecation requested automatically", now));
        }
        if (source.getLifecycle() == VersionLifecycle.DEPRECATION_SCHEDULED
                && !now.isBefore(source.getDeprecateEffectiveAt())
                && dependencies.findActiveByVersion(subject.getId(), sourceVersion, now).isEmpty()) {
            source.setLifecycle(VersionLifecycle.DEPRECATED);
            source.setDeprecatedAt(now);
            auditEvents.save(new VersionAuditEventEntity(subject, sourceVersion,
                    SchemaRegistryService.AUDIT_DEPRECATED, null,
                    AUDIT_BATCH_COMPLETED_PREFIX + batchId
                            + " completed: frozen set resolved and no active consumers remain", now));
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MigrationBatchSummary> listBatches(String subjectName) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        Instant now = timeProvider.now();
        return batches.findBySubjectIdOrderByIdAsc(subject.getId()).stream()
                .map(batch -> toSummary(subject, batch, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationBatchView getBatch(String subjectName, long batchId) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return buildView(subject, mustFindBatch(subject, batchId), timeProvider.now());
    }

    private MigrationBatchView buildView(SubjectEntity subject, MigrationBatchEntity batch, Instant now) {
        Map<String, MigrationConfirmationEntity> confirmationByConsumer = new LinkedHashMap<>();
        List<MigrationConfirmationView> confirmationsView = new ArrayList<>();
        for (MigrationConfirmationEntity c : confirmations.findByBatchIdOrderByConfirmedAtAsc(batch.getId())) {
            confirmationByConsumer.put(c.getConsumer(), c);
            confirmationsView.add(toConfirmationView(c));
        }

        List<MigrationMemberView> pending = new ArrayList<>();
        List<MigrationMemberView> leaseExpired = new ArrayList<>();
        List<MigrationMemberView> detached = new ArrayList<>();
        for (MigrationBatchMemberEntity member : members.findByBatchIdOrderByConsumerAsc(batch.getId())) {
            MigrationMemberView view = liveMemberView(subject, batch, member, now,
                    confirmationByConsumer.get(member.getConsumer()));
            switch (view.status()) {
                case PENDING -> pending.add(view);
                case LEASE_EXPIRED -> leaseExpired.add(view);
                case DETACHED -> detached.add(view);
                case CONFIRMED -> {
                    // 已确认项统一从 confirmations 列表展示。
                }
            }
        }

        return new MigrationBatchView(batch.getId(), subject.getName(), batch.getSourceVersion(),
                batch.getTargetVersion(), batch.getStatus(), batch.getCreatedAt(),
                batch.getCompletedAt(), batch.getCancelledAt(), batch.getCancelReason(),
                List.copyOf(pending), List.copyOf(confirmationsView), List.copyOf(leaseExpired),
                List.copyOf(detached),
                sourceDeprecationBlockReasons(subject, batch.getSourceVersion(), now));
    }

    /**
     * 成员的展示状态：已持久化的解析态直接返回；PENDING 成员按当前依赖实时结算
     * （仍有效使用源版本 → PENDING，否则按租约过期/自行迁出展示），但只读不改写。
     */
    private MigrationMemberView liveMemberView(SubjectEntity subject, MigrationBatchEntity batch,
            MigrationBatchMemberEntity member, Instant now, MigrationConfirmationEntity confirmation) {
        MigrationMemberStatus status = member.getStatus();
        Integer observedVersion = member.getObservedVersion();
        Instant resolvedAt = member.getResolvedAt();
        if (status == MigrationMemberStatus.PENDING) {
            Optional<ConsumerDependencyEntity> dep =
                    dependencies.findBySubjectIdAndConsumer(subject.getId(), member.getConsumer());
            if (dep.isEmpty()) {
                status = MigrationMemberStatus.LEASE_EXPIRED;
                resolvedAt = now;
            } else {
                ConsumerDependencyEntity dependency = dep.get();
                observedVersion = dependency.getContractVersion();
                if (dependency.getContractVersion() == batch.getSourceVersion()
                        && dependency.getLeaseExpiresAt().isAfter(now)) {
                    status = MigrationMemberStatus.PENDING;
                } else if (dependency.getContractVersion() == batch.getSourceVersion()) {
                    status = MigrationMemberStatus.LEASE_EXPIRED;
                    resolvedAt = now;
                } else {
                    status = MigrationMemberStatus.DETACHED;
                    resolvedAt = now;
                }
            }
        }
        return new MigrationMemberView(member.getConsumer(), status, member.getFrozenVersion(),
                observedVersion, member.getFrozenLeaseExpiresAt(), resolvedAt,
                confirmation != null ? confirmation.getEventId() : null);
    }

    private MigrationBatchSummary toSummary(SubjectEntity subject, MigrationBatchEntity batch, Instant now) {
        long pendingCount = members.findByBatchIdOrderByConsumerAsc(batch.getId()).stream()
                .filter(m -> liveMemberView(subject, batch, m, now,
                        confirmations.findByBatchIdAndConsumer(batch.getId(), m.getConsumer()).orElse(null))
                        .status() == MigrationMemberStatus.PENDING)
                .count();
        return new MigrationBatchSummary(batch.getId(), subject.getName(), batch.getSourceVersion(),
                batch.getTargetVersion(), batch.getStatus(), batch.getFrozenCount(), (int) pendingCount,
                batch.getCreatedAt(), batch.getCompletedAt(), batch.getCancelledAt());
    }

    /** 源版本当前仍不能进入已废弃状态的原因（空列表表示没有阻断）。 */
    private List<String> sourceDeprecationBlockReasons(SubjectEntity subject, int sourceVersion, Instant now) {
        SchemaVersionEntity source = versions
                .findBySubjectIdAndVersion(subject.getId(), sourceVersion)
                .orElseThrow(() -> new IllegalStateException("source version " + sourceVersion + " missing"));
        List<String> reasons = new ArrayList<>();
        switch (source.getLifecycle()) {
            case ACTIVE -> reasons.add("source version " + sourceVersion
                    + " is ACTIVE; deprecation will be requested automatically when the batch completes");
            case TOMBSTONE -> reasons.add("source version " + sourceVersion + " has been deleted (tombstone)");
            case DEPRECATED -> {
                // 已废弃：无阻断原因。
            }
            case DEPRECATION_SCHEDULED -> {
                if (now.isBefore(source.getDeprecateEffectiveAt())) {
                    reasons.add("deprecation is scheduled but not effective until "
                            + source.getDeprecateEffectiveAt());
                }
                List<String> active = dependencies
                        .findActiveByVersion(subject.getId(), sourceVersion, now).stream()
                        .map(ConsumerDependencyEntity::getConsumer)
                        .sorted().toList();
                if (!active.isEmpty()) {
                    reasons.add("active consumers still using the source version: " + active);
                }
            }
        }
        return List.copyOf(reasons);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private MigrationBatchEntity mustFindBatch(SubjectEntity subject, long batchId) {
        MigrationBatchEntity batch = batches.findById(batchId)
                .orElseThrow(() -> batchNotFound(subject.getName(), batchId));
        if (!batch.getSubject().getId().equals(subject.getId())) {
            throw batchNotFound(subject.getName(), batchId);
        }
        return batch;
    }

    private SchemaVersionEntity mustFindVersion(SubjectEntity subject, String subjectName, int version) {
        return versions.findBySubjectIdAndVersion(subject.getId(), version)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VERSION_NOT_FOUND,
                        "subject '" + subjectName + "' has no version " + version));
    }

    private SubjectEntity lockSubject(String name) {
        return subjects.findByNameForUpdate(name)
                .orElseThrow(() -> subjectNotFound(name));
    }

    private static boolean sameEvent(MigrationConfirmationEntity existing, long batchId, Long subjectId,
            String consumer, MigrationEvent event) {
        return existing.getBatch().getId() == batchId
                && existing.getBatch().getSubject().getId().equals(subjectId)
                && existing.getConsumer().equals(consumer)
                && existing.getUpdateSeq() == event.updateSeq()
                && existing.getLeaseExpiresAt().equals(event.leaseExpiresAt())
                && (event.targetVersion() == null
                        || event.targetVersion() == existing.getToVersion());
    }

    private static MigrationConfirmationView toConfirmationView(MigrationConfirmationEntity c) {
        return new MigrationConfirmationView(c.getEventId(), c.getConsumer(), c.getFromVersion(),
                c.getToVersion(), c.getUpdateSeq(), c.getLeaseExpiresAt(), c.getConfirmedAt());
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    private static ApiException subjectNotFound(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SUBJECT_NOT_FOUND,
                "subject '" + name + "' not found");
    }

    private static ApiException batchNotFound(String subjectName, long batchId) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.MIGRATION_BATCH_NOT_FOUND,
                "subject '" + subjectName + "' has no migration batch " + batchId);
    }

    // ------------------------------------------------------------------
    // 视图记录
    // ------------------------------------------------------------------

    /**
     * @param targetVersion   事件声明的目标版本；可空，非空时必须等于批次目标版本。
     * @param leaseExpiresAt  确认同时续租的新租约到期时间（必须晚于当前时间）。
     */
    public record MigrationEvent(String eventId, Integer targetVersion, Instant leaseExpiresAt,
            long updateSeq) {
    }

    public record MigrationConfirmationView(String eventId, String consumer, int fromVersion, int toVersion,
            long updateSeq, Instant leaseExpiresAt, Instant confirmedAt) {
    }

    public record MigrationMemberView(String consumer, MigrationMemberStatus status, int frozenVersion,
            Integer observedVersion, Instant frozenLeaseExpiresAt, Instant resolvedAt,
            String confirmationEventId) {
    }

    public record MigrationBatchSummary(long batchId, String subject, int sourceVersion, int targetVersion,
            MigrationBatchStatus status, int frozenCount, int pendingCount, Instant createdAt,
            Instant completedAt, Instant cancelledAt) {
    }

    public record MigrationBatchView(long batchId, String subject, int sourceVersion, int targetVersion,
            MigrationBatchStatus status, Instant createdAt, Instant completedAt, Instant cancelledAt,
            String cancelReason,
            List<MigrationMemberView> pendingConsumers,
            List<MigrationConfirmationView> confirmations,
            List<MigrationMemberView> leaseExpiredMembers,
            List<MigrationMemberView> detachedMembers,
            List<String> sourceDeprecationBlockReasons) {
    }
}
