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

    record ErrorResponse(String code, String message, List<String> details, List<CompatibilityDiff> diffs) {
    }
}
