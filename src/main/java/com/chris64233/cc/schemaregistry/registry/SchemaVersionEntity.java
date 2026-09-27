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

    /**
     * 可变契约载荷。受控删除后置空；摘要、版本号与审计时间戳继续保留。
     */
    @Lob
    @Column(name = "content")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle", nullable = false, length = 32)
    private VersionLifecycle lifecycle = VersionLifecycle.ACTIVE;

    @Column(name = "deprecate_effective_at")
    private Instant deprecateEffectiveAt;

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 删除保留期（毫秒），废弃时确定，删除后作为审计信息保留。 */
    @Column(name = "retention_millis")
    private Long retentionMillis;

    protected SchemaVersionEntity() {
    }

    public SchemaVersionEntity(SubjectEntity subject, int version, String content, String contentHash,
            Instant createdAt) {
        this.subject = subject;
        this.version = version;
        this.content = content;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.lifecycle = VersionLifecycle.ACTIVE;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getRetentionMillis() {
        return retentionMillis;
    }

    public void setRetentionMillis(Long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    /**
     * 执行受控删除：仅移除可变载荷，保留摘要、版本号与审计记录。
     */
    public void markTombstone(Instant deletedAt) {
        this.content = null;
        this.lifecycle = VersionLifecycle.TOMBSTONE;
        this.deletedAt = deletedAt;
    }
}
