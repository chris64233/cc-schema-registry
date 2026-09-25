package com.chris64233.cc.schemaregistry.registry;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findBySubjectIdAndIdemKey(Long subjectId, String idemKey);
}
