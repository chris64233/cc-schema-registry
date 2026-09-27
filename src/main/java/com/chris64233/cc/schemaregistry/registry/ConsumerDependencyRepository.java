package com.chris64233.cc.schemaregistry.registry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumerDependencyRepository extends JpaRepository<ConsumerDependencyEntity, Long> {

    Optional<ConsumerDependencyEntity> findBySubjectIdAndConsumer(Long subjectId, String consumer);

    List<ConsumerDependencyEntity> findBySubjectIdOrderByConsumerAsc(Long subjectId);

    /**
     * 查询某主题下指向指定版本且租约未过期的有效依赖。查询在调用方持有的事务/锁内执行。
     */
    @Query("select d from ConsumerDependencyEntity d where d.subject.id = :subjectId "
            + "and d.contractVersion = :version and d.leaseExpiresAt > :now order by d.consumer asc")
    List<ConsumerDependencyEntity> findActiveByVersion(@Param("subjectId") Long subjectId,
            @Param("version") int version, @Param("now") java.time.Instant now);

    @Query("select d from ConsumerDependencyEntity d where d.subject.id = :subjectId "
            + "and d.leaseExpiresAt > :now order by d.consumer asc")
    List<ConsumerDependencyEntity> findActiveBySubject(@Param("subjectId") Long subjectId,
            @Param("now") java.time.Instant now);
}
