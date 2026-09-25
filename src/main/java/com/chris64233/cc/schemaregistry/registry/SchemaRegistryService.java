package com.chris64233.cc.schemaregistry.registry;

import java.time.Instant;
import java.util.List;

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

    private final SubjectRepository subjects;
    private final SchemaVersionRepository versions;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ContractParser contractParser;
    private final CompatibilityChecker compatibilityChecker;

    public SchemaRegistryService(SubjectRepository subjects, SchemaVersionRepository versions,
            IdempotencyRecordRepository idempotencyRecords, ContractParser contractParser,
            CompatibilityChecker compatibilityChecker) {
        this.subjects = subjects;
        this.versions = versions;
        this.idempotencyRecords = idempotencyRecords;
        this.contractParser = contractParser;
        this.compatibilityChecker = compatibilityChecker;
    }

    @Transactional
    public SubjectEntity createSubject(String name, CompatibilityMode compatibility) {
        if (subjects.existsByName(name)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SUBJECT_EXISTS,
                    "subject '" + name + "' already exists");
        }
        return subjects.save(new SubjectEntity(name, compatibility, Instant.now()));
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
        SubjectEntity subject = subjects.findByNameForUpdate(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
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
                        existing.getVersion(), Instant.now()));
            }
            return new PublishResult(subjectName, existing.getVersion(), contentHash, false);
        }

        List<CompatibilityDiff> diffs = compatibilityChecker.check(subject.getCompatibility(), contract,
                historyOf(subject));
        if (!diffs.isEmpty()) {
            throw new IncompatibleContractException(diffs);
        }

        int nextVersion = versions.findMaxVersion(subject.getId()) + 1;
        versions.save(new SchemaVersionEntity(subject, nextVersion, contract.canonicalJson(), contentHash,
                Instant.now()));
        if (idemKey != null) {
            idempotencyRecords.save(new IdempotencyRecordEntity(subject, idemKey, contentHash, nextVersion,
                    Instant.now()));
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VERSION_NOT_FOUND,
                        "subject '" + subjectName + "' has no version " + version));
    }

    @Transactional(readOnly = true)
    public List<CompatibilityDiff> checkCompatibility(String subjectName, String rawContract) {
        ObjectContract contract = contractParser.parse(rawContract);
        SubjectEntity subject = subjects.findByName(subjectName)
                .orElseThrow(() -> subjectNotFound(subjectName));
        return compatibilityChecker.check(subject.getCompatibility(), contract, historyOf(subject));
    }

    private List<VersionedContract> historyOf(SubjectEntity subject) {
        return versions.findBySubjectIdOrderByVersionAsc(subject.getId()).stream()
                .map(v -> new VersionedContract(v.getVersion(), contractParser.parse(v.getContent())))
                .toList();
    }

    private static String normalizeKey(String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    }

    private static ApiException subjectNotFound(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SUBJECT_NOT_FOUND,
                "subject '" + name + "' not found");
    }

    public record PublishResult(String subject, int version, String contentHash, boolean created) {
    }
}
