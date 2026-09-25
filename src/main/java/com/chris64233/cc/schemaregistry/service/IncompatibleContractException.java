package com.chris64233.cc.schemaregistry.service;

import com.chris64233.cc.schemaregistry.contract.CompatibilityDiff;
import java.util.List;

/**
 * 契约与主题历史版本不兼容。携带按确定性顺序排列的全部差异，首个差异即稳定错误详情。
 */
public class IncompatibleContractException extends RuntimeException {

    private final List<CompatibilityDiff> diffs;

    public IncompatibleContractException(List<CompatibilityDiff> diffs) {
        super("contract is incompatible: " + diffs.getFirst().message());
        this.diffs = List.copyOf(diffs);
    }

    public List<CompatibilityDiff> diffs() {
        return diffs;
    }

    public CompatibilityDiff firstDiff() {
        return diffs.getFirst();
    }
}
