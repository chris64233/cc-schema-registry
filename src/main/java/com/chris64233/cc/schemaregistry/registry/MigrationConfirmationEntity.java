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
 * 迁移确认历史：消费者确认切换到批次目标版本的唯一迁移事件，只追加、不可修改。
 *
 * <p>事件必须匹配批次、消费者与目标版本。{@code (subject, eventId)} 唯一：
 * 相同事件号重复提交按幂等重放（返回首次结果），同号用于其它参数则冲突。
 */
@Entity
@Table(name = "migration_confirmations", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"subject_id", "event_id"}),
        @UniqueConstraint(columnNames = {"batch_id", "consumer"})
})
public class MigrationConfirmationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private MigrationBatchEntity batch;

    /** 消费者提供的唯一迁移事件号。 */
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "target_version", nullable = false)
    private int targetVersion;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected MigrationConfirmationEntity() {
    }

    public MigrationConfirmationEntity(SubjectEntity subject, MigrationBatchEntity batch, String eventId,
            String consumer, int targetVersion, Instant confirmedAt) {
        this.subject = subject;
        this.batch = batch;
        this.eventId = eventId;
        this.consumer = consumer;
        this.targetVersion = targetVersion;
        this.confirmedAt = confirmedAt;
    }

    public SubjectEntity getSubject() {
        return subject;
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

    public int getTargetVersion() {
        return targetVersion;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
