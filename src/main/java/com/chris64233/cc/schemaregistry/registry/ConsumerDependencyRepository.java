package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerDependencyRepository extends JpaRepository<ConsumerDependencyEntity, Long> {

    Optional<ConsumerDependencyEntity> findBySubjectIdAndConsumerId(Long subjectId, String consumerId);

    List<ConsumerDependencyEntity> findBySubjectIdOrderByConsumerIdAsc(Long subjectId);

    List<ConsumerDependencyEntity> findBySubjectIdAndVersion(Long subjectId, int version);
}
