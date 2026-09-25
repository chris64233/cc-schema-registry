package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SchemaVersionRepository extends JpaRepository<SchemaVersionEntity, Long> {

    List<SchemaVersionEntity> findBySubjectIdOrderByVersionAsc(Long subjectId);

    Optional<SchemaVersionEntity> findBySubjectIdAndVersion(Long subjectId, int version);

    Optional<SchemaVersionEntity> findBySubjectIdAndContentHash(Long subjectId, String contentHash);

    long countBySubjectId(Long subjectId);

    @Query("select coalesce(max(v.version), 0) from SchemaVersionEntity v where v.subject.id = :subjectId")
    int findMaxVersion(@Param("subjectId") Long subjectId);
}
