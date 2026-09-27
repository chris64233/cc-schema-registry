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
 * 幂等请求记录。废弃请求、消费者更新、删除请求按
 * {@code (subject, requestKind, requestId)} 分别幂等，并保存首次请求的结果用于重放。
 */
@Entity
@Table(name = "operation_requests", uniqueConstraints = @UniqueConstraint(
        columnNames = {"subject_id", "request_kind", "request_id", "consumer"}))
public class OperationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "request_kind", nullable = false, length = 32)
    private String requestKind;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "consumer", nullable = false, length = 128)
    private String consumer;

    @Column(name = "contract_version")
    private Integer contractVersion;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "update_seq")
    private Long updateSeq;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "retention_millis")
    private Long retentionMillis;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_lifecycle", length = 32)
    private VersionLifecycle resultLifecycle;

    @Column(name = "result_event_at")
    private Instant resultEventAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected OperationRequestEntity() {
    }

    public OperationRequestEntity(SubjectEntity subject, String requestKind, String requestId, String consumer,
            Integer contractVersion, Instant leaseExpiresAt, Long updateSeq, Instant effectiveAt,
            Long retentionMillis, VersionLifecycle resultLifecycle, Instant resultEventAt, Instant createdAt) {
        this.subject = subject;
        this.requestKind = requestKind;
        this.requestId = requestId;
        this.consumer = consumer;
        this.contractVersion = contractVersion;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updateSeq = updateSeq;
        this.effectiveAt = effectiveAt;
        this.retentionMillis = retentionMillis;
        this.resultLifecycle = resultLifecycle;
        this.resultEventAt = resultEventAt;
        this.createdAt = createdAt;
    }

    public String getRequestKind() {
        return requestKind;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getConsumer() {
        return consumer;
    }

    public Integer getContractVersion() {
        return contractVersion;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Long getUpdateSeq() {
        return updateSeq;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Long getRetentionMillis() {
        return retentionMillis;
    }

    public VersionLifecycle getResultLifecycle() {
        return resultLifecycle;
    }

    public Instant getResultEventAt() {
        return resultEventAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
