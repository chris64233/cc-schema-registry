package com.chris64233.cc.schemaregistry.web;

import com.chris64233.cc.schemaregistry.service.SchemaRegistryService;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.CompatibilityReport;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.PublishResult;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.SubjectView;
import com.chris64233.cc.schemaregistry.service.SchemaRegistryService.VersionView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subjects")
public class SubjectController {

    private final SchemaRegistryService service;

    public SubjectController(SchemaRegistryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubjectView> createSubject(@Valid @RequestBody CreateSubjectRequest request) {
        SubjectView created = service.createSubject(request.name(), request.compatibilityMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<SubjectView> listSubjects() {
        return service.listSubjects();
    }

    @GetMapping("/{name}")
    public SubjectView getSubject(@PathVariable String name) {
        return service.getSubject(name);
    }

    /**
     * 发布契约版本。请求体即契约 JSON；可选 Idempotency-Key 头用于幂等发布。
     */
    @PostMapping("/{name}/versions")
    public ResponseEntity<PublishResponse> publish(
            @PathVariable String name,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String contractJson) {
        PublishResult result = service.publish(name, idempotencyKey, contractJson);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new PublishResponse(name, result));
    }

    @GetMapping("/{name}/versions")
    public List<VersionView> listVersions(@PathVariable String name) {
        return service.listVersions(name);
    }

    @GetMapping("/{name}/versions/{version}")
    public VersionView getVersion(@PathVariable String name, @PathVariable int version) {
        return service.getVersion(name, version);
    }

    /**
     * 兼容性差异查询：对候选契约执行与发布相同的全历史校验，但不写入任何记录。
     */
    @PostMapping("/{name}/compatibility-checks")
    public CompatibilityReport checkCompatibility(
            @PathVariable String name, @RequestBody String contractJson) {
        return service.checkCompatibility(name, contractJson);
    }

    public record PublishResponse(String subject, int version, String contentHash,
            boolean created, java.time.Instant createdAt) {

        PublishResponse(String subject, PublishResult result) {
            this(subject, result.version().version(), result.version().contentHash(),
                    result.created(), result.version().createdAt());
        }
    }
}
