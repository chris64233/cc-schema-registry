package com.chris64233.cc.schemaregistry.service;

import com.chris64233.cc.schemaregistry.contract.CompatibilityChecker;
import com.chris64233.cc.schemaregistry.contract.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.contract.ContractParser;
import com.chris64233.cc.schemaregistry.contract.ObjectContract;
import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import com.chris64233.cc.schemaregistry.domain.SchemaVersionEntity;
import com.chris64233.cc.schemaregistry.domain.SubjectEntity;
import com.chris64233.cc.schemaregistry.repository.SchemaVersionRepository;
import com.chris64233.cc.schemaregistry.repository.SubjectRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchemaRegistryService {

    private final SubjectRepository subjectRepository;
    private final SchemaVersionRepository versionRepository;
    private final ContractParser contractParser;
    private final CompatibilityChecker compatibilityChecker;

    public SchemaRegistryService(SubjectRepository subjectRepository,
            SchemaVersionRepository versionRepository,
            ContractParser contractParser,
            CompatibilityChecker compatibilityChecker) {
        this.subjectRepository = subjectRepository;
        this.versionRepository = versionRepository;
        this.contractParser = contractParser;
        this.compatibilityChecker = compatibilityChecker;
    }

    public record SubjectView(String name, CompatibilityMode compatibilityMode,
            java.time.Instant createdAt, long versionCount) {
    }

    public record VersionView(int version, String contentHash, String canonicalContent,
            String idempotencyKey, java.time.Instant createdAt) {
    }

    public record PublishResult(VersionView version, boolean created) {
    }

    public record CompatibilityReport(String subject, CompatibilityMode mode, boolean compatible,
            List<Integer> checkedVersions, List<CompatibilityDiff> diffs) {
    }

    @Transactional
    public SubjectView createSubject(String name, CompatibilityMode mode) {
        if (subjectRepository.existsByName(name)) {
            throw new ApiException(ErrorCode.SUBJECT_ALREADY_EXISTS,
                    "subject already exists: " + name);
        }
        SubjectEntity saved = subjectRepository.save(new SubjectEntity(name, mode));
        return toSubjectView(saved, 0);
    }

    @Transactional(readOnly = true)
    public List<SubjectView> listSubjects() {
        return subjectRepository.findAll().stream()
                .map(subject -> toSubjectView(subject, versionRepository.countBySubjectId(subject.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubjectView getSubject(String name) {
        SubjectEntity subject = findSubject(name);
        return toSubjectView(subject, versionRepository.countBySubjectId(subject.getId()));
    }

    /**
     * 发布契约版本。整个流程在主题行悲观写锁内执行：
     * 幂等判定 → 全历史兼容性校验 → 分配连续版本号并写入，
     * 并发发布因此被串行化，每个版本都基于包含更早并发提交的完整历史校验。
     */
    @Transactional
    public PublishResult publish(String subjectName, String idempotencyKey, String contractJson) {
        SubjectEntity subject = subjectRepository.findByNameForUpdate(subjectName)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBJECT_NOT_FOUND,
                        "subject not found: " + subjectName));
        ObjectContract contract = contractParser.parse(contractJson);
        String key = normalizeIdempotencyKey(idempotencyKey);

        if (key != null) {
            var byKey = versionRepository.findBySubjectIdAndIdempotencyKey(subject.getId(), key);
            if (byKey.isPresent()) {
                SchemaVersionEntity existing = byKey.get();
                if (!existing.getContentHash().equals(contract.contentHash())) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                            "idempotency key '" + key + "' was already used with a different contract");
                }
                return new PublishResult(toVersionView(existing), false);
            }
        }

        var byHash = versionRepository.findBySubjectIdAndContentHash(subject.getId(), contract.contentHash());
        if (byHash.isPresent()) {
            return new PublishResult(toVersionView(byHash.get()), false);
        }

        List<SchemaVersionEntity> history = versionRepository.findBySubjectIdOrderByVersionAsc(subject.getId());
        List<CompatibilityDiff> diffs = compatibilityChecker.check(
                contract, toVersionedContracts(history), subject.getCompatibilityMode());
        if (!diffs.isEmpty()) {
            throw new IncompatibleContractException(diffs);
        }

        int nextVersion = history.isEmpty() ? 1 : history.getLast().getVersion() + 1;
        SchemaVersionEntity saved = versionRepository.save(new SchemaVersionEntity(
                subject, nextVersion, contract.canonicalJson(), contract.contentHash(), key));
        return new PublishResult(toVersionView(saved), true);
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(String subjectName) {
        SubjectEntity subject = findSubject(subjectName);
        return versionRepository.findBySubjectIdOrderByVersionAsc(subject.getId()).stream()
                .map(this::toVersionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionView getVersion(String subjectName, int version) {
        SubjectEntity subject = findSubject(subjectName);
        return versionRepository.findBySubjectIdAndVersion(subject.getId(), version)
                .map(this::toVersionView)
                .orElseThrow(() -> new ApiException(ErrorCode.VERSION_NOT_FOUND,
                        "version " + version + " not found for subject: " + subjectName));
    }

    /**
     * 兼容性差异查询（dry-run）：校验候选契约与全部历史版本，不写入任何记录。
     */
    @Transactional(readOnly = true)
    public CompatibilityReport checkCompatibility(String subjectName, String contractJson) {
        SubjectEntity subject = findSubject(subjectName);
        ObjectContract candidate = contractParser.parse(contractJson);
        List<SchemaVersionEntity> history = versionRepository.findBySubjectIdOrderByVersionAsc(subject.getId());
        List<CompatibilityDiff> diffs = compatibilityChecker.check(
                candidate, toVersionedContracts(history), subject.getCompatibilityMode());
        return new CompatibilityReport(subject.getName(), subject.getCompatibilityMode(),
                diffs.isEmpty(),
                history.stream().map(SchemaVersionEntity::getVersion).toList(),
                diffs);
    }

    private SubjectEntity findSubject(String name) {
        return subjectRepository.findByName(name)
                .orElseThrow(() -> new ApiException(ErrorCode.SUBJECT_NOT_FOUND,
                        "subject not found: " + name));
    }

    private List<CompatibilityChecker.VersionedContract> toVersionedContracts(List<SchemaVersionEntity> history) {
        return history.stream()
                .map(entity -> new CompatibilityChecker.VersionedContract(
                        entity.getVersion(), contractParser.parse(entity.getCanonicalContent())))
                .toList();
    }

    private SubjectView toSubjectView(SubjectEntity subject, long versionCount) {
        return new SubjectView(subject.getName(), subject.getCompatibilityMode(),
                subject.getCreatedAt(), versionCount);
    }

    private VersionView toVersionView(SchemaVersionEntity entity) {
        return new VersionView(entity.getVersion(), entity.getContentHash(),
                entity.getCanonicalContent(), entity.getIdempotencyKey(), entity.getCreatedAt());
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "idempotency key must be at most 128 characters");
        }
        return key;
    }
}
