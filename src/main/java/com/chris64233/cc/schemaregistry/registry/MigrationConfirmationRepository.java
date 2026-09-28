package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationConfirmationRepository extends JpaRepository<MigrationConfirmationEntity, Long> {

    Optional<MigrationConfirmationEntity> findByEventId(String eventId);

    Optional<MigrationConfirmationEntity> findByBatchIdAndConsumer(Long batchId, String consumer);

    List<MigrationConfirmationEntity> findByBatchIdOrderByConfirmedAtAsc(Long batchId);
}
