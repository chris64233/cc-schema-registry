package com.chris64233.cc.schemaregistry.registry;

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
 * 兼容性检查依赖边：版本 {@code fromVersion} 发布时曾针对 {@code toVersion} 执行兼容性检查。
 * 每个新版本发布时对当时全部历史版本建边。{@code fromVersion} 被受控删除（载荷移除）后，
 * 其出边一并移除：它不再作为需要历史依据的契约存在。仍存在入边的版本不能被删除。
 */
@Entity
@Table(name = "compat_reference_edges", uniqueConstraints = @UniqueConstraint(
        columnNames = {"subject_id", "from_version", "to_version"}))
public class CompatReferenceEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private SubjectEntity subject;

    @Column(name = "from_version", nullable = false)
    private int fromVersion;

    @Column(name = "to_version", nullable = false)
    private int toVersion;

    protected CompatReferenceEdgeEntity() {
    }

    public CompatReferenceEdgeEntity(SubjectEntity subject, int fromVersion, int toVersion) {
        this.subject = subject;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    public int getFromVersion() {
        return fromVersion;
    }

    public int getToVersion() {
        return toVersion;
    }
}
