package com.chris64233.cc.schemaregistry.compat;

/**
 * 一条确定性兼容性差异。排序规则固定：版本号、属性名、规则、方向。
 */
public record CompatibilityDiff(
        int version,
        String direction,
        String rule,
        String property,
        String message) implements Comparable<CompatibilityDiff> {

    public static final String RULE_REQUIRED_PROPERTY_REMOVED = "REQUIRED_PROPERTY_REMOVED";
    public static final String RULE_PROPERTY_TYPE_CHANGED = "PROPERTY_TYPE_CHANGED";
    public static final String RULE_REQUIRED_PROPERTY_WITHOUT_DEFAULT = "REQUIRED_PROPERTY_WITHOUT_DEFAULT";
    public static final String RULE_ENUM_VALUES_REMOVED = "ENUM_VALUES_REMOVED";

    @Override
    public int compareTo(CompatibilityDiff other) {
        int byVersion = Integer.compare(version, other.version);
        if (byVersion != 0) {
            return byVersion;
        }
        int byProperty = property.compareTo(other.property);
        if (byProperty != 0) {
            return byProperty;
        }
        int byRule = rule.compareTo(other.rule);
        if (byRule != 0) {
            return byRule;
        }
        return direction.compareTo(other.direction);
    }
}
