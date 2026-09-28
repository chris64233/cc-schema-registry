package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationBatchRepository extends JpaRepository<MigrationBatchEntity, Long> {

    Optional<MigrationBatchEntity> findBySubjectIdAndBatchNo(Long subjectId, int batchNo);

    List<MigrationBatchEntity> findBySubjectIdOrderByBatchNoAsc(Long subjectId);

    List<MigrationBatchEntity> findBySubjectIdAndStatusOrderByBatchNoAsc(Long subjectId,
            MigrationBatchStatus status);

    /** 主题内是否存在指向源版本的进行中批次（同一源版本同时只允许一个进行中批次）。 */
    boolean existsBySubjectIdAndSourceVersionAndStatus(Long subjectId, int sourceVersion,
            MigrationBatchStatus status);

    Optional<MigrationBatchEntity> findBySubjectIdAndIdempotencyKey(Long subjectId, String idempotencyKey);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(max(b.batchNo), 0) from MigrationBatchEntity b where b.subject.id = :subjectId")
    int findMaxBatchNo(@org.springframework.data.repository.query.Param("subjectId") Long subjectId);

    @org.springframework.data.jpa.repository.Query(
            "select distinct b.subject.id from MigrationBatchEntity b where b.status = "
                    + "com.chris64233.cc.schemaregistry.registry.MigrationBatchStatus.IN_PROGRESS")
    List<Long> findSubjectIdsWithInProgressBatches();
}
