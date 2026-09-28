package com.chris64233.cc.schemaregistry.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerResult;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.ConsumerView;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.DeleteEligibility;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.DeleteResult;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.DeprecationResult;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.LifecycleView;
import com.chris64233.cc.schemaregistry.registry.SchemaRegistryService.PublishResult;
import com.chris64233.cc.schemaregistry.registry.SchemaVersionEntity;
import com.chris64233.cc.schemaregistry.registry.SubjectEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/subjects")
public class SchemaRegistryController {

    private final SchemaRegistryService service;
    private final JsonMapper jsonMapper = JsonMapper.shared();

    public SchemaRegistryController(SchemaRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Dto.SubjectResponse> createSubject(
            @Validated @RequestBody Dto.CreateSubjectRequest request) {
        SubjectEntity subject = service.createSubject(request.name(), request.compatibility());
        return ResponseEntity.status(HttpStatus.CREATED).body(toSubjectResponse(subject));
    }

    @GetMapping
    public List<Dto.SubjectResponse> listSubjects() {
        return service.listSubjects().stream().map(this::toSubjectResponse).toList();
    }

    @GetMapping("/{name}")
    public Dto.SubjectResponse getSubject(@PathVariable String name) {
        return toSubjectResponse(service.getSubject(name));
    }

    @PostMapping("/{name}/versions")
    public ResponseEntity<Dto.PublishResponse> publish(@PathVariable String name,
            @RequestBody String contract,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PublishResult result = service.publish(name, contract, idempotencyKey);
        Dto.PublishResponse body = new Dto.PublishResponse(result.subject(), result.version(),
                result.contentHash(), result.created());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/{name}/versions")
    public List<Dto.VersionSummary> listVersions(@PathVariable String name) {
        return service.listVersions(name).stream().map(this::toVersionSummary).toList();
    }

    @GetMapping("/{name}/versions/{version}")
    public Dto.VersionResponse getVersion(@PathVariable String name, @PathVariable int version) {
        SchemaVersionEntity entity = service.getVersion(name, version);
        // 受控删除后载荷为空，contract 返回 null，但摘要、版本号与生命周期仍可查询。
        JsonNode contract = entity.getContent() != null ? jsonMapper.readTree(entity.getContent()) : null;
        return new Dto.VersionResponse(name, entity.getVersion(), contract, entity.getContentHash(),
                entity.getCreatedAt(), entity.getLifecycle(), entity.getDeprecateEffectiveAt(),
                entity.getDeprecatedAt(), entity.getDeletedAt());
    }

    @PostMapping("/{name}/compatibility/check")
    public Dto.CompatibilityCheckResponse checkCompatibility(@PathVariable String name,
            @RequestBody String contract) {
        List<CompatibilityDiff> diffs = service.checkCompatibility(name, contract);
        return new Dto.CompatibilityCheckResponse(diffs.isEmpty(), diffs);
    }

    @PostMapping("/{name}/consumers/{consumer}")
    public ResponseEntity<Dto.ConsumerResponse> registerConsumer(@PathVariable String name,
            @PathVariable String consumer,
            @Validated @RequestBody Dto.ConsumerRegistrationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ConsumerResult result = service.registerConsumer(name,
                new SchemaRegistryService.ConsumerRegistration(consumer, request.version(),
                        request.leaseExpiresAt(), request.updateSeq(), request.expectedVersion(),
                        idempotencyKey));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new Dto.ConsumerResponse(result.consumer(), result.version(), result.leaseExpiresAt(),
                        result.updateSeq(), result.updatedAt(), true));
    }

    @GetMapping("/{name}/consumers")
    public List<Dto.ConsumerResponse> listConsumers(@PathVariable String name) {
        return service.listConsumers(name).stream().map(this::toConsumerResponse).toList();
    }

    @PostMapping("/{name}/versions/{version}/deprecations")
    public Dto.DeprecationResponse deprecate(@PathVariable String name, @PathVariable int version,
            @RequestBody(required = false) Dto.DeprecationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DeprecationResult result = service.requestDeprecation(name, version,
                request != null ? request.effectiveAt() : null,
                request != null ? request.retentionMillis() : null, idempotencyKey);
        return toDeprecationResponse(result);
    }

    @PostMapping("/{name}/versions/{version}/deletions")
    public ResponseEntity<Dto.DeleteResponse> deleteVersion(@PathVariable String name,
            @PathVariable int version,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DeleteResult result = service.requestDeletion(name, version, idempotencyKey);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new Dto.DeleteResponse(result.subject(), result.version(), result.deleted(),
                        result.deletedAt(), result.reasons()));
    }

    @GetMapping("/{name}/versions/{version}/lifecycle")
    public Dto.LifecycleResponse lifecycle(@PathVariable String name, @PathVariable int version) {
        return toLifecycleResponse(service.getLifecycle(name, version));
    }

    @GetMapping("/{name}/versions/{version}/deletion-eligibility")
    public Dto.DeletionEligibilityResponse deletionEligibility(@PathVariable String name,
            @PathVariable int version) {
        return toEligibilityResponse(service.explainDeletion(name, version));
    }

    @PostMapping("/deprecation-scans")
    public Dto.ScanResponse scanDeprecations() {
        SchemaRegistryService.ScanResult result = service.scanDeprecations();
        return new Dto.ScanResponse(result.deprecatedVersions(), result.completedBatches());
    }

    private Dto.SubjectResponse toSubjectResponse(SubjectEntity subject) {
        return new Dto.SubjectResponse(subject.getName(), subject.getCompatibility(), subject.getCreatedAt());
    }

    private Dto.VersionSummary toVersionSummary(SchemaVersionEntity v) {
        return new Dto.VersionSummary(v.getVersion(), v.getContentHash(), v.getCreatedAt(),
                v.getLifecycle(), v.getDeprecateEffectiveAt(), v.getDeprecatedAt(), v.getDeletedAt(),
                v.getContent() != null);
    }

    private Dto.ConsumerResponse toConsumerResponse(ConsumerView c) {
        return new Dto.ConsumerResponse(c.consumer(), c.version(), c.leaseExpiresAt(), c.updateSeq(),
                c.updatedAt(), c.active());
    }

    private Dto.DeprecationResponse toDeprecationResponse(DeprecationResult r) {
        return new Dto.DeprecationResponse(r.subject(), r.version(), r.lifecycle(), r.effectiveAt(),
                r.deprecatedAt(), r.blockingConsumers().stream().map(this::toConsumerResponse).toList());
    }

    private Dto.DeletionEligibilityResponse toEligibilityResponse(DeleteEligibility e) {
        return new Dto.DeletionEligibilityResponse(e.eligible(), e.reasons(), e.referencedByVersions(),
                e.activeConsumers().stream().map(this::toConsumerResponse).toList());
    }

    private Dto.LifecycleResponse toLifecycleResponse(LifecycleView v) {
        return new Dto.LifecycleResponse(v.subject(), v.version(), v.lifecycle(), v.createdAt(),
                v.deprecateEffectiveAt(), v.deprecatedAt(), v.deletedAt(), v.retentionMillis(),
                v.contentHash(),
                v.blockingConsumers().stream().map(this::toConsumerResponse).toList(),
                new Dto.CompatibilityReferencesResponse(v.compatibilityReferences().referencedByVersions()),
                toEligibilityResponse(v.deleteEligibility()),
                v.audit().stream()
                        .map(a -> new Dto.AuditEventResponse(a.eventType(), a.requestId(), a.detail(),
                                a.eventTime()))
                        .toList());
    }
}
