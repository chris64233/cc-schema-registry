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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "schema_versions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"subject_id", "version"}),
        @UniqueConstraint(columnNames = {"subject_id", "content_hash"})
})
public class SchemaVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(nullable = false)
    private int version;

    @Lob
    @Column
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VersionLifecycle lifecycle = VersionLifecycle.ACTIVE;

    @Column
    private Instant deprecateEffectiveAt;

    @Column
    private Instant deprecatedAt;

    @Column
    private Long retentionSeconds;

    @Column
    private Instant deletedAt;

    @Column
    private String deprecationRequestKey;

    @Column
    private String deletionRequestKey;

    protected SchemaVersionEntity() {
    }

    public SchemaVersionEntity(SubjectEntity subject, int version, String content, String contentHash,
            Instant createdAt) {
        this.subject = subject;
        this.version = version;
        this.content = content;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
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

    public String getContent() {
        return content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public VersionLifecycle getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(VersionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Instant getDeprecateEffectiveAt() {
        return deprecateEffectiveAt;
    }

    public void setDeprecateEffectiveAt(Instant deprecateEffectiveAt) {
        this.deprecateEffectiveAt = deprecateEffectiveAt;
    }

    public Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }

    public Long getRetentionSeconds() {
        return retentionSeconds;
    }

    public void setRetentionSeconds(Long retentionSeconds) {
        this.retentionSeconds = retentionSeconds;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeprecationRequestKey() {
        return deprecationRequestKey;
    }

    public void setDeprecationRequestKey(String deprecationRequestKey) {
        this.deprecationRequestKey = deprecationRequestKey;
    }

    public String getDeletionRequestKey() {
        return deletionRequestKey;
    }

    public void setDeletionRequestKey(String deletionRequestKey) {
        this.deletionRequestKey = deletionRequestKey;
    }

    public void clearContent() {
        this.content = null;
    }

    public boolean isContentDeleted() {
        return deletedAt != null;
    }
}
