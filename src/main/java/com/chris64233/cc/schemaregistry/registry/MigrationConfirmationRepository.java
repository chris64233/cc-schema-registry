package com.chris64233.cc.schemaregistry.registry;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationConfirmationRepository extends JpaRepository<MigrationConfirmationEntity, Long> {

    Optional<MigrationConfirmationEntity> findBySubjectIdAndEventId(Long subjectId, String eventId);

    Optional<MigrationConfirmationEntity> findByBatchIdAndConsumer(Long batchId, String consumer);
}
