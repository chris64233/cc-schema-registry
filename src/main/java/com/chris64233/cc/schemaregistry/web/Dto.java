package com.chris64233.cc.schemaregistry.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;
import com.chris64233.cc.schemaregistry.registry.CompatibilityMode;
import com.chris64233.cc.schemaregistry.registry.MigrationBatchStatus;
import com.chris64233.cc.schemaregistry.registry.MigrationMemberStatus;
import com.chris64233.cc.schemaregistry.registry.VersionLifecycle;

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

    record VersionSummary(int version, String contentHash, Instant createdAt, VersionLifecycle lifecycle,
            Instant deprecateEffectiveAt, Instant deprecatedAt, Instant deletedAt, boolean payloadPresent) {
    }

    record VersionResponse(String subject, int version, JsonNode contract, String contentHash,
            Instant createdAt, VersionLifecycle lifecycle, Instant deprecateEffectiveAt,
            Instant deprecatedAt, Instant deletedAt) {
    }

    record CompatibilityCheckResponse(boolean compatible, List<CompatibilityDiff> diffs) {
    }

    record DeprecationRequest(Instant effectiveAt, Long retentionMillis) {
    }

    record DeprecationResponse(String subject, int version, VersionLifecycle lifecycle, Instant effectiveAt,
            Instant deprecatedAt, List<ConsumerResponse> blockingConsumers) {
    }

    record ConsumerRegistrationRequest(int version, @NotNull Instant leaseExpiresAt, long updateSeq,
            Integer expectedVersion) {
    }

    record ConsumerResponse(String consumer, int version, Instant leaseExpiresAt, long updateSeq,
            Instant updatedAt, boolean active) {
    }

    record DeleteResponse(String subject, int version, boolean deleted, Instant deletedAt,
            List<String> reasons) {
    }

    record DeletionEligibilityResponse(boolean eligible, List<String> reasons,
            List<Integer> referencedByVersions, List<ConsumerResponse> activeConsumers) {
    }

    record AuditEventResponse(String eventType, String requestId, String detail, Instant eventTime) {
    }

    record LifecycleResponse(String subject, int version, VersionLifecycle lifecycle, Instant createdAt,
            Instant deprecateEffectiveAt, Instant deprecatedAt, Instant deletedAt, Long retentionMillis,
            String contentHash, List<ConsumerResponse> blockingConsumers,
            CompatibilityReferencesResponse compatibilityReferences,
            DeletionEligibilityResponse deleteEligibility, List<AuditEventResponse> audit) {
    }

    record CompatibilityReferencesResponse(List<Integer> referencedByVersions) {
    }

    record ScanResponse(int deprecatedVersions, int completedMigrationBatches) {
    }

    // ------------------------------------------------------------------
    // 消费者迁移批次
    // ------------------------------------------------------------------

    record CreateMigrationBatchRequest(int sourceVersion, int targetVersion) {
    }

    record MigrationBatchSummaryResponse(long batchId, String subject, int sourceVersion,
            int targetVersion, MigrationBatchStatus status, int frozenCount, int pendingCount,
            Instant createdAt, Instant completedAt, Instant cancelledAt) {
    }

    record MigrationConfirmationRequest(Integer targetVersion, @NotNull Instant leaseExpiresAt,
            long updateSeq) {
    }

    record MigrationConfirmationResponse(String eventId, String consumer, int fromVersion,
            int toVersion, long updateSeq, Instant leaseExpiresAt, Instant confirmedAt) {
    }

    record MigrationMemberResponse(String consumer, MigrationMemberStatus status, int frozenVersion,
            Integer observedVersion, Instant frozenLeaseExpiresAt, Instant resolvedAt,
            String confirmationEventId) {
    }

    record CancelMigrationBatchRequest(String reason) {
    }

    record MigrationBatchResponse(long batchId, String subject, int sourceVersion, int targetVersion,
            MigrationBatchStatus status, Instant createdAt, Instant completedAt, Instant cancelledAt,
            String cancelReason,
            List<MigrationMemberResponse> pendingConsumers,
            List<MigrationConfirmationResponse> confirmations,
            List<MigrationMemberResponse> leaseExpiredMembers,
            List<MigrationMemberResponse> detachedMembers,
            List<String> sourceDeprecationBlockReasons) {
    }

    record ErrorResponse(String code, String message, List<String> details, List<CompatibilityDiff> diffs) {
    }
}
