package com.chris64233.cc.schemaregistry.web;

import com.chris64233.cc.schemaregistry.contract.CompatibilityDiff;
import java.util.List;

public record ErrorResponse(String code, String message,
        CompatibilityDiff firstDiff, List<CompatibilityDiff> diffs) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, null);
    }
}
