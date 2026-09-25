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
        return service.listVersions(name).stream()
                .map(v -> new Dto.VersionSummary(v.getVersion(), v.getContentHash(), v.getCreatedAt()))
                .toList();
    }

    @GetMapping("/{name}/versions/{version}")
    public Dto.VersionResponse getVersion(@PathVariable String name, @PathVariable int version) {
        SchemaVersionEntity entity = service.getVersion(name, version);
        JsonNode contract = jsonMapper.readTree(entity.getContent());
        return new Dto.VersionResponse(name, entity.getVersion(), contract, entity.getContentHash(),
                entity.getCreatedAt());
    }

    @PostMapping("/{name}/compatibility/check")
    public Dto.CompatibilityCheckResponse checkCompatibility(@PathVariable String name,
            @RequestBody String contract) {
        List<CompatibilityDiff> diffs = service.checkCompatibility(name, contract);
        return new Dto.CompatibilityCheckResponse(diffs.isEmpty(), diffs);
    }

    private Dto.SubjectResponse toSubjectResponse(SubjectEntity subject) {
        return new Dto.SubjectResponse(subject.getName(), subject.getCompatibility(), subject.getCreatedAt());
    }
}
