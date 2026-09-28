package com.chris64233.cc.schemaregistry.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.schemaregistry.compat.CompatibilityChecker;
import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.compat.VersionedContract;
import com.chris64233.cc.schemaregistry.contract.ContractParser;
import com.chris64233.cc.schemaregistry.contract.ObjectContract;

@Service
public class SchemaRegistryService {

    static final String REQUEST_KIND_DEPRECATE = "DEPRECATE";
    static final String REQUEST_KIND_CONSUMER_UPDATE = "CONSUMER_UPDATE";
    static final String REQUEST_KIND_DELETE = "DELETE";

    static final String AUDIT_DEPRECATION_REQUESTED = "DEPRECATION_REQUESTED";
    static final String AUDIT_DEPRECATED = "DEPRECATED";
    static final String AUDIT_DELETE_REQUESTED = "DELETE_REQUESTED";
    static final String AUDIT_DELETED = "DELETED";
    static final String AUDIT_MIGRATION_BATCH_CREATED = "MIGRATION_BATCH_CREATED";
    static final String AUDIT_MIGRATION_CONFIRMED = "MIGRATION_CONFIRMED";
    static final String AUDIT_MIGRATION_BATCH_COMPLETED = "MIGRATION_BATCH_COMPLETED";
    static final String AUDIT_MIGRATION_BATCH_CANCELLED = "MIGRATION_BATCH_CANCELLED";
    static final String AUDIT_MIGRATION_MEMBER_RESOLVED = "MIGRATION_MEMBER_RESOLVED";

    /** 删除保留期默认值：废弃生效后 30 天。 */
    static final long DEFAULT_RETENTION_MILLIS = Duration.ofDays(30).toMillis();

    private final SubjectRepository subjects;
    private final SchemaVersionRepository versions;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ConsumerDependencyRepository dependencies;
    private final OperationRequestRepository operationRequests;
    private final VersionAuditEventRepository auditEvents;
    private final CompatReferenceEdgeRepository compatEdges;
    private final MigrationBatchRepository migrationBatches;
    private final MigrationBatchMemberRepository migrationMembers;
    private final MigrationConfirmationRepository migrationConfirmations;
    private final ContractParser contractParser;
    private final CompatibilityChecker compatibilityChecker;
    private final TimeProvider timeProvider;

    public SchemaRegistryService(SubjectRepository subjects, SchemaVersionRepository versions,
            IdempotencyRecordRepository idempotencyRecords, ConsumerDependencyRepository dependencies,
            OperationRequestRepository operationRequests, VersionAuditEventRepository auditEvents,
            CompatReferenceEdgeRepository compatEdges, MigrationBatchRepository migrationBatches,
            MigrationBatchMemberRepository migrationMembers,
            MigrationConfirmationRepository migrationConfirmations, ContractParser contractParser,
            CompatibilityChecker compatibilityChecker, TimeProvider timeProvider) {
        this.subjects = subjects;
        this.versions = versions;
        this.idempotencyRecords = idempotencyRecords;
        this.dependencies = dependencies;
        this.operationRequests = operationRequests;
        this.auditEvents = auditEvents;
        this.compatEdges = compatEdges;
        this.migrationBatches = migrationBatches;
        this.migrationMembers = migrationMembers;
        this.migrationConfirmations = migrationConfirmations;
        this.contractParser = contractParser;
        this.compatibilityChecker = compatibilityChecker;
        this.timeProvider = timeProvider;
    }

