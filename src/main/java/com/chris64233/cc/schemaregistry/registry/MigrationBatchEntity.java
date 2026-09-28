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

/**
 * 消费者迁移批次：绑定同一主题的源版本与目标版本。创建时在主题悲观写锁内冻结
 * 当时仍在使用源版本的有效消费者集合（成员见 {@link MigrationBatchMemberEntity}），
 * 并校验目标版本对每个冻结消费者当前版本满足主题兼容策略，因此不会产生部分可执行的批次。
 *
 * <p>批次只允许单向流转：{@code OPEN → COMPLETED} 或 {@code OPEN → CANCELLED}。
 * 冻结的版本与消费者集合创建后不可变；取消不回退已确认的消费者版本。
 */
@Entity
@Table(name = "migration_batches")
public class MigrationBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "source_version", nullable = false)
    private int sourceVersion;

    @Column(name = "target_version", nullable = false)
    private int targetVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationBatchStatus status = MigrationBatchStatus.OPEN;

    @Column(name = "frozen_count", nullable = false)
    private int frozenCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected MigrationBatchEntity() {
    }

    public MigrationBatchEntity(SubjectEntity subject, int sourceVersion, int targetVersion, int frozenCount,
            Instant createdAt) {
        this.subject = subject;
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        this.frozenCount = frozenCount;
        this.createdAt = createdAt;
        this.status = MigrationBatchStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public SubjectEntity getSubject() {
        return subject;
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

    public int getFrozenCount() {
        return frozenCount;
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

    public String getCancelReason() {
        return cancelReason;
    }

    /**
     * 完成批次：冻结集合全部解决后推动源版本进入废弃流程。只允许从 OPEN 流转一次。
     */
    public void markCompleted(Instant completedAt) {
        this.status = MigrationBatchStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    /**
     * 取消批次：只停止后续迁移推进，不回退已确认的消费者版本。重复取消保持幂等。
     */
    public void markCancelled(Instant cancelledAt, String reason) {
        this.status = MigrationBatchStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.cancelReason = reason;
    }
}
