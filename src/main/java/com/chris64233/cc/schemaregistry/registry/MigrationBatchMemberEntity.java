package com.chris64233.cc.schemaregistry.registry;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 迁移批次的冻结成员：批次创建时对源版本每个有效消费者依赖拍一张快照
 * （消费者、当时登记版本、当时租约到期时间）。
 *
 * <p>成员状态在批次进行中按当前依赖实时判定；批次完成时被固化，之后不可修改。
 * 取消批次不改变成员快照与已确认状态。
 */
@Entity
@Table(name = "migration_batch_members", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"batch_id", "consumer"}),
        @UniqueConstraint(columnNames = {"subject_id", "batch_no", "consumer"})
})
public class MigrationBatchMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private MigrationBatchEntity batch;

    /** 冗余 subject，便于按主题查询与加锁后批量读取。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "batch_no", nullable = false)
    private int batchNo;

    @Column(nullable = false)
    private String consumer;

    /** 冻结时该消费者登记的版本（即源版本）。 */
    @Column(name = "frozen_version", nullable = false)
    private int frozenVersion;

    /** 冻结时该消费者的租约到期时间。 */
    @Column(name = "frozen_lease_expires_at", nullable = false)
    private Instant frozenLeaseExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationMemberStatus status = MigrationMemberStatus.PENDING;

    /** 状态固化（确认/过期/改登记）的时间；PENDING 时为空。 */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected MigrationBatchMemberEntity() {
    }

    public MigrationBatchMemberEntity(MigrationBatchEntity batch, SubjectEntity subject, String consumer,
            int frozenVersion, Instant frozenLeaseExpiresAt) {
        this.batch = batch;
        this.subject = subject;
        this.batchNo = batch.getBatchNo();
        this.consumer = consumer;
        this.frozenVersion = frozenVersion;
        this.frozenLeaseExpiresAt = frozenLeaseExpiresAt;
        this.status = MigrationMemberStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public MigrationBatchEntity getBatch() {
        return batch;
    }

    public SubjectEntity getSubject() {
        return subject;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public String getConsumer() {
        return consumer;
    }

    public int getFrozenVersion() {
        return frozenVersion;
    }

    public Instant getFrozenLeaseExpiresAt() {
        return frozenLeaseExpiresAt;
    }

    public MigrationMemberStatus getStatus() {
        return status;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void resolve(MigrationMemberStatus status, Instant resolvedAt) {
        this.status = status;
        this.resolvedAt = resolvedAt;
    }
}
