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
 * 迁移批次的冻结成员：批次创建时快照的一个源版本有效消费者。
 *
 * <p>成员记录只追加、不可删除；{@link #status} 只允许从 PENDING 向解析态单向流转。
 * 即便租约过期，记录仍保留以便审计（“因租约过期移除的项”）。
 */
@Entity
@Table(name = "migration_batch_members", uniqueConstraints = @UniqueConstraint(
        columnNames = {"batch_id", "consumer"}))
public class MigrationBatchMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private MigrationBatchEntity batch;

    @Column(nullable = false)
    private String consumer;

    /** 冻结时该消费者登记的版本（即批次源版本）。 */
    @Column(name = "frozen_version", nullable = false)
    private int frozenVersion;

    /** 冻结时的租约到期时间快照。 */
    @Column(name = "frozen_lease_expires_at", nullable = false)
    private Instant frozenLeaseExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MigrationMemberStatus status = MigrationMemberStatus.PENDING;

    /** 状态离开 PENDING 的时间（确认时间/扫描观察到租约过期或自行迁出的时间）。 */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 解析时观察到的消费者实际登记版本：确认为目标版本；自行迁出时为其新版本。 */
    @Column(name = "observed_version")
    private Integer observedVersion;

    protected MigrationBatchMemberEntity() {
    }

    public MigrationBatchMemberEntity(MigrationBatchEntity batch, String consumer, int frozenVersion,
            Instant frozenLeaseExpiresAt) {
        this.batch = batch;
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

    public Integer getObservedVersion() {
        return observedVersion;
    }

    public void markResolved(MigrationMemberStatus status, Instant resolvedAt, Integer observedVersion) {
        if (this.status != MigrationMemberStatus.PENDING) {
            throw new IllegalStateException("member '" + consumer + "' already resolved as "
                    + this.status);
        }
        this.status = status;
        this.resolvedAt = resolvedAt;
        this.observedVersion = observedVersion;
    }

    /**
     * 迁移确认是消费者现身的直接证据，优先于扫描时的间接推断：
     * 允许从 PENDING / LEASE_EXPIRED / DETACHED 收敛到 CONFIRMED，但已确认的不可重复确认。
     */
    public void markConfirmed(Instant confirmedAt, int targetVersion) {
        if (this.status == MigrationMemberStatus.CONFIRMED) {
            throw new IllegalStateException("member '" + consumer + "' already confirmed");
        }
        this.status = MigrationMemberStatus.CONFIRMED;
        this.resolvedAt = confirmedAt;
        this.observedVersion = targetVersion;
    }
}
