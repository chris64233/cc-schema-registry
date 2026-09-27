package com.chris64233.cc.schemaregistry.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.registry.CompatibilityMode;

import tools.jackson.databind.JsonNode;

final class Dto {

    private Dto() {
    }

    record CreateSubjectRequest(@NotBlank String name, @NotNull CompatibilityMode compatibility) {
    }

    record SubjectResponse(String name, CompatibilityMode compatibility, Instant createdAt) {
    }

    record PublishResponse(String subject, int version, String contentHash, boolean created) {
    }

    record VersionSummary(int version, String contentHash, Instant createdAt) {
    }

    record VersionResponse(String subject, int version, JsonNode contract, String contentHash,
            Instant createdAt) {
    }

    record CompatibilityCheckResponse(boolean compatible, List<CompatibilityDiff> diffs) {
    }

    record UpsertConsumerRequest(@NotNull Integer version, @NotNull Long updateSeq,
            @NotNull Instant leaseExpiresAt, String idempotencyKey) {
    }

    record ConsumerDependencyResponse(String subject, String consumerId, int version, long updateSeq,
            Instant leaseExpiresAt, boolean applied, boolean stale) {
    }

    record DeprecateVersionRequest(@NotNull Instant effectiveAt, @NotNull Long retentionSeconds,
            String requestKey) {
    }

    record LifecycleResponse(String subject, int version, String lifecycle, Instant createdAt,
            Instant deprecateEffectiveAt, Instant deprecatedAt, Long retentionSeconds, Instant deletedAt,
            boolean contentDeleted) {
    }

    record BlockingConsumer(String consumerId, long updateSeq, Instant leaseExpiresAt) {
    }

    record DeprecationBlockersResponse(String subject, int version, String lifecycle, boolean blocked,
            List<BlockingConsumer> blockers) {
    }

    record DeletionEligibilityResponse(String subject, int version, boolean eligible, List<String> reasons) {
    }

    record DeprecationScanResponse(String subject, List<Integer> deprecatedVersions) {
    }

    record DeleteVersionResponse(String subject, int version, boolean deleted, Instant deletedAt) {
    }

    record AuditEntry(int version, String event, String detail, Instant at) {
    }

    record ErrorResponse(String code, String message, List<String> details, List<CompatibilityDiff> diffs) {
    }
}
