package com.chris64233.cc.schemaregistry.registry;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "subjects", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class SubjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompatibilityMode compatibility;

    @Column(nullable = false)
    private Instant createdAt;

    protected SubjectEntity() {
    }

    public SubjectEntity(String name, CompatibilityMode compatibility, Instant createdAt) {
        this.name = name;
        this.compatibility = compatibility;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CompatibilityMode getCompatibility() {
        return compatibility;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
