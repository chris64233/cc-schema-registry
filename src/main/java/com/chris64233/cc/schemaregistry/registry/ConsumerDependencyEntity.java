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
 * 消费者依赖登记：某个消费者正在使用某主题的某个契约版本，租约到期前该依赖有效。
 * 同一消费者的更新带 {@code expectedVersion} 乐观条件与单调递增的更新号，
 * 迟到心跳不能覆盖较新的依赖版本。
 */
@Entity
@Table(name = "consumer_dependencies",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subject_id", "consumer"}))
public class ConsumerDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    /** 单调递增的更新号，用于拒绝迟到的心跳/重放。 */
    @Column(name = "update_seq", nullable = false)
    private long updateSeq;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConsumerDependencyEntity() {
    }

    public ConsumerDependencyEntity(SubjectEntity subject, String consumer, int contractVersion,
            Instant leaseExpiresAt, long updateSeq, Instant updatedAt) {
        this.subject = subject;
        this.consumer = consumer;
        this.contractVersion = contractVersion;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updateSeq = updateSeq;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public SubjectEntity getSubject() {
        return subject;
    }

    public String getConsumer() {
        return consumer;
    }

    public int getContractVersion() {
        return contractVersion;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public long getUpdateSeq() {
        return updateSeq;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void apply(int contractVersion, Instant leaseExpiresAt, long updateSeq, Instant updatedAt) {
        this.contractVersion = contractVersion;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updateSeq = updateSeq;
        this.updatedAt = updatedAt;
    }
}
