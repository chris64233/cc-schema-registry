package com.chris64233.cc.schemaregistry.registry;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompatReferenceEdgeRepository
        extends JpaRepository<CompatReferenceEdgeEntity, Long> {

    /**
     * 返回仍把目标版本作为兼容性检查依据的版本号集合（边的 from 端）。
     */
    @Query("select e.fromVersion from CompatReferenceEdgeEntity e "
            + "where e.subject.id = :subjectId and e.toVersion = :version")
    List<Integer> findReferencingVersions(@Param("subjectId") Long subjectId,
            @Param("version") int version);

    @Modifying
    @Query("delete from CompatReferenceEdgeEntity e "
            + "where e.subject.id = :subjectId and e.fromVersion = :version")
    void deleteOutgoingEdges(@Param("subjectId") Long subjectId, @Param("version") int version);
}
