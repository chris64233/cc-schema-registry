package com.chris64233.cc.schemaregistry.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.SortedMap;

/**
 * 规范化后的对象契约：属性按名称排序，语义相同契约产生相同的规范化文本与内容哈希。
 */
public record ObjectContract(SortedMap<String, PropertyContract> properties) {

    public String canonicalJson() {
        StringBuilder sb = new StringBuilder("{\"properties\":{");
        boolean first = true;
        for (PropertyContract property : properties.values()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(CanonicalJson.quote(property.name())).append(':').append(property.canonicalJson());
            first = false;
        }
        return sb.append("}}").toString();
    }

    public String contentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
