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
 * 消费者对某主题某契约版本的依赖登记。updateSeq 是消费者侧单调递增的更新序号，
 * 只有更大序号的更新才会生效，迟到的心跳（旧序号）不会覆盖较新的依赖版本。
 * lastIdemKey/lastRequestHash 用于消费者更新号的幂等重放。
 */
@Entity
@Table(name = "consumer_dependencies",
        uniqueConstraints = @UniqueConstraint(columnNames = {"subject_id", "consumer_id"}))
public class ConsumerDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "consumer_id", nullable = false)
    private String consumerId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private long updateSeq;

    @Column(nullable = false)
    private Instant leaseExpiresAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private String lastIdemKey;

    @Column
    private String lastRequestHash;

    protected ConsumerDependencyEntity() {
    }

    public ConsumerDependencyEntity(SubjectEntity subject, String consumerId, int version, long updateSeq,
            Instant leaseExpiresAt, Instant updatedAt, String lastIdemKey, String lastRequestHash) {
        this.subject = subject;
        this.consumerId = consumerId;
        this.version = version;
        this.updateSeq = updateSeq;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = updatedAt;
        this.lastIdemKey = lastIdemKey;
        this.lastRequestHash = lastRequestHash;
    }

    public Long getId() {
        return id;
    }

    public SubjectEntity getSubject() {
        return subject;
    }

    public String getConsumerId() {
        return consumerId;
    }

    public int getVersion() {
        return version;
    }

    public long getUpdateSeq() {
        return updateSeq;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getLastIdemKey() {
        return lastIdemKey;
    }

    public String getLastRequestHash() {
        return lastRequestHash;
    }

    public void update(int version, long updateSeq, Instant leaseExpiresAt, Instant updatedAt,
            String lastIdemKey, String lastRequestHash) {
        this.version = version;
        this.updateSeq = updateSeq;
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = updatedAt;
        this.lastIdemKey = lastIdemKey;
        this.lastRequestHash = lastRequestHash;
    }

    public boolean hasActiveLease(Instant now) {
        return leaseExpiresAt.isAfter(now);
    }
}
