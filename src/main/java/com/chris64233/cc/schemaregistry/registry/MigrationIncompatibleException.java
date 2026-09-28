package com.chris64233.cc.schemaregistry.registry;

import java.util.List;

import com.chris64233.cc.schemaregistry.compat.CompatibilityDiff;

/**
 * 迁移批次创建失败：目标版本与冻结消费者当前版本之间不满足主题兼容策略。
 * 携带按（消费者、版本、属性、规则、方向）确定排序的差异列表，绝不创建部分可执行的批次。
 */
public class MigrationIncompatibleException extends RuntimeException {

    /** 不兼容的冻结消费者（已排序）。 */
    private final List<String> consumers;

    private final List<CompatibilityDiff> diffs;

    public MigrationIncompatibleException(List<String> consumers, List<CompatibilityDiff> diffs) {
        super("migration target is incompatible with current version of consumers " + consumers
                + (diffs.isEmpty() ? "" : "; first diff: " + diffs.get(0).message()));
        this.consumers = List.copyOf(consumers);
        this.diffs = List.copyOf(diffs);
    }

    public List<String> consumers() {
        return consumers;
    }

    public List<CompatibilityDiff> diffs() {
        return diffs;
    }
}
