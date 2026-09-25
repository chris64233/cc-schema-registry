package com.chris64233.cc.schemaregistry.contract;

import java.util.List;

public class InvalidContractException extends RuntimeException {

    private final List<String> violations;

    public InvalidContractException(List<String> violations) {
        super(violations.isEmpty() ? "invalid contract" : violations.get(0));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
