package com.chris64233.cc.schemaregistry.web;

import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSubjectRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "只允许字母、数字、点、下划线和连字符")
        String name,

        @NotNull
        CompatibilityMode compatibilityMode) {
}
