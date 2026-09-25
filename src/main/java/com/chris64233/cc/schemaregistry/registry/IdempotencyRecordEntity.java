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

@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subject_id", "idem_key"}))
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "idem_key", nullable = false)
    private String idemKey;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(SubjectEntity subject, String idemKey, String contentHash, int version,
            Instant createdAt) {
        this.subject = subject;
        this.idemKey = idemKey;
        this.contentHash = contentHash;
        this.version = version;
        this.createdAt = createdAt;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getVersion() {
        return version;
    }
}
