package com.chris64233.cc.schemaregistry.contract;

import java.util.SortedSet;

/**
 * 单个属性的规范化声明。
 *
 * @param enumValues 仅 string 类型可声明，已排序；为 null 表示不限制取值
 * @param canonicalDefault 默认值的规范化 JSON 文本；为 null 表示未声明默认值
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
}
