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
    private final MigrationBatchService migrationBatchService;
    private final ContractParser contractParser;
    private final CompatibilityChecker compatibilityChecker;
    private final TimeProvider timeProvider;

    public SchemaRegistryService(SubjectRepository subjects, SchemaVersionRepository versions,
            IdempotencyRecordRepository idempotencyRecords, ConsumerDependencyRepository dependencies,
            OperationRequestRepository operationRequests, VersionAuditEventRepository auditEvents,
            CompatReferenceEdgeRepository compatEdges, MigrationBatchRepository migrationBatches,
            MigrationBatchService migrationBatchService, ContractParser contractParser,
            CompatibilityChecker compatibilityChecker, TimeProvider timeProvider) {
        this.subjects = subjects;
        this.versions = versions;
        this.idempotencyRecords = idempotencyRecords;
        this.dependencies = dependencies;
        this.operationRequests = operationRequests;
        this.auditEvents = auditEvents;
        this.compatEdges = compatEdges;
        this.migrationBatches = migrationBatches;
        this.migrationBatchService = migrationBatchService;
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
     * 扫描全部待废弃版本与未完成迁移批次：先结算各主题 OPEN 批次（冻结成员租约过期/迁出
     * 即完成批次并推动源版本进入废弃流程），再把生效时间已到且无有效消费者依赖的待废弃
     * 版本推进为已废弃。每个主题按 id 顺序加悲观写锁，与消费者续租、迁移确认互斥，
     * 保证并发结果一致：要么扫描先提交（依赖尚未续租/确认，成功结算），要么对方先提交
     * （扫描看到有效依赖，成员保持待迁移、版本保持待废弃），不会漏掉仍活跃的源版本使用者。
     *
     * @return 本次完成的批次数与推进为已废弃的版本数
     */
    @Transactional
    public ScanResult scanDeprecations() {
        Instant now = timeProvider.now();
        java.util.Set<Long> subjectIds = new java.util.LinkedHashSet<>(
                versions.findSubjectIdsWithLifecycle(VersionLifecycle.DEPRECATION_SCHEDULED));
        subjectIds.addAll(migrationBatches.findSubjectIdsWithStatus(MigrationBatchStatus.OPEN));
        int transitions = 0;
        int completedBatches = 0;
        for (Long subjectId : subjectIds) {
            SubjectEntity subject = subjects.findByIdForUpdate(subjectId).orElse(null);
            if (subject == null) {
                continue;
            }
            completedBatches += migrationBatchService.progressOpenBatches(subject, now);
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
        return new ScanResult(transitions, completedBatches);
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

    public record ScanResult(int deprecatedVersions, int completedBatches) {
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
}