    @Transactional
    public SubjectEntity createSubject(String name, CompatibilityMode compatibility) {
        if (subjects.existsByName(name)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SUBJECT_EXISTS,
                    "subject '" + name + "' already exists");
        }
        return subjects.save(new SubjectEntity(name, compatibility, timeProvider.now()));
    }

    @Transactional(readOnly = true)
    public List<SubjectEntity> listSubjects() {
        return subjects.findAll();
    }

    @Transactional(readOnly = true)
    public SubjectEntity getSubject(String name) {
        return subjects.findByName(name)
                .orElseThrow(() -> subjectNotFound(name));
    }

    /**
     * 发布契约。对主题行加悲观写锁，保证并发发布串行化：版本号连续唯一，
     * 且每次发布都基于包含更早并发提交的完整历史重新校验。
     */
    @Transactional
    public PublishResult publish(String subjectName, String rawContract, String idempotencyKey) {
        ObjectContract contract = contractParser.parse(rawContract);
        SubjectEntity subject = lockSubject(subjectName);
        String contentHash = contract.contentHash();
        String idemKey = normalizeKey(idempotencyKey);

        if (idemKey != null) {
            var record = idempotencyRecords.findBySubjectIdAndIdemKey(subject.getId(), idemKey);
            if (record.isPresent()) {
                IdempotencyRecordEntity existing = record.get();
                if (!existing.getContentHash().equals(contentHash)) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "idempotency key '" + idemKey + "' was used with a different contract");
                }
                return new PublishResult(subjectName, existing.getVersion(), contentHash, false);
            }
        }

        var identical = versions.findBySubjectIdAndContentHash(subject.getId(), contentHash);
        if (identical.isPresent()) {
            SchemaVersionEntity existing = identical.get();
            if (idemKey != null) {
                idempotencyRecords.save(new IdempotencyRecordEntity(subject, idemKey, contentHash,
                        existing.getVersion(), timeProvider.now()));
            }
            // 相同内容（含已受控删除、仅保留摘要的墓碑）始终映射到同一版本号。
            return new PublishResult(subjectName, existing.getVersion(), contentHash, false);
        }

        List<VersionedContract> history = liveHistoryOf(subject);
        List<CompatibilityDiff> diffs = compatibilityChecker.check(subject.getCompatibility(), contract,
                history);
        if (!diffs.isEmpty()) {
            throw new IncompatibleContractException(diffs);
        }

        int nextVersion = versions.findMaxVersion(subject.getId()) + 1;
        versions.save(new SchemaVersionEntity(subject, nextVersion, contract.canonicalJson(), contentHash,
                timeProvider.now()));
        // 记录兼容性检查依赖：新版本曾针对当时每个存活历史版本做过检查。
        for (VersionedContract base : history) {
            compatEdges.save(new CompatReferenceEdgeEntity(subject, nextVersion, base.version()));
        }
        if (idemKey != null) {
            idempotencyRecords.save(new IdempotencyRecordEntity(subject, idemKey, contentHash, nextVersion,
                    timeProvider.now()));
        }
        return new PublishResult(subjectName, nextVersion, contentHash, true);
    }

    @Transactional(readOnly = true)
    public List<SchemaVersionEntity> listVersions(String subjectName) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return versions.findBySubjectIdOrderByVersionAsc(subject.getId());
    }

    @Transactional(readOnly = true)
    public SchemaVersionEntity getVersion(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return versions.findBySubjectIdAndVersion(subject.getId(), version)
                .orElseThrow(() -> versionNotFound(subjectName, version));
    }

    @Transactional(readOnly = true)
    public List<CompatibilityDiff> checkCompatibility(String subjectName, String rawContract) {
        ObjectContract contract = contractParser.parse(rawContract);
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return compatibilityChecker.check(subject.getCompatibility(), contract, liveHistoryOf(subject));
    }

    // ------------------------------------------------------------------
    // 消费者依赖登记
    // ------------------------------------------------------------------

    /**
     * 登记或更新消费者依赖。同一消费者的更新必须满足：
     * <ul>
     *   <li>更新号严格大于已见更新号，迟到心跳/重放被拒绝（{@code STALE_UPDATE}）；</li>
     *   <li>携带 expectedVersion 时必须与当前登记版本一致（{@code VERSION_CONDITION_MISMATCH}）；</li>
     *   <li>目标版本必须存在且未废弃/删除（{@code LIFECYCLE_CONFLICT}）。</li>
     * </ul>
     * 整个更新在主题悲观写锁内完成，与废弃扫描互斥。requestId 非空时按
     * （主题、消费者、请求号）幂等，重放返回首次结果。
     */
    @Transactional
    public ConsumerResult registerConsumer(String subjectName, ConsumerRegistration registration) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        String requestId = normalizeKey(registration.requestId());

        if (registration.consumer() == null || registration.consumer().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "consumer must not be blank");
        }
        if (registration.leaseExpiresAt() == null || !registration.leaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "leaseExpiresAt must be in the future");
        }
        if (registration.updateSeq() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "updateSeq must not be negative");
        }
        if (requestId != null) {
            var prior = operationRequests.findBySubjectIdAndRequestKindAndRequestIdAndConsumer(
                    subject.getId(), REQUEST_KIND_CONSUMER_UPDATE, requestId, registration.consumer());
            if (prior.isPresent()) {
                OperationRequestEntity existing = prior.get();
                if (!sameConsumerRequest(existing, registration)) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "request id '" + requestId + "' for consumer '" + registration.consumer()
                                    + "' was used with different parameters");
                }
                return new ConsumerResult(subjectName, registration.consumer(),
                        existing.getContractVersion(), existing.getLeaseExpiresAt(),
                        existing.getUpdateSeq(), existing.getCreatedAt(), false);
            }
        }

        SchemaVersionEntity target = mustFindVersion(subject, subjectName, registration.version());
        if (target.getLifecycle() == VersionLifecycle.DEPRECATED
                || target.getLifecycle() == VersionLifecycle.TOMBSTONE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "version " + registration.version() + " is " + target.getLifecycle()
                            + " and cannot receive consumer registrations");
        }

        Optional<ConsumerDependencyEntity> current =
                dependencies.findBySubjectIdAndConsumer(subject.getId(), registration.consumer());
        boolean created;
        ConsumerDependencyEntity entity;
        if (current.isEmpty()) {
            if (registration.expectedVersion() != null
                    && registration.expectedVersion() != registration.version()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONDITION_MISMATCH,
                        "expectedVersion " + registration.expectedVersion()
                                + " does not match first registration version "
                                + registration.version());
            }
            entity = new ConsumerDependencyEntity(subject, registration.consumer(),
                    registration.version(), registration.leaseExpiresAt(), registration.updateSeq(), now);
            dependencies.save(entity);
            created = true;
        } else {
            entity = current.get();
            if (registration.updateSeq() <= entity.getUpdateSeq()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.STALE_UPDATE,
                        "updateSeq " + registration.updateSeq() + " is not newer than seen sequence "
                                + entity.getUpdateSeq() + " for consumer '" + registration.consumer()
                                + "'; late heartbeat cannot overwrite dependency version "
                                + entity.getContractVersion());
            }
            if (registration.expectedVersion() != null
                    && registration.expectedVersion() != entity.getContractVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONDITION_MISMATCH,
                        "expectedVersion " + registration.expectedVersion()
                                + " does not match currently registered version "
                                + entity.getContractVersion());
            }
            entity.apply(registration.version(), registration.leaseExpiresAt(),
                    registration.updateSeq(), now);
            created = false;
        }

        if (requestId != null) {
            operationRequests.save(new OperationRequestEntity(subject, REQUEST_KIND_CONSUMER_UPDATE,
                    requestId, registration.consumer(), registration.version(),
                    registration.leaseExpiresAt(), registration.updateSeq(), null, null, null, null, now));
        }
        return new ConsumerResult(subjectName, entity.getConsumer(), entity.getContractVersion(),
                entity.getLeaseExpiresAt(), entity.getUpdateSeq(), now, created);
    }

    @Transactional(readOnly = true)
    public List<ConsumerView> listConsumers(String subjectName) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        Instant now = timeProvider.now();
        return dependencies.findBySubjectIdOrderByConsumerAsc(subject.getId()).stream()
                .map(d -> new ConsumerView(d.getConsumer(), d.getContractVersion(), d.getLeaseExpiresAt(),
                        d.getUpdateSeq(), d.getUpdatedAt(), d.getLeaseExpiresAt().isAfter(now)))
                .toList();
    }

    // ------------------------------------------------------------------
    // 版本废弃
    // ------------------------------------------------------------------

    /**
     * 请求将版本标记为待废弃并设置生效时间。生效时间到达且不存在有效消费者依赖时，
     * 同事务内立即推进为已废弃；否则保持待废弃，等待 {@link #scanDeprecations()} 推进。
     * 请求号非空时幂等。
     */
    @Transactional
    public DeprecationResult requestDeprecation(String subjectName, int version, Instant effectiveAt,
            Long retentionMillis, String requestId) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        Instant effective = effectiveAt != null ? effectiveAt : now;
        long retention = retentionMillis != null ? retentionMillis : DEFAULT_RETENTION_MILLIS;
        String reqId = normalizeKey(requestId);
        SchemaVersionEntity entity = mustFindVersion(subject, subjectName, version);

        if (reqId != null) {
            var prior = findDeprecationRequest(subject.getId(), reqId);
            if (prior.isPresent()) {
                OperationRequestEntity existing = prior.get();
                if (existing.getContractVersion() != version
                        || !effective.equals(existing.getEffectiveAt())
                        || retention != existing.getRetentionMillis()) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "deprecation request id '" + reqId
                                    + "' was used with different parameters");
                }
                return replayDeprecation(subject, subjectName, entity, existing.getResultEventAt());
            }
        }

        if (entity.getLifecycle() == VersionLifecycle.TOMBSTONE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "version " + version + " has been deleted (tombstone) and cannot be deprecated");
        }
        if (entity.getLifecycle() == VersionLifecycle.DEPRECATED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "version " + version + " is already DEPRECATED");
        }
        if (retention < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "retentionMillis must not be negative");
        }

        Instant deprecatedAt = null;
        if (entity.getLifecycle() == VersionLifecycle.ACTIVE) {
            entity.setLifecycle(VersionLifecycle.DEPRECATION_SCHEDULED);
            entity.setDeprecateEffectiveAt(effective);
            entity.setRetentionMillis(retention);
            auditEvents.save(new VersionAuditEventEntity(subject, version, AUDIT_DEPRECATION_REQUESTED,
                    reqId, "effectiveAt=" + effective + ", retentionMillis=" + retention, now));
        } else {
            // 已待废弃：无请求号的重复调用允许调整生效时间/保留期。
            entity.setDeprecateEffectiveAt(effective);
            entity.setRetentionMillis(retention);
            auditEvents.save(new VersionAuditEventEntity(subject, version, AUDIT_DEPRECATION_REQUESTED,
                    reqId, "rescheduled effectiveAt=" + effective + ", retentionMillis=" + retention,
                    now));
        }

        if (effective.isAfter(now)) {
            if (reqId != null) {
                saveDeprecationRequest(subject, reqId, version, effective, retention, null, now);
            }
            return new DeprecationResult(subjectName, version,
                    VersionLifecycle.DEPRECATION_SCHEDULED, effective, null,
                    activeConsumersOf(subject, version));
        }

        List<ConsumerView> blockers = activeConsumersOf(subject, version);
        if (blockers.isEmpty()) {
            entity.setLifecycle(VersionLifecycle.DEPRECATED);
            entity.setDeprecatedAt(now);
            deprecatedAt = now;
            auditEvents.save(new VersionAuditEventEntity(subject, version, AUDIT_DEPRECATED, reqId,
                    "deprecation became effective with no active consumers", now));
        }
        if (reqId != null) {
            saveDeprecationRequest(subject, reqId, version, effective, retention, deprecatedAt, now);
        }
        return new DeprecationResult(subjectName, version, entity.getLifecycle(), effective, deprecatedAt,
                blockers);
    }

    /**
     * 扫描全部待废弃版本与进行中的迁移批次：
     * <ol>
     *   <li>先按主题推进迁移批次：剔除租约过期的冻结成员，冻结集合全部解决则完成批次并推进源版本；</li>
     *   <li>再推进待废弃版本：生效时间已到且无有效消费者依赖的进入已废弃。</li>
     * </ol>
     * 每个主题按 id 顺序加悲观写锁，与消费者续租、迁移确认互斥，保证并发结果一致：
     * 要么扫描先提交（依赖尚未续租，成员被剔除/版本废弃），要么续租先提交（扫描看到有效依赖）。
     *
     * @return 本次推进为已废弃的版本数量
     */
    @Transactional
    public int scanDeprecations() {
        Instant now = timeProvider.now();
        java.util.TreeSet<Long> subjectIds = new java.util.TreeSet<>();
        subjectIds.addAll(versions.findSubjectIdsWithLifecycle(VersionLifecycle.DEPRECATION_SCHEDULED));
        subjectIds.addAll(migrationBatches.findSubjectIdsWithInProgressBatches());
        int transitions = 0;
        for (Long subjectId : subjectIds) {
            SubjectEntity subject = subjects.findByIdForUpdate(subjectId).orElse(null);
            if (subject == null) {
                continue;
            }
            // 先推进迁移批次，可能在同事务内把源版本推进为已废弃。
            List<MigrationBatchEntity> inProgress = migrationBatches
                    .findBySubjectIdAndStatusOrderByBatchNoAsc(subjectId, MigrationBatchStatus.IN_PROGRESS);
            for (MigrationBatchEntity batch : inProgress) {
                if (tryCompleteBatch(subject, batch, now)) {
                    transitions++;
                }
            }
            List<SchemaVersionEntity> scheduled =
                    versions.findBySubjectIdAndLifecycleOrderByVersionAsc(subjectId,
                            VersionLifecycle.DEPRECATION_SCHEDULED);
            for (SchemaVersionEntity entity : scheduled) {
                if (!now.isBefore(entity.getDeprecateEffectiveAt())
                        && activeConsumersOf(subject, entity.getVersion()).isEmpty()) {
                    entity.setLifecycle(VersionLifecycle.DEPRECATED);
                    entity.setDeprecatedAt(now);
                    auditEvents.save(new VersionAuditEventEntity(subject, entity.getVersion(),
                            AUDIT_DEPRECATED, null,
                            "deprecation scan: effective and no active consumers", now));
                    transitions++;
                }
            }
        }
        return transitions;
    }

    // ------------------------------------------------------------------
    // 消费者迁移批次
    // ------------------------------------------------------------------

    /**
     * 创建迁移批次：绑定同一主题的源版本与目标版本，并冻结当前仍在使用源版本的有效消费者集合。
     * 全部前置条件必须同时满足，任何一条不满足都整体拒绝，不会产生部分可执行的批次：
     * <ul>
     *   <li>源、目标版本均存在，源版本不是墓碑，目标版本可用（未废弃/删除）；</li>
     *   <li>源 ≠ 目标；</li>
     *   <li>同一源版本不存在另一个进行中的批次；</li>
     *   <li>源版本当前至少有一个有效消费者（否则冻结集合为空）；</li>
     *   <li>目标契约与每个冻结消费者当前使用的源版本之间满足主题兼容策略——冻结成员都在源版本上，
     *       即目标契约对源版本通过兼容性检查；不满足时返回确定性差异列表（{@code CONTRACT_INCOMPATIBLE}）。</li>
     * </ul>
     * 整个创建在主题悲观写锁内完成。幂等键非空时按（主题、键）幂等。
     */
    @Transactional
    public MigrationBatchCreation createMigrationBatch(String subjectName, MigrationBatchRequest request) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        String idemKey = normalizeKey(request.idempotencyKey());

        if (request.sourceVersion() == request.targetVersion()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "source version and target version must differ");
        }

        if (idemKey != null) {
            var prior = migrationBatches.findBySubjectIdAndIdempotencyKey(subject.getId(), idemKey);
            if (prior.isPresent()) {
                MigrationBatchEntity existing = prior.get();
                if (existing.getSourceVersion() != request.sourceVersion()
                        || existing.getTargetVersion() != request.targetVersion()) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "migration batch idempotency key '" + idemKey
                                    + "' was used with different source/target versions");
                }
                return new MigrationBatchCreation(toBatchView(existing), false);
            }
        }

        SchemaVersionEntity source = mustFindVersion(subject, subjectName, request.sourceVersion());
        SchemaVersionEntity target = mustFindVersion(subject, subjectName, request.targetVersion());
        if (source.getLifecycle() == VersionLifecycle.TOMBSTONE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "source version " + source.getVersion()
                            + " has been deleted (tombstone) and cannot be migrated away from");
        }
        if (target.getLifecycle() != VersionLifecycle.ACTIVE
                && target.getLifecycle() != VersionLifecycle.DEPRECATION_SCHEDULED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "target version " + target.getVersion() + " is " + target.getLifecycle()
                            + " and is not available as a migration target");
        }
        if (migrationBatches.existsBySubjectIdAndSourceVersionAndStatus(subject.getId(),
                source.getVersion(), MigrationBatchStatus.IN_PROGRESS)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "an in-progress migration batch for source version " + source.getVersion()
                            + " already exists");
        }

        List<ConsumerDependencyEntity> active =
                dependencies.findActiveByVersion(subject.getId(), source.getVersion(), now);
        if (active.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_NO_ACTIVE_CONSUMERS,
                    "version " + source.getVersion()
                            + " has no active consumers; nothing to freeze into a migration batch");
        }

        // 目标版本必须与每个冻结消费者当前使用的版本（即源版本）满足主题兼容策略。
        ObjectContract targetContract = contractParser.parse(target.getContent());
        List<CompatibilityDiff> diffs = compatibilityChecker.check(subject.getCompatibility(), targetContract,
                List.of(new VersionedContract(source.getVersion(), contractParser.parse(source.getContent()))));
        if (!diffs.isEmpty()) {
            throw new IncompatibleContractException(diffs);
        }

        int batchNo = migrationBatches.findMaxBatchNo(subject.getId()) + 1;
        MigrationBatchEntity batch = new MigrationBatchEntity(subject, batchNo, source.getVersion(),
                target.getVersion(), idemKey, now);
        migrationBatches.save(batch);
        // 冻结：对创建时刻仍在使用源版本的有效消费者集合拍快照。
        for (ConsumerDependencyEntity d : active) {
            migrationMembers.save(new MigrationBatchMemberEntity(batch, subject, d.getConsumer(),
                    d.getContractVersion(), d.getLeaseExpiresAt()));
        }
        auditEvents.save(new VersionAuditEventEntity(subject, source.getVersion(),
                AUDIT_MIGRATION_BATCH_CREATED, idemKey,
                "migration batch " + batchNo + ": version " + source.getVersion() + " -> "
                        + target.getVersion() + ", froze " + active.size() + " consumer(s) "
                        + active.stream().map(ConsumerDependencyEntity::getConsumer).sorted().toList(),
                now));
        return new MigrationBatchCreation(toBatchView(batch), true);
    }

    /**
     * 消费者以唯一迁移事件确认已切换到批次目标版本。确认必须：
     * <ul>
     *   <li>携带主题内唯一的事件号，重复事件保持幂等（返回首次确认结果）；</li>
     *   <li>批次存在且仍在进行中；</li>
     *   <li>消费者在批次冻结集合内，且确认的目标版本等于批次目标版本；</li>
     *   <li>消费者当前登记版本必须是源版本：迟到的旧批次确认不能覆盖消费者后来登记的版本；
     *       未列入冻结集合的消费者也不能借此改变依赖关系。</li>
     * </ul>
     * 确认把消费者依赖 compare-and-set 到目标版本（沿用其更新号与租约），并尝试完成批次。
     */
    @Transactional
    public MigrationBatchView confirmMigration(String subjectName, int batchNo,
            MigrationConfirmationRequest request) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        String eventId = requireEventId(request.eventId());

        MigrationBatchEntity batch = mustFindBatch(subject, subjectName, batchNo);

        MigrationConfirmationEntity byEvent =
                migrationConfirmations.findBySubjectIdAndEventId(subject.getId(), eventId).orElse(null);
        if (byEvent != null) {
            if (byEvent.getBatch().getId().equals(batch.getId())
                    && byEvent.getConsumer().equals(request.consumer())
                    && byEvent.getTargetVersion() == batch.getTargetVersion()) {
                return toBatchView(batch);
            }
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                    "migration event id '" + eventId + "' was already used for another confirmation");
        }

        if (batch.getStatus() != MigrationBatchStatus.IN_PROGRESS) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_BATCH_CLOSED,
                    "migration batch " + batchNo + " is " + batch.getStatus()
                            + " and no longer accepts confirmations");
        }
        if (request.targetVersion() != null && request.targetVersion() != batch.getTargetVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_MISMATCH,
                    "confirmation target version " + request.targetVersion()
                            + " does not match batch target version " + batch.getTargetVersion());
        }

        MigrationBatchMemberEntity member = migrationMembers
                .findByBatchIdAndConsumer(batch.getId(), request.consumer())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_MISMATCH,
                        "consumer '" + request.consumer()
                                + "' is not in the frozen set of batch " + batchNo
                                + "; it cannot alter its dependency via this batch"));
        if (member.getStatus() != MigrationMemberStatus.PENDING) {
            // 同一消费者在此批次上已有确认（但事件号不同）：历史不可修改。
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_ALREADY_CONFIRMED,
                    "consumer '" + request.consumer() + "' is already " + member.getStatus()
                            + " in batch " + batchNo);
        }

        ConsumerDependencyEntity dependency = dependencies
                .findBySubjectIdAndConsumer(subject.getId(), request.consumer())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_VERSION_CONFLICT,
                        "consumer '" + request.consumer()
                                + "' has no registered dependency; a batch confirmation cannot create one"));
        if (dependency.getContractVersion() != batch.getSourceVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_VERSION_CONFLICT,
                    "consumer '" + request.consumer() + "' is now registered on version "
                            + dependency.getContractVersion() + ", not batch source version "
                            + batch.getSourceVersion()
                            + "; a late confirmation cannot overwrite the newer dependency");
        }
        SchemaVersionEntity target = mustFindVersion(subject, subjectName, batch.getTargetVersion());
        if (target.getLifecycle() == VersionLifecycle.DEPRECATED
                || target.getLifecycle() == VersionLifecycle.TOMBSTONE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.LIFECYCLE_CONFLICT,
                    "target version " + target.getVersion() + " is " + target.getLifecycle()
                            + " and cannot receive consumer registrations");
        }

        migrationConfirmations.save(new MigrationConfirmationEntity(subject, batch, eventId,
                request.consumer(), batch.getTargetVersion(), now));
        // CAS 到目标版本：沿用更新号与现有租约，因此不会把租约意外延长。
        dependency.apply(batch.getTargetVersion(), dependency.getLeaseExpiresAt(),
                dependency.getUpdateSeq(), now);
        member.resolve(MigrationMemberStatus.CONFIRMED, now);
        auditEvents.save(new VersionAuditEventEntity(subject, batch.getSourceVersion(),
                AUDIT_MIGRATION_CONFIRMED, eventId,
                "batch " + batchNo + ": consumer '" + request.consumer() + "' confirmed switch to version "
                        + batch.getTargetVersion(),
                now));

        tryCompleteBatch(subject, batch, now);
        return toBatchView(mustFindBatch(subject, subjectName, batchNo));
    }

    /**
     * 在批次完成前取消。取消只停止后续迁移推进（此后确认被拒绝），不回退已经确认的消费者版本，
     * 也不改变冻结快照。重复取消（已取消）按幂等返回当前状态；取消已完成批次被拒绝。
     */
    @Transactional
    public MigrationBatchView cancelMigrationBatch(String subjectName, int batchNo) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        MigrationBatchEntity batch = mustFindBatch(subject, subjectName, batchNo);
        if (batch.getStatus() == MigrationBatchStatus.CANCELLED) {
            return toBatchView(batch);
        }
        if (batch.getStatus() == MigrationBatchStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.MIGRATION_BATCH_CLOSED,
                    "migration batch " + batchNo + " is already COMPLETED and cannot be cancelled");
        }
        batch.markCancelled(now);
        auditEvents.save(new VersionAuditEventEntity(subject, batch.getSourceVersion(),
                AUDIT_MIGRATION_BATCH_CANCELLED, null,
                "batch " + batchNo + " cancelled; confirmed consumer versions are not rolled back", now));
        return toBatchView(batch);
    }

    @Transactional(readOnly = true)
    public List<MigrationBatchView> listMigrationBatches(String subjectName) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return migrationBatches.findBySubjectIdOrderByBatchNoAsc(subject.getId()).stream()
                .map(this::toBatchView)
                .toList();
    }

    @Transactional(readOnly = true)
    public MigrationBatchView getMigrationBatch(String subjectName, int batchNo) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return toBatchView(mustFindBatch(subject, subjectName, batchNo));
    }

    /**
     * 尝试完成进行中的批次。进行中时按当前依赖实时判定每个 PENDING 成员：
     * 租约已过期或依赖记录消失 → LEASE_EXPIRED；当前版本不是源版本（自行改登记）→ RELOCATED。
     * 当冻结集合中不再存在源版本的活跃使用者时，批次完成并推进源版本进入原有废弃流程。
     *
     * @return 源版本是否在本次尝试中进入已废弃
     */
    private boolean tryCompleteBatch(SubjectEntity subject, MigrationBatchEntity batch, Instant now) {
        if (batch.getStatus() != MigrationBatchStatus.IN_PROGRESS) {
            return false;
        }
        List<MigrationBatchMemberEntity> members =
                migrationMembers.findByBatchIdOrderByConsumerAsc(batch.getId());
        for (MigrationBatchMemberEntity member : members) {
            if (member.getStatus() != MigrationMemberStatus.PENDING) {
                continue;
            }
            MigrationMemberStatus liveStatus = migrationMemberStatus(member, subject, now);
            if (liveStatus == MigrationMemberStatus.PENDING) {
                continue;
            }
            ConsumerDependencyEntity dependency =
                    dependencies.findBySubjectIdAndConsumer(subject.getId(), member.getConsumer()).orElse(null);
            String detail;
            if (liveStatus == MigrationMemberStatus.LEASE_EXPIRED) {
                detail = "removed from batch " + batch.getBatchNo() + " after lease expiry (lease was "
                        + (dependency != null ? dependency.getLeaseExpiresAt()
                                : member.getFrozenLeaseExpiresAt())
                        + ")";
            } else {
                detail = "left batch " + batch.getBatchNo() + " by re-registering on version "
                        + (dependency != null ? dependency.getContractVersion() : "?");
            }
            member.resolve(liveStatus, now);
            auditEvents.save(new VersionAuditEventEntity(subject, batch.getSourceVersion(),
                    AUDIT_MIGRATION_MEMBER_RESOLVED, null, "consumer '" + member.getConsumer() + "' " + detail,
                    now));
        }

        // 完成判定只看冻结集合的当前真实依赖，不能把仍活跃的源版本使用者漏掉。
        boolean stillActive = members.stream()
                .anyMatch(m -> migrationMemberStatus(m, subject, now) == MigrationMemberStatus.PENDING);
        if (stillActive) {
            return false;
        }
        batch.markCompleted(now);
        auditEvents.save(new VersionAuditEventEntity(subject, batch.getSourceVersion(),
                AUDIT_MIGRATION_BATCH_COMPLETED, null,
                "batch " + batch.getBatchNo() + " completed; all frozen consumers resolved", now));
        return advanceSourceAfterBatch(subject, batch, now);
    }

    /**
     * 批次完成后推动源版本进入原有废弃流程：ACTIVE 源版本立即登记废弃（生效时间为当前时刻）；
     * 只要当前不存在源版本的有效消费者（含冻结集合外后来登记的消费者），就在同事务内推进为已废弃。
     */
    private boolean advanceSourceAfterBatch(SubjectEntity subject, MigrationBatchEntity batch, Instant now) {
        SchemaVersionEntity source =
                versions.findBySubjectIdAndVersion(subject.getId(), batch.getSourceVersion()).orElse(null);
        if (source == null || source.getLifecycle() == VersionLifecycle.TOMBSTONE
                || source.getLifecycle() == VersionLifecycle.DEPRECATED) {
            return false;
        }
        if (source.getLifecycle() == VersionLifecycle.ACTIVE) {
            source.setLifecycle(VersionLifecycle.DEPRECATION_SCHEDULED);
            source.setDeprecateEffectiveAt(now);
            if (source.getRetentionMillis() == null) {
                source.setRetentionMillis(DEFAULT_RETENTION_MILLIS);
            }
            auditEvents.save(new VersionAuditEventEntity(subject, source.getVersion(),
                    AUDIT_DEPRECATION_REQUESTED, null,
                    "auto-scheduled by completion of migration batch " + batch.getBatchNo(), now));
        }
        // 原有废弃流程的门槛仍然适用：生效时间未到或冻结集合外仍有活跃使用者时，保持待废弃，
        // 由废弃扫描在条件满足后推进。
        if (now.isBefore(source.getDeprecateEffectiveAt())) {
            return false;
        }
        List<ConsumerView> blockers = activeConsumersOf(subject, source.getVersion());
        if (!blockers.isEmpty()) {
            return false;
        }
        source.setLifecycle(VersionLifecycle.DEPRECATED);
        source.setDeprecatedAt(now);
        auditEvents.save(new VersionAuditEventEntity(subject, source.getVersion(), AUDIT_DEPRECATED, null,
                "migration batch " + batch.getBatchNo() + " completed with no active consumers", now));
        return true;
    }

    /**
     * 组装批次视图。进行中成员状态按当前依赖实时计算；已完成/已取消成员使用固化状态。
     * 同时给出源版本当前仍不能废弃的原因。
     */
    private MigrationBatchView toBatchView(MigrationBatchEntity batch) {
        Instant now = timeProvider.now();
        Long subjectId = batch.getSubject().getId();
        String subjectName = batch.getSubject().getName();
        List<MigrationBatchMemberEntity> stored =
                migrationMembers.findByBatchIdOrderByConsumerAsc(batch.getId());

        List<MemberView> pending = new ArrayList<>();
        List<MemberView> confirmed = new ArrayList<>();
        List<MemberView> removed = new ArrayList<>();
        for (MigrationBatchMemberEntity m : stored) {
            MigrationMemberStatus status = migrationMemberStatus(m, batch.getSubject(), now);
            ConsumerDependencyEntity d =
                    dependencies.findBySubjectIdAndConsumer(subjectId, m.getConsumer()).orElse(null);
            int currentVersion = d != null ? d.getContractVersion() : m.getFrozenVersion();
            Instant leaseExpiresAt = d != null ? d.getLeaseExpiresAt() : m.getFrozenLeaseExpiresAt();
            MemberView view = new MemberView(m.getConsumer(), m.getFrozenVersion(), currentVersion,
                    leaseExpiresAt, status, m.getResolvedAt());
            switch (status) {
                case CONFIRMED -> confirmed.add(view);
                case PENDING -> pending.add(view);
                default -> removed.add(view);
            }
        }

        List<ConfirmationView> confirmations = confirmationsOf(batch, stored).stream()
                .map(c -> new ConfirmationView(c.getConsumer(), c.getTargetVersion(), c.getEventId(),
                        c.getConfirmedAt()))
                .sorted(java.util.Comparator.comparing(ConfirmationView::consumer))
                .toList();

        SchemaVersionEntity source =
                versions.findBySubjectIdAndVersion(subjectId, batch.getSourceVersion()).orElse(null);
        List<String> sourceBlockReasons =
                source != null ? sourceDeprecationBlockReasons(batch.getSubject(), source, now) : List.of();

        return new MigrationBatchView(subjectName, batch.getBatchNo(), batch.getSourceVersion(),
                batch.getTargetVersion(), batch.getStatus(), batch.getCreatedAt(), batch.getCompletedAt(),
                batch.getCancelledAt(), List.copyOf(pending), List.copyOf(confirmed), List.copyOf(removed),
                confirmations, List.copyOf(sourceBlockReasons));
    }

    /** 确认历史与冻结成员一一对应，按成员（消费者）顺序收集以保持确定性。 */
    private List<MigrationConfirmationEntity> confirmationsOf(MigrationBatchEntity batch,
            List<MigrationBatchMemberEntity> members) {
        List<MigrationConfirmationEntity> result = new ArrayList<>();
        for (MigrationBatchMemberEntity m : members) {
            migrationConfirmations.findByBatchIdAndConsumer(batch.getId(), m.getConsumer())
                    .ifPresent(result::add);
        }
        return result;
    }

    /** 进行中批次的 PENDING 成员按当前依赖实时归类；其它情况返回固化状态。 */
    private MigrationMemberStatus migrationMemberStatus(MigrationBatchMemberEntity member,
            SubjectEntity subject, Instant now) {
        if (member.getStatus() != MigrationMemberStatus.PENDING) {
            return member.getStatus();
        }
        if (member.getBatch().getStatus() != MigrationBatchStatus.IN_PROGRESS) {
            return MigrationMemberStatus.PENDING;
        }
        ConsumerDependencyEntity d =
                dependencies.findBySubjectIdAndConsumer(subject.getId(), member.getConsumer()).orElse(null);
        if (d != null && d.getLeaseExpiresAt().isAfter(now)) {
            return d.getContractVersion() == member.getBatch().getSourceVersion()
                    ? MigrationMemberStatus.PENDING
                    : MigrationMemberStatus.RELOCATED;
        }
        return MigrationMemberStatus.LEASE_EXPIRED;
    }

    /** 源版本当前仍不能进入已废弃的原因（始终按当前依赖与批次状态实时计算）。 */
    private List<String> sourceDeprecationBlockReasons(SubjectEntity subject, SchemaVersionEntity source,
            Instant now) {
        List<String> reasons = new ArrayList<>();
        switch (source.getLifecycle()) {
            case ACTIVE -> reasons.add("source version " + source.getVersion()
                    + " is still ACTIVE; it will be scheduled for deprecation when the batch completes");
            case TOMBSTONE -> reasons.add("source version " + source.getVersion() + " has been deleted");
            case DEPRECATED -> {
                // 已进入已废弃，无阻断原因。
            }
            case DEPRECATION_SCHEDULED -> {
                if (now.isBefore(source.getDeprecateEffectiveAt())) {
                    reasons.add("deprecation not yet effective (effective at "
                            + source.getDeprecateEffectiveAt() + ")");
                }
                List<ConsumerView> active = activeConsumersOf(subject, source.getVersion());
                if (!active.isEmpty()) {
                    reasons.add("active consumers still using version " + source.getVersion() + ": "
                            + active.stream().map(ConsumerView::consumer).sorted().toList());
                }
            }
        }
        return List.copyOf(reasons);
    }

    private MigrationBatchEntity mustFindBatch(SubjectEntity subject, String subjectName, int batchNo) {
        return migrationBatches.findBySubjectIdAndBatchNo(subject.getId(), batchNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.MIGRATION_BATCH_NOT_FOUND,
                        "subject '" + subjectName + "' has no migration batch " + batchNo));
    }

    private static String requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "eventId must not be blank");
        }
        return eventId;
    }

    // ------------------------------------------------------------------
    // 受控删除
    // ------------------------------------------------------------------

    /**
     * 请求受控删除已废弃版本。仅当版本已废弃、无其他版本的兼容性检查依赖、无活跃消费者且
     * 保留期届满时才执行：只移除可变契约载荷，摘要、版本号与审计记录保留；
     * 该版本发出的兼容性检查依赖边一并移除。请求号非空时幂等。
     */
    @Transactional
    public DeleteResult requestDeletion(String subjectName, int version, String requestId) {
        SubjectEntity subject = lockSubject(subjectName);
        Instant now = timeProvider.now();
        String reqId = normalizeKey(requestId);
        SchemaVersionEntity entity = mustFindVersion(subject, subjectName, version);

        if (reqId != null) {
            var prior = operationRequests.findBySubjectIdAndRequestKindAndRequestIdAndConsumer(
                    subject.getId(), REQUEST_KIND_DELETE, reqId, "");
            if (prior.isPresent()) {
                OperationRequestEntity existing = prior.get();
                if (existing.getContractVersion() != version) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "delete request id '" + reqId + "' was used for a different version");
                }
                return new DeleteResult(subjectName, version, true, existing.getResultEventAt(),
                        List.of());
            }
        }

        auditEvents.save(new VersionAuditEventEntity(subject, version, AUDIT_DELETE_REQUESTED, reqId,
                "delete requested at " + now, now));

        DeleteEligibility eligibility = evaluateEligibility(subject, entity, now);
        if (!eligibility.eligible()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DELETE_NOT_ELIGIBLE,
                    "version " + version + " is not eligible for deletion: "
                            + String.join("; ", eligibility.reasons()));
        }

        entity.markTombstone(now);
        compatEdges.deleteOutgoingEdges(subject.getId(), version);
        auditEvents.save(new VersionAuditEventEntity(subject, version, AUDIT_DELETED, reqId,
                "mutable payload removed; digest, version number and audit retained", now));
        if (reqId != null) {
            operationRequests.save(new OperationRequestEntity(subject, REQUEST_KIND_DELETE, reqId, "",
                    version, null, null, null, null, VersionLifecycle.TOMBSTONE, now, now));
        }
        return new DeleteResult(subjectName, version, true, now, List.of());
    }

    // ------------------------------------------------------------------
    // 解释查询
    // ------------------------------------------------------------------

    /** 版本生命周期视图：状态、时间点、阻断废弃的消费者、删除资格与审计记录。 */
    @Transactional(readOnly = true)
    public LifecycleView getLifecycle(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        SchemaVersionEntity entity = mustFindVersion(subject, subjectName, version);
        Instant now = timeProvider.now();
        List<ConsumerView> blockers = activeConsumersOf(subject, version);
        DeleteEligibility eligibility = evaluateEligibility(subject, entity, now);
        List<AuditView> audit = auditEvents
                .findBySubjectIdAndContractVersionOrderByIdAsc(subject.getId(), version).stream()
                .map(e -> new AuditView(e.getEventType(), e.getRequestId(), e.getDetail(), e.getEventTime()))
                .toList();
        return new LifecycleView(subjectName, version, entity.getLifecycle(), entity.getCreatedAt(),
                entity.getDeprecateEffectiveAt(), entity.getDeprecatedAt(), entity.getDeletedAt(),
                entity.getRetentionMillis(), entity.getContentHash(), blockers,
                compatReferenceView(subject, entity), eligibility, audit);
    }

    /** 删除资格解释：无论当前状态如何都返回资格判定与逐条原因。 */
    @Transactional(readOnly = true)
    public DeleteEligibility explainDeletion(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        SchemaVersionEntity entity = mustFindVersion(subject, subjectName, version);
        return evaluateEligibility(subject, entity, timeProvider.now());
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private DeleteEligibility evaluateEligibility(SubjectEntity subject, SchemaVersionEntity entity,
            Instant now) {
        List<String> reasons = new ArrayList<>();
        int version = entity.getVersion();

        if (entity.getLifecycle() != VersionLifecycle.DEPRECATED) {
            reasons.add("version is not DEPRECATED (current lifecycle: " + entity.getLifecycle() + ")");
        }
        List<Integer> referencedBy = compatEdges.findReferencingVersions(subject.getId(), version).stream()
                .distinct().sorted().toList();
        if (!referencedBy.isEmpty()) {
            reasons.add("still referenced by compatibility checks of versions " + referencedBy);
        }
        List<ConsumerView> activeConsumers = activeConsumersOf(subject, version);
        if (!activeConsumers.isEmpty()) {
            reasons.add("active consumers: " + activeConsumers.stream().map(ConsumerView::consumer).toList());
        }
        if (entity.getLifecycle() == VersionLifecycle.DEPRECATED && entity.getDeprecatedAt() != null) {
            long retention = entity.getRetentionMillis() != null
                    ? entity.getRetentionMillis() : DEFAULT_RETENTION_MILLIS;
            Instant eligibleAt = entity.getDeprecatedAt().plusMillis(retention);
            if (now.isBefore(eligibleAt)) {
                reasons.add("retention period not elapsed; eligible at " + eligibleAt + " (in "
                        + Duration.between(now, eligibleAt) + ")");
            }
        }
        return new DeleteEligibility(reasons.isEmpty(), List.copyOf(reasons), referencedBy,
                activeConsumers);
    }

    private CompatReferenceView compatReferenceView(SubjectEntity subject, SchemaVersionEntity entity) {
        return new CompatReferenceView(
                compatEdges.findReferencingVersions(subject.getId(), entity.getVersion()).stream()
                        .distinct().sorted().toList());
    }

    private List<ConsumerView> activeConsumersOf(SubjectEntity subject, int version) {
        return dependencies.findActiveByVersion(subject.getId(), version, timeProvider.now()).stream()
                .map(d -> new ConsumerView(d.getConsumer(), d.getContractVersion(), d.getLeaseExpiresAt(),
                        d.getUpdateSeq(), d.getUpdatedAt(), true))
                .toList();
    }

    /** 存活历史版本（载荷未被受控删除），用于兼容性校验与建边。 */
    private List<VersionedContract> liveHistoryOf(SubjectEntity subject) {
        return versions.findBySubjectIdOrderByVersionAsc(subject.getId()).stream()
                .filter(v -> v.getContent() != null)
                .map(v -> new VersionedContract(v.getVersion(), contractParser.parse(v.getContent())))
                .toList();
    }

    private SubjectEntity lockSubject(String name) {
        return subjects.findByNameForUpdate(name)
                .orElseThrow(() -> subjectNotFound(name));
    }

    private SchemaVersionEntity mustFindVersion(SubjectEntity subject, String subjectName, int version) {
        return versions.findBySubjectIdAndVersion(subject.getId(), version)
                .orElseThrow(() -> versionNotFound(subjectName, version));
    }

    private Optional<OperationRequestEntity> findDeprecationRequest(Long subjectId, String requestId) {
        return operationRequests
                .findBySubjectIdAndRequestKindAndRequestIdAndConsumer(subjectId,
                        REQUEST_KIND_DEPRECATE, requestId, "");
    }

    private void saveDeprecationRequest(SubjectEntity subject, String requestId, int version,
            Instant effectiveAt, long retentionMillis, Instant deprecatedAt, Instant now) {
        operationRequests.save(new OperationRequestEntity(subject, REQUEST_KIND_DEPRECATE, requestId, "",
                version, null, null, effectiveAt, retentionMillis,
                deprecatedAt != null ? VersionLifecycle.DEPRECATED
                        : VersionLifecycle.DEPRECATION_SCHEDULED,
                deprecatedAt, now));
    }

    private DeprecationResult replayDeprecation(SubjectEntity subject, String subjectName,
            SchemaVersionEntity entity, Instant recordedDeprecatedAt) {
        int version = entity.getVersion();
        // 重放时反映当前真实状态（可能已被扫描推进），阻断消费者按当前时刻计算。
        List<ConsumerView> blockers = entity.getLifecycle() == VersionLifecycle.DEPRECATED
                ? List.of()
                : activeConsumersOf(subject, version);
        return new DeprecationResult(subjectName, version, entity.getLifecycle(),
                entity.getDeprecateEffectiveAt(),
                entity.getDeprecatedAt() != null ? entity.getDeprecatedAt() : recordedDeprecatedAt,
                blockers);
    }

    private static boolean sameConsumerRequest(OperationRequestEntity recorded,
            ConsumerRegistration registration) {
        return recorded.getContractVersion() != null
                && recorded.getContractVersion() == registration.version()
                && recorded.getLeaseExpiresAt().equals(registration.leaseExpiresAt())
                && recorded.getUpdateSeq() != null
                && recorded.getUpdateSeq() == registration.updateSeq();
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    private static ApiException subjectNotFound(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SUBJECT_NOT_FOUND,
                "subject '" + name + "' not found");
    }

    private static ApiException versionNotFound(String subjectName, int version) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VERSION_NOT_FOUND,
                "subject '" + subjectName + "' has no version " + version);
    }

    public record PublishResult(String subject, int version, String contentHash, boolean created) {
    }

    /**
     * @param expectedVersion 版本条件：非空时必须与该消费者当前登记版本一致；首次登记时须为空或等于 version。
     */
    public record ConsumerRegistration(String consumer, int version, Instant leaseExpiresAt, long updateSeq,
            Integer expectedVersion, String requestId) {
    }

    public record ConsumerResult(String subject, String consumer, int version, Instant leaseExpiresAt,
            long updateSeq, Instant updatedAt, boolean created) {
    }

    public record ConsumerView(String consumer, int version, Instant leaseExpiresAt, long updateSeq,
            Instant updatedAt, boolean active) {
    }

    public record DeprecationResult(String subject, int version, VersionLifecycle lifecycle,
            Instant effectiveAt, Instant deprecatedAt, List<ConsumerView> blockingConsumers) {
    }

    public record DeleteResult(String subject, int version, boolean deleted, Instant deletedAt,
            List<String> reasons) {
    }

    public record DeleteEligibility(boolean eligible, List<String> reasons,
            List<Integer> referencedByVersions, List<ConsumerView> activeConsumers) {
    }

    public record CompatReferenceView(List<Integer> referencedByVersions) {
    }

    public record AuditView(String eventType, String requestId, String detail, Instant eventTime) {
    }

    public record LifecycleView(String subject, int version, VersionLifecycle lifecycle, Instant createdAt,
            Instant deprecateEffectiveAt, Instant deprecatedAt, Instant deletedAt, Long retentionMillis,
            String contentHash, List<ConsumerView> blockingConsumers,
            CompatReferenceView compatibilityReferences, DeleteEligibility deleteEligibility,
            List<AuditView> audit) {
    }

    public record MigrationBatchRequest(int sourceVersion, int targetVersion, String idempotencyKey) {
    }

    public record MigrationBatchCreation(MigrationBatchView batch, boolean created) {
    }

    public record MigrationConfirmationRequest(String consumer, String eventId, Integer targetVersion) {
    }

    /**
     * @param status            成员状态：PENDING / CONFIRMED / LEASE_EXPIRED / RELOCATED
     * @param currentVersion    消费者当前登记版本（记录消失时回退为冻结版本）
     * @param leaseExpiresAt    当前租约到期时间（记录消失时回退为冻结租约）
     * @param resolvedAt        状态固化时间；进行中实时判定的状态为 null
     */
    public record MemberView(String consumer, int frozenVersion, int currentVersion,
            Instant leaseExpiresAt, MigrationMemberStatus status, Instant resolvedAt) {
    }

    public record ConfirmationView(String consumer, int targetVersion, String eventId,
            Instant confirmedAt) {
    }

    /**
     * 迁移批次视图：
     *
     * @param pendingConsumers    仍待迁移的冻结消费者
     * @param confirmedConsumers  已确认项（与 {@code confirmations} 对应）
     * @param removedConsumers    因租约过期移除或自行改登记的项
     * @param confirmations       只追加、不可修改的确认历史
     * @param sourceBlockReasons  源版本当前仍不能废弃的原因（为空表示无阻断）
     */
    public record MigrationBatchView(String subject, int batchNo, int sourceVersion, int targetVersion,
            MigrationBatchStatus status, Instant createdAt, Instant completedAt, Instant cancelledAt,
            List<MemberView> pendingConsumers, List<MemberView> confirmedConsumers,
            List<MemberView> removedConsumers, List<ConfirmationView> confirmations,
            List<String> sourceBlockReasons) {
    }
}
