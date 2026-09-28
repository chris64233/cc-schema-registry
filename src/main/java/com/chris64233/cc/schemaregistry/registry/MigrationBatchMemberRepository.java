package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationBatchMemberRepository extends JpaRepository<MigrationBatchMemberEntity, Long> {

    List<MigrationBatchMemberEntity> findByBatchIdOrderByConsumerAsc(Long batchId);

    Optional<MigrationBatchMemberEntity> findByBatchIdAndConsumer(Long batchId, String consumer);
}
