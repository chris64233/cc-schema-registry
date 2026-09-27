package com.chris64233.cc.schemaregistry.registry;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionAuditRepository extends JpaRepository<VersionAuditEntity, Long> {

    List<VersionAuditEntity> findBySubjectIdAndVersionOrderByAtAsc(Long subjectId, int version);
}
