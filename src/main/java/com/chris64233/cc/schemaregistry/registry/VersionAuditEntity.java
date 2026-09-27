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
 * 版本生命周期审计记录。版本删除只清除可变载荷，审计记录永久保留。
 */
@Entity
@Table(name = "version_audit")
public class VersionAuditEntity {

    public enum Event {
        PUBLISHED,
        DEPRECATION_REQUESTED,
        DEPRECATED,
        DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Event event;

    @Column
    private String detail;

    @Column(nullable = false)
    private Instant at;

    protected VersionAuditEntity() {
    }

    public VersionAuditEntity(SubjectEntity subject, int version, Event event, String detail, Instant at) {
        this.subject = subject;
        this.version = version;
        this.event = event;
        this.detail = detail;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public Event getEvent() {
        return event;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
