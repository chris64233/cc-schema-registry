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

/**
 * 版本生命周期审计事件：废弃请求、废弃生效、受控删除等只追加记录。
 * 版本载荷被删除后审计记录继续保留。
 */
@Entity
@Table(name = "version_audit_events")
public class VersionAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "contract_version", nullable = false)
    private int contractVersion;

    /** 事件类型，如 DEPRECATION_REQUESTED、DEPRECATED、DELETE_REQUESTED、DELETED。 */
    @Column(nullable = false, length = 40)
    private String eventType;

    /** 触发该事件的请求号（幂等键），如有。 */
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(length = 1000)
    private String detail;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    protected VersionAuditEventEntity() {
    }

    public VersionAuditEventEntity(SubjectEntity subject, int contractVersion, String eventType,
            String requestId, String detail, Instant eventTime) {
        this.subject = subject;
        this.contractVersion = contractVersion;
        this.eventType = eventType;
        this.requestId = requestId;
        this.detail = detail;
        this.eventTime = eventTime;
    }

    public int getContractVersion() {
        return contractVersion;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getEventTime() {
        return eventTime;
    }
}
