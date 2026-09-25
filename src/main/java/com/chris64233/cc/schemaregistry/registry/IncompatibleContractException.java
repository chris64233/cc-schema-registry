package com.chris64233.cc.schemaregistry.registry;

import java.util.List;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;

public class IncompatibleContractException extends RuntimeException {

    private final List<CompatibilityDiff> diffs;

    public IncompatibleContractException(List<CompatibilityDiff> diffs) {
        super(diffs.isEmpty() ? "contract is incompatible" : diffs.get(0).message());
        this.diffs = List.copyOf(diffs);
    }

    public List<CompatibilityDiff> diffs() {
        return diffs;
    }
}
