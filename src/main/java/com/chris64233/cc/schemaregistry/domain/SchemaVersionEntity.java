package com.chris64233.cc.schemaregistry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "schema_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_version_subject_version", columnNames = {"subject_id", "version_no"}),
        @UniqueConstraint(name = "uk_version_subject_hash", columnNames = {"subject_id", "content_hash"}),
        @UniqueConstraint(name = "uk_version_subject_idempotency", columnNames = {"subject_id", "idempotency_key"})
})
public class SchemaVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "version_no", nullable = false)
    private int version;

    @Lob
    @Column(name = "canonical_content", nullable = false)
    private String canonicalContent;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SchemaVersionEntity() {
    }

    public SchemaVersionEntity(SubjectEntity subject, int version, String canonicalContent,
            String contentHash, String idempotencyKey) {
        this.subject = subject;
        this.version = version;
        this.canonicalContent = canonicalContent;
        this.contentHash = contentHash;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public SubjectEntity getSubject() {
        return subject;
    }

    public int getVersion() {
        return version;
    }

    public String getCanonicalContent() {
        return canonicalContent;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
