package com.chris64233.cc.schemaregistry.registry;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 迁移确认事件：消费者确认已切换到批次目标版本的唯一事件，只追加、不可修改。
 *
 * <p>两个唯一约束保证幂等与“一个消费者在一个批次中只能确认一次”：
 * <ul>
 *   <li>{@code event_id} 全局唯一：同一事件重放返回首次结果，携带不同参数则冲突；</li>
 *   <li>{@code (batch_id, consumer)} 唯一：迟到的、与已确认事件不同的旧事件被拒绝，
 *       不能覆盖消费者后来登记的版本。</li>
 * </ul>
 */
@Entity
@Table(name = "migration_confirmations", uniqueConstraints = {
        @UniqueConstraint(columnNames = "event_id"),
        @UniqueConstraint(columnNames = {"batch_id", "consumer"})
})
public class MigrationConfirmationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private MigrationBatchEntity batch;

    /** 全局唯一的迁移事件号（由 Idempotency-Key 头提供）。 */
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "from_version", nullable = false)
    private int fromVersion;

    @Column(name = "to_version", nullable = false)
    private int toVersion;

    /** 事件携带的消费者更新号，用于与依赖登记做迟到比较。 */
    @Column(name = "update_seq", nullable = false)
    private long updateSeq;

    /** 事件携带的新租约到期时间（确认同时续租，避免确认后依赖立即失效）。 */
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected MigrationConfirmationEntity() {
    }

    public MigrationConfirmationEntity(MigrationBatchEntity batch, String eventId, String consumer,
            int fromVersion, int toVersion, long updateSeq, Instant leaseExpiresAt, Instant confirmedAt) {
        this.batch = batch;
        this.eventId = eventId;
        this.consumer = consumer;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.updateSeq = updateSeq;
        this.leaseExpiresAt = leaseExpiresAt;
        this.confirmedAt = confirmedAt;
    }

    public Long getId() {
        return id;
    }

    public MigrationBatchEntity getBatch() {
        return batch;
    }

    public String getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    public int getFromVersion() {
        return fromVersion;
    }

    public int getToVersion() {
        return toVersion;
    }

    public long getUpdateSeq() {
        return updateSeq;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
