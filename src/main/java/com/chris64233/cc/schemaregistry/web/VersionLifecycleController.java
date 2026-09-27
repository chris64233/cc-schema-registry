package com.chris64233.cc.schemaregistry.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.cc.schemaregistry.registry.ConsumerDependencyEntity;
import com.chris64233.cc.schemaregistry.registry.SchemaVersionEntity;
import com.chris64233.cc.schemaregistry.registry.VersionAuditEntity;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.ConsumerUpdateResult;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.DeleteResult;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycleService.DeletionEligibility;

@RestController
@RequestMapping("/api/subjects/{name}")
public class VersionLifecycleController {

    private final VersionLifecycleService service;

    public VersionLifecycleController(VersionLifecycleService service) {
        this.service = service;
    }

    @PutMapping("/consumers/{consumerId}")
    public Dto.ConsumerDependencyResponse upsertConsumer(@PathVariable String name,
            @PathVariable String consumerId,
            @Validated @RequestBody Dto.UpsertConsumerRequest request) {
        ConsumerUpdateResult result = service.upsertConsumerDependency(name, consumerId, request.version(),
                request.updateSeq(), request.leaseExpiresAt(), request.idempotencyKey());
        return new Dto.ConsumerDependencyResponse(result.subject(), result.consumerId(), result.version(),
                result.updateSeq(), result.leaseExpiresAt(), result.applied(), result.stale());
    }

    @GetMapping("/consumers")
    public List<Dto.ConsumerDependencyResponse> listConsumers(@PathVariable String name) {
        return service.listConsumerDependencies(name).stream()
                .map(dep -> new Dto.ConsumerDependencyResponse(name, dep.getConsumerId(), dep.getVersion(),
                        dep.getUpdateSeq(), dep.getLeaseExpiresAt(), false, false))
                .toList();
    }

    @PostMapping("/versions/{version}/deprecation")
    public Dto.LifecycleResponse requestDeprecation(@PathVariable String name, @PathVariable int version,
            @Validated @RequestBody Dto.DeprecateVersionRequest request) {
        SchemaVersionEntity entity = service.requestDeprecation(name, version, request.effectiveAt(),
                request.retentionSeconds(), request.requestKey());
        return toLifecycleResponse(name, entity);
    }

    @PostMapping("/deprecation-scan")
    public Dto.DeprecationScanResponse scanDeprecations(@PathVariable String name) {
        return new Dto.DeprecationScanResponse(name, service.applyDueDeprecations(name));
    }

    @DeleteMapping("/versions/{version}")
    public ResponseEntity<Dto.DeleteVersionResponse> deleteVersion(@PathVariable String name,
            @PathVariable int version,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        DeleteResult result = service.deleteVersion(name, version, requestKey);
        return ResponseEntity.ok(new Dto.DeleteVersionResponse(result.subject(), result.version(),
                result.deleted(), result.deletedAt()));
    }

    @GetMapping("/versions/{version}/lifecycle")
    public Dto.LifecycleResponse getLifecycle(@PathVariable String name, @PathVariable int version) {
        return toLifecycleResponse(name, service.getLifecycle(name, version));
    }

    @GetMapping("/versions/{version}/deprecation-blockers")
    public Dto.DeprecationBlockersResponse deprecationBlockers(@PathVariable String name,
            @PathVariable int version) {
        SchemaVersionEntity entity = service.getLifecycle(name, version);
        List<Dto.BlockingConsumer> blockers = service.deprecationBlockers(name, version).stream()
                .map(dep -> new Dto.BlockingConsumer(dep.getConsumerId(), dep.getUpdateSeq(),
                        dep.getLeaseExpiresAt()))
                .toList();
        return new Dto.DeprecationBlockersResponse(name, version, entity.getLifecycle().name(),
                !blockers.isEmpty(), blockers);
    }

    @GetMapping("/versions/{version}/deletion-eligibility")
    public Dto.DeletionEligibilityResponse deletionEligibility(@PathVariable String name,
            @PathVariable int version) {
        DeletionEligibility eligibility = service.deletionEligibility(name, version);
        return new Dto.DeletionEligibilityResponse(eligibility.subject(), eligibility.version(),
                eligibility.eligible(), eligibility.reasons());
    }

    @GetMapping("/versions/{version}/audit")
    public List<Dto.AuditEntry> auditTrail(@PathVariable String name, @PathVariable int version) {
        return service.auditTrail(name, version).stream()
                .map(this::toAuditEntry)
                .toList();
    }

    private Dto.LifecycleResponse toLifecycleResponse(String name, SchemaVersionEntity entity) {
        return new Dto.LifecycleResponse(name, entity.getVersion(),
                entity.getLifecycle().name(), entity.getCreatedAt(), entity.getDeprecateEffectiveAt(),
                entity.getDeprecatedAt(), entity.getRetentionSeconds(), entity.getDeletedAt(),
                entity.isContentDeleted());
    }

    private Dto.AuditEntry toAuditEntry(VersionAuditEntity audit) {
        return new Dto.AuditEntry(audit.getVersion(), audit.getEvent().name(), audit.getDetail(),
                audit.getAt());
    }
}
