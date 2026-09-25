package com.chris64233.cc.schemaregistry.contract;

import java.util.SortedMap;

/**
 * 规范化后的对象契约。canonicalJson 为确定性文本（属性按名称排序、无空白），
 * contentHash 为其 SHA-256，二者共同构成契约的内容身份。
 */
public record ObjectContract(
        SortedMap<String, PropertyContract> properties,
        String canonicalJson,
        String contentHash) {
}
