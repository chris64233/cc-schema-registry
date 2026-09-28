package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatchEntity, Long> {

    List<MigrationBatchEntity> findBySubjectIdOrderByIdAsc(Long subjectId);

    Optional<MigrationBatchEntity> findBySubjectIdAndSourceVersionAndStatus(Long subjectId,
            int sourceVersion, MigrationBatchStatus status);

    @Query("select distinct b.subject.id from MigrationBatchEntity b where b.status = :status")
    List<Long> findSubjectIdsWithStatus(@Param("status") MigrationBatchStatus status);
}
