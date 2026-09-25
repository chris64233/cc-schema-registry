package com.chris64233.cc.schemaregistry.repository;

import com.chris64233.cc.schemaregistry.domain.SchemaVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemaVersionRepository extends JpaRepository<SchemaVersionEntity, Long> {

    List<SchemaVersionEntity> findBySubjectIdOrderByVersionAsc(Long subjectId);

    Optional<SchemaVersionEntity> findBySubjectIdAndVersion(Long subjectId, int version);

    Optional<SchemaVersionEntity> findBySubjectIdAndContentHash(Long subjectId, String contentHash);

    Optional<SchemaVersionEntity> findBySubjectIdAndIdempotencyKey(Long subjectId, String idempotencyKey);

    long countBySubjectId(Long subjectId);
}
