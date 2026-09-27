package com.chris64233.cc.schemaregistry.registry;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionAuditEventRepository extends JpaRepository<VersionAuditEventEntity, Long> {

    List<VersionAuditEventEntity> findBySubjectIdAndContractVersionOrderByIdAsc(
            Long subjectId, int contractVersion);
}
