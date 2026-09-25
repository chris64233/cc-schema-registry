package com.chris64233.cc.schemaregistry.contract;

import java.util.SortedSet;

/**
 * 单个属性的契约声明。enumValues 仅 string 类型可声明；canonicalDefault 为默认值的规范化 JSON 字面量。
 */
public record PropertyContract(
        String name,
        PropertyType type,
        boolean required,
        SortedSet<String> enumValues,
        String canonicalDefault) {

    public boolean hasDefault() {
        return canonicalDefault != null;
    }

    String canonicalJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":").append(CanonicalJson.quote(type.jsonName()));
        sb.append(",\"required\":").append(required);
        if (enumValues != null) {
            sb.append(",\"enum\":[");
            boolean first = true;
            for (String value : enumValues) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(CanonicalJson.quote(value));
                first = false;
            }
            sb.append(']');
        }
        if (canonicalDefault != null) {
            sb.append(",\"default\":").append(canonicalDefault);
        }
        return sb.append('}').toString();
    }
}
