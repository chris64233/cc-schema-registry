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
 * 消费者迁移批次：绑定同一主题的源版本与目标版本，创建时冻结仍在使用源版本的
 * 有效消费者集合。批次状态单向流转（进行中 → 已完成 / 已取消），批次记录不可修改。
 *
 * <p>批次号在同一主题内从 1 递增。创建请求携带幂等键时，相同键重放返回首次批次。
 */
@Entity
@Table(name = "migration_batches", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"subject_id", "batch_no"}),
        @UniqueConstraint(columnNames = {"subject_id", "idempotency_key"})
})
public class MigrationBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "batch_no", nullable = false)
    private int batchNo;

    @Column(name = "source_version", nullable = false)
    private int sourceVersion;

    @Column(name = "target_version", nullable = false)
    private int targetVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationBatchStatus status = MigrationBatchStatus.IN_PROGRESS;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** 创建批次的幂等键（对应 Idempotency-Key 头），可空。 */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    protected MigrationBatchEntity() {
    }

    public MigrationBatchEntity(SubjectEntity subject, int batchNo, int sourceVersion, int targetVersion,
            String idempotencyKey, Instant createdAt) {
        this.subject = subject;
        this.batchNo = batchNo;
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.status = MigrationBatchStatus.IN_PROGRESS;
    }

    public Long getId() {
        return id;
    }

    public SubjectEntity getSubject() {
        return subject;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public int getSourceVersion() {
        return sourceVersion;
    }

    public int getTargetVersion() {
        return targetVersion;
    }

    public MigrationBatchStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void markCompleted(Instant completedAt) {
        this.status = MigrationBatchStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void markCancelled(Instant cancelledAt) {
        this.status = MigrationBatchStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
    }
}
