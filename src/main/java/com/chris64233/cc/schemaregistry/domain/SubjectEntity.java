package com.chris64233.cc.schemaregistry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "subject", uniqueConstraints = @UniqueConstraint(name = "uk_subject_name", columnNames = "name"))
public class SubjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "compatibility_mode", nullable = false, length = 16)
    private CompatibilityMode compatibilityMode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SubjectEntity() {
    }

    public SubjectEntity(String name, CompatibilityMode compatibilityMode) {
        this.name = name;
        this.compatibilityMode = compatibilityMode;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CompatibilityMode getCompatibilityMode() {
        return compatibilityMode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
