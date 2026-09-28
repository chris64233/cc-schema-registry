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

import com.chris64233.cc.schemaregistry.registry.MigrationBatchService;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationBatchSummary;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationBatchView;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationConfirmationView;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationEvent;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchService.MigrationMemberView;

/**
 * 消费者迁移批次接口：创建批次（冻结消费者集合并做兼容性校验）、查询、
 * 以唯一迁移事件确认、完成前取消。
 */
@RestController
@RequestMapping("/api/subjects/{name}/migration-batches")
public class MigrationBatchController {

    private final MigrationBatchService service;

    public MigrationBatchController(MigrationBatchService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Dto.MigrationBatchResponse> createBatch(@PathVariable String name,
            @Validated @RequestBody Dto.CreateMigrationBatchRequest request) {
        MigrationBatchView view = service.createBatch(name, request.sourceVersion(),
                request.targetVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(toBatchResponse(view));
    }

    @GetMapping
    public List<Dto.MigrationBatchSummaryResponse> listBatches(@PathVariable String name) {
        return service.listBatches(name).stream().map(this::toSummaryResponse).toList();
    }

    @GetMapping("/{batchId}")
    public Dto.MigrationBatchResponse getBatch(@PathVariable String name, @PathVariable long batchId) {
        return toBatchResponse(service.getBatch(name, batchId));
    }

    @PostMapping("/{batchId}/consumers/{consumer}/confirmations")
    public Dto.MigrationConfirmationResponse confirm(@PathVariable String name,
            @PathVariable long batchId, @PathVariable String consumer,
            @Validated @RequestBody Dto.MigrationConfirmationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String eventId) {
        MigrationConfirmationView view = service.confirm(name, batchId, consumer,
                new MigrationEvent(eventId, request.targetVersion(), request.leaseExpiresAt(),
                        request.updateSeq()));
        return toConfirmationResponse(view);
    }

    @PostMapping("/{batchId}/cancellation")
    public Dto.MigrationBatchResponse cancel(@PathVariable String name, @PathVariable long batchId,
            @RequestBody(required = false) Dto.CancelMigrationBatchRequest request) {
        return toBatchResponse(service.cancel(name, batchId,
                request != null ? request.reason() : null));
    }

    private Dto.MigrationBatchSummaryResponse toSummaryResponse(MigrationBatchSummary s) {
        return new Dto.MigrationBatchSummaryResponse(s.batchId(), s.subject(), s.sourceVersion(),
                s.targetVersion(), s.status(), s.frozenCount(), s.pendingCount(), s.createdAt(),
                s.completedAt(), s.cancelledAt());
    }

    private Dto.MigrationBatchResponse toBatchResponse(MigrationBatchView b) {
        return new Dto.MigrationBatchResponse(b.batchId(), b.subject(), b.sourceVersion(),
                b.targetVersion(), b.status(), b.createdAt(), b.completedAt(), b.cancelledAt(),
                b.cancelReason(),
                b.pendingConsumers().stream().map(this::toMemberResponse).toList(),
                b.confirmations().stream().map(this::toConfirmationResponse).toList(),
                b.leaseExpiredMembers().stream().map(this::toMemberResponse).toList(),
                b.detachedMembers().stream().map(this::toMemberResponse).toList(),
                b.sourceDeprecationBlockReasons());
    }

    private Dto.MigrationMemberResponse toMemberResponse(MigrationMemberView m) {
        return new Dto.MigrationMemberResponse(m.consumer(), m.status(), m.frozenVersion(),
                m.observedVersion(), m.frozenLeaseExpiresAt(), m.resolvedAt(),
                m.confirmationEventId());
    }

    private Dto.MigrationConfirmationResponse toConfirmationResponse(MigrationConfirmationView c) {
        return new Dto.MigrationConfirmationResponse(c.eventId(), c.consumer(), c.fromVersion(),
                c.toVersion(), c.updateSeq(), c.leaseExpiresAt(), c.confirmedAt());
    }
}
