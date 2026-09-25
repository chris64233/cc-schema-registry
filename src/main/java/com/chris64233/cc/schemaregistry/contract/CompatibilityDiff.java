package com.chris64233.cc.schemaregistry.contract;

/**
 * 一条确定性的兼容性差异。排序规则固定（被检版本号、属性名、规则码），
 * 保证同一对契约总是产生相同的“首个差异”。
 */
public record CompatibilityDiff(int againstVersion, String rule, String property, String message)
        implements Comparable<CompatibilityDiff> {

    public static final String REQUIRED_PROPERTY_REMOVED = "REQUIRED_PROPERTY_REMOVED";
    public static final String PROPERTY_TYPE_CHANGED = "PROPERTY_TYPE_CHANGED";
    public static final String REQUIRED_PROPERTY_MISSING_DEFAULT = "REQUIRED_PROPERTY_MISSING_DEFAULT";
    public static final String ENUM_NEWLY_RESTRICTED = "ENUM_NEWLY_RESTRICTED";
    public static final String ENUM_VALUES_REMOVED = "ENUM_VALUES_REMOVED";

    @Override
    public int compareTo(CompatibilityDiff other) {
        int byVersion = Integer.compare(againstVersion, other.againstVersion);
        if (byVersion != 0) {
            return byVersion;
        }
        int byProperty = property.compareTo(other.property);
        if (byProperty != 0) {
            return byProperty;
        }
        return rule.compareTo(other.rule);
    }
}
