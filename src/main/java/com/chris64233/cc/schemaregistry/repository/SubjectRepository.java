package com.chris64233.cc.schemaregistry.repository;

import com.chris64233.cc.schemaregistry.domain.SubjectEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubjectRepository extends JpaRepository<SubjectEntity, Long> {

    Optional<SubjectEntity> findByName(String name);

    boolean existsByName(String name);

    /**
     * 发布流程入口：对主题行加悲观写锁，串行化同一主题的并发发布，
     * 使每个版本都能在包含更早并发提交的完整历史上重新校验。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubjectEntity s where s.name = :name")
    Optional<SubjectEntity> findByNameForUpdate(@Param("name") String name);
}
