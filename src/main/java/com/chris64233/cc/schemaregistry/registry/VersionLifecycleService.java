package com.chris64233.cc.schemaregistry.registry;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本废弃、消费者依赖登记与受控删除。
 *
 * 并发一致性：消费者续租/迁移、废弃扫描、删除都在事务内先对主题行加悲观写锁
 * （{@link SubjectRepository#findByNameForUpdate}），因此废弃扫描与消费者续租被串行化，
 * 扫描要么看到续租后的有效租约（保持 DEPRECATING），要么先提交废弃（后续续租被拒绝），
 * 不会出现"扫描废弃了版本而同时心跳又续上了该版本"的中间态。
 */
@Service
public class VersionLifecycleService {

    private final SubjectRepository subjects;
    private final SchemaVersionRepository versions;
    private final ConsumerDependencyRepository consumers;
    private final VersionAuditRepository audits;
    private final Clock clock;

    public VersionLifecycleService(SubjectRepository subjects, SchemaVersionRepository versions,
            ConsumerDependencyRepository consumers, VersionAuditRepository audits, Clock clock) {
        this.subjects = subjects;
        this.versions = versions;
        this.consumers = consumers;
        this.audits = audits;
        this.clock = clock;
    }

    // ---------- 消费者依赖登记 ----------

    /**
     * 登记或更新消费者依赖。更新带版本条件：仅当 updateSeq 严格大于已存序号时才生效；
     * 相等且幂等键匹配时视为重放，直接返回当前状态；否则视为迟到心跳，不覆盖较新的依赖。
     */
    @Transactional
    public ConsumerUpdateResult upsertConsumerDependency(String subjectName, String consumerId, int version,
            long updateSeq, Instant leaseExpiresAt, String idempotencyKey) {
        SubjectEntity subject = subjects.findByNameForUpdate(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        String key = normalizeKey(idempotencyKey);
        String requestHash = requestHash(version, updateSeq, leaseExpiresAt);

        var existing = consumers.findBySubjectIdAndConsumerId(subject.getId(), consumerId);
        if (existing.isPresent()) {
            ConsumerDependencyEntity dep = existing.get();
            if (key != null && key.equals(dep.getLastIdemKey())) {
                if (!requestHash.equals(dep.getLastRequestHash())) {
                    throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                            "idempotency key '" + idempotencyKey + "' was used with a different consumer update");
                }
                return ConsumerUpdateResult.of(dep, false, false);
            }
            if (updateSeq <= dep.getUpdateSeq()) {
                return ConsumerUpdateResult.of(dep, false, true);
            }
            requireUsableVersion(subject, version);
            dep.update(version, updateSeq, leaseExpiresAt, clock.instant(), key, requestHash);
            return ConsumerUpdateResult.of(dep, true, false);
        }

        requireUsableVersion(subject, version);
        ConsumerDependencyEntity dep = consumers.save(new ConsumerDependencyEntity(subject, consumerId, version,
                updateSeq, leaseExpiresAt, clock.instant(), key, requestHash));
        return ConsumerUpdateResult.of(dep, true, false);
    }

    @Transactional(readOnly = true)
    public List<ConsumerDependencyEntity> listConsumerDependencies(String subjectName) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return consumers.findBySubjectIdOrderByConsumerIdAsc(subject.getId());
    }

    // ---------- 废弃 ----------

    /**
     * 登记废弃请求：版本进入 DEPRECATING 并记录生效时间与保留期。废弃请求号幂等：
     * 相同请求号重放相同参数返回当前状态，相同请求号携带不同参数返回 409。
     */
    @Transactional
    public SchemaVersionEntity requestDeprecation(String subjectName, int version, Instant effectiveAt,
            long retentionSeconds, String requestKey) {
        SubjectEntity subject = subjects.findByNameForUpdate(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        SchemaVersionEntity entity = getVersionEntity(subject, version);
        String key = normalizeKey(requestKey);

        if (entity.isContentDeleted()) {
            throw versionDeleted(subjectName, version);
        }
        if (key != null && key.equals(entity.getDeprecationRequestKey())) {
            if (!Objects.equals(effectiveAt, entity.getDeprecateEffectiveAt())
                    || retentionSeconds != entity.getRetentionSeconds()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT,
                        "deprecation request key '" + requestKey + "' was used with different parameters");
            }
            return entity;
        }
        if (entity.getLifecycle() == VersionLifecycle.DEPRECATED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_DEPRECATED,
                    "version " + version + " of subject '" + subjectName + "' is already deprecated");
        }
        entity.setLifecycle(VersionLifecycle.DEPRECATING);
        entity.setDeprecateEffectiveAt(effectiveAt);
        entity.setRetentionSeconds(retentionSeconds);
        entity.setDeprecationRequestKey(key);
        audits.save(new VersionAuditEntity(subject, version, VersionAuditEntity.Event.DEPRECATION_REQUESTED,
                "effectiveAt=" + effectiveAt + ", retentionSeconds=" + retentionSeconds, clock.instant()));
        return entity;
    }

    /**
     * 废弃扫描：把生效时间已到且没有有效消费者依赖（全部迁移或租约过期）的
     * DEPRECATING 版本推进到 DEPRECATED。返回本次完成废弃的版本号。
     */
    @Transactional
    public List<Integer> applyDueDeprecations(String subjectName) {
        SubjectEntity subject = subjects.findByNameForUpdate(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        Instant now = clock.instant();
        List<Integer> transitioned = new ArrayList<>();
        for (SchemaVersionEntity entity : versions.findBySubjectIdAndLifecycle(subject.getId(),
                VersionLifecycle.DEPRECATING)) {
            if (entity.getDeprecateEffectiveAt().isAfter(now)) {
                continue;
            }
            if (hasActiveConsumers(subject.getId(), entity.getVersion(), now)) {
                continue;
            }
            entity.setLifecycle(VersionLifecycle.DEPRECATED);
            entity.setDeprecatedAt(now);
            audits.save(new VersionAuditEntity(subject, entity.getVersion(),
                    VersionAuditEntity.Event.DEPRECATED, null, now));
            transitioned.add(entity.getVersion());
        }
        return transitioned;
    }

    // ---------- 删除 ----------

    /**
     * 受控删除：仅当版本已废弃、保留期届满、没有活跃消费者、且没有更晚版本
     * 的兼容性检查依赖时才允许。删除只清除可变载荷（content），摘要、版本号、
     * 审计记录保留。删除请求号幂等。
     */
    @Transactional
    public DeleteResult deleteVersion(String subjectName, int version, String requestKey) {
        SubjectEntity subject = subjects.findByNameForUpdate(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        SchemaVersionEntity entity = getVersionEntity(subject, version);
        String key = normalizeKey(requestKey);

        if (entity.isContentDeleted()) {
            if (key != null && key.equals(entity.getDeletionRequestKey())) {
                return new DeleteResult(subjectName, version, false, entity.getDeletedAt());
            }
            throw versionDeleted(subjectName, version);
        }
        List<String> reasons = deletionBlockers(subject, entity, clock.instant());
        if (!reasons.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DELETION_NOT_ELIGIBLE,
                    "version " + version + " of subject '" + subjectName + "' is not eligible for deletion: "
                            + String.join(", ", reasons));
        }
        Instant now = clock.instant();
        entity.clearContent();
        entity.setDeletedAt(now);
        entity.setDeletionRequestKey(key);
        audits.save(new VersionAuditEntity(subject, version, VersionAuditEntity.Event.DELETED, null, now));
        return new DeleteResult(subjectName, version, true, now);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public SchemaVersionEntity getLifecycle(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return getVersionEntity(subject, version);
    }

    /** 阻断废弃的消费者：在该版本上持有未过期租约的消费者。 */
    @Transactional(readOnly = true)
    public List<ConsumerDependencyEntity> deprecationBlockers(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        getVersionEntity(subject, version);
        Instant now = clock.instant();
        return consumers.findBySubjectIdAndVersion(subject.getId(), version).stream()
                .filter(dep -> dep.hasActiveLease(now))
                .toList();
    }

    /** 删除资格解释：返回资格标志与所有不满足的原因。 */
    @Transactional(readOnly = true)
    public DeletionEligibility deletionEligibility(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        SchemaVersionEntity entity = getVersionEntity(subject, version);
        if (entity.isContentDeleted()) {
            return new DeletionEligibility(subjectName, version, false, List.of("ALREADY_DELETED"));
        }
        List<String> reasons = deletionBlockers(subject, entity, clock.instant());
        return new DeletionEligibility(subjectName, version, reasons.isEmpty(), reasons);
    }

    @Transactional(readOnly = true)
    public List<VersionAuditEntity> auditTrail(String subjectName, int version) {
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return audits.findBySubjectIdAndVersionOrderByAtAsc(subject.getId(), version);
    }

    // ---------- 内部 ----------

    private List<String> deletionBlockers(SubjectEntity subject, SchemaVersionEntity entity, Instant now) {
        List<String> reasons = new ArrayList<>();
        if (entity.getLifecycle() != VersionLifecycle.DEPRECATED) {
            reasons.add("VERSION_NOT_DEPRECATED");
        }
        if (entity.getDeprecatedAt() != null && entity.getRetentionSeconds() != null
                && now.isBefore(entity.getDeprecatedAt().plusSeconds(entity.getRetentionSeconds()))) {
            reasons.add("RETENTION_PERIOD_NOT_ELAPSED");
        }
        if (hasActiveConsumers(subject.getId(), entity.getVersion(), now)) {
            reasons.add("ACTIVE_CONSUMERS_PRESENT");
        }
        if (versions.countBySubjectIdAndVersionGreaterThanAndDeletedAtIsNull(subject.getId(),
                entity.getVersion()) > 0) {
            reasons.add("COMPATIBILITY_DEPENDENTS_PRESENT");
        }
        return reasons;
    }

    private boolean hasActiveConsumers(Long subjectId, int version, Instant now) {
        return consumers.findBySubjectIdAndVersion(subjectId, version).stream()
                .anyMatch(dep -> dep.hasActiveLease(now));
    }

    private void requireUsableVersion(SubjectEntity subject, int version) {
        SchemaVersionEntity entity = getVersionEntity(subject, version);
        if (entity.isContentDeleted()) {
            throw versionDeleted(subject.getName(), version);
        }
        if (entity.getLifecycle() == VersionLifecycle.DEPRECATED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_DEPRECATED,
                    "version " + version + " of subject '" + subject.getName()
                            + "' is deprecated; consumers must migrate to another version");
        }
    }

    private SchemaVersionEntity getVersionEntity(SubjectEntity subject, int version) {
        return versions.findBySubjectIdAndVersion(subject.getId(), version)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VERSION_NOT_FOUND,
                        "subject '" + subject.getName() + "' has no version " + version));
    }

    private static String requestHash(int version, long updateSeq, Instant leaseExpiresAt) {
        return version + "|" + updateSeq + "|" + leaseExpiresAt;
    }

    private static String normalizeKey(String key) {
        return key == null || key.isBlank() ? null : key;
    }

    private static ApiException subjectNotFound(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SUBJECT_NOT_FOUND,
                "subject '" + name + "' not found");
    }

    private static ApiException versionDeleted(String subjectName, int version) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_DELETED,
                "version " + version + " of subject '" + subjectName + "' has been deleted");
    }

    public record ConsumerUpdateResult(String subject, String consumerId, int version, long updateSeq,
            Instant leaseExpiresAt, boolean applied, boolean stale) {

        static ConsumerUpdateResult of(ConsumerDependencyEntity dep, boolean applied, boolean stale) {
            return new ConsumerUpdateResult(dep.getSubject().getName(), dep.getConsumerId(), dep.getVersion(),
                    dep.getUpdateSeq(), dep.getLeaseExpiresAt(), applied, stale);
        }
    }

    public record DeleteResult(String subject, int version, boolean deleted, Instant deletedAt) {
    }

    public record DeletionEligibility(String subject, int version, boolean eligible, List<String> reasons) {
    }
}
