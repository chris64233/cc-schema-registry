package com.chris64233.cc.schemaregistry.registry;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationRequestRepository extends JpaRepository<OperationRequestEntity, Long> {

    Optional<OperationRequestEntity> findBySubjectIdAndRequestKindAndRequestIdAndConsumer(
            Long subjectId, String requestKind, String requestId, String consumer);
}
