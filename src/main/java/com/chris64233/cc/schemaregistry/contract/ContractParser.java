package com.chris64233.cc.schemaregistry.contract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 解析并规范化对象契约：
 *
 * <pre>
 * {
 *   "properties": {
 *     "name": { "type": "string", "required": true, "enum": ["a", "b"], "default": "a" }
 *   }
 * }
 * </pre>
 *
 * 规范化后属性按名称排序、键序固定、无空白，语义相同的契约得到相同的 canonicalJson 与 contentHash。
 */
@Component
public class ContractParser {

    private static final Set<String> ROOT_KEYS = Set.of("properties");
    private static final Set<String> PROPERTY_KEYS = Set.of("type", "required", "enum", "default");

    private final JsonMapper strictMapper = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .build();

    private final JsonMapper writer = JsonMapper.builder().build();

    public ObjectContract parse(String contractJson) {
        JsonNode root = readTree(contractJson);
        if (!root.isObject()) {
            throw new ContractValidationException("contract must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!ROOT_KEYS.contains(entry.getKey())) {
                throw new ContractValidationException("unknown contract field: " + entry.getKey());
            }
        }
        JsonNode propertiesNode = root.get("properties");
        if (propertiesNode == null || !propertiesNode.isObject()) {
            throw new ContractValidationException("contract.properties must be a JSON object");
        }
        SortedMap<String, PropertyContract> properties = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : propertiesNode.properties()) {
            String name = entry.getKey();
            if (name.isBlank()) {
                throw new ContractValidationException("property name must not be blank");
            }
            properties.put(name, parseProperty(name, entry.getValue()));
        }
        String canonicalJson = canonicalize(properties);
        return new ObjectContract(properties, canonicalJson, sha256(canonicalJson));
    }

    private JsonNode readTree(String contractJson) {
        if (contractJson == null || contractJson.isBlank()) {
            throw new ContractValidationException("contract body must not be empty");
        }
        try {
            return strictMapper.readTree(contractJson);
        } catch (JacksonException e) {
            String message = e.getMessage() != null && e.getMessage().contains("Duplicate")
                    ? "contract contains duplicate field names"
                    : "contract is not valid JSON";
            throw new ContractValidationException(message);
        }
    }

    private PropertyContract parseProperty(String name, JsonNode node) {
        if (!node.isObject()) {
            throw new ContractValidationException("property '" + name + "' must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!PROPERTY_KEYS.contains(entry.getKey())) {
                throw new ContractValidationException(
                        "property '" + name + "' has unknown field: " + entry.getKey());
            }
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new ContractValidationException("property '" + name + "' must declare a type");
        }
        PropertyType type = PropertyType.fromJsonName(typeNode.textValue())
                .orElseThrow(() -> new ContractValidationException(
                        "property '" + name + "' has unknown type: " + typeNode.textValue()));

        JsonNode requiredNode = node.get("required");
        if (requiredNode != null && !requiredNode.isBoolean()) {
            throw new ContractValidationException("property '" + name + "'.required must be a boolean");
        }
        boolean required = requiredNode != null && requiredNode.booleanValue();

        SortedSet<String> enumValues = parseEnum(name, type, node.get("enum"));
        String canonicalDefault = parseDefault(name, type, enumValues, node.get("default"));
        return new PropertyContract(name, type, required, enumValues, canonicalDefault);
    }

    private SortedSet<String> parseEnum(String name, PropertyType type, JsonNode enumNode) {
        if (enumNode == null) {
            return null;
        }
        if (type != PropertyType.STRING) {
            throw new ContractValidationException(
                    "property '" + name + "' declares enum but is not a string");
        }
        if (!enumNode.isArray()) {
            throw new ContractValidationException("property '" + name + "'.enum must be an array");
        }
        SortedSet<String> values = new TreeSet<>();
        for (JsonNode item : enumNode) {
            if (!item.isTextual() || item.textValue().isEmpty()) {
                throw new ContractValidationException(
                        "property '" + name + "'.enum must contain non-empty strings");
            }
            if (!values.add(item.textValue())) {
                throw new ContractValidationException(
                        "property '" + name + "'.enum contains duplicate value: " + item.textValue());
            }
        }
        if (values.isEmpty()) {
            throw new ContractValidationException("property '" + name + "'.enum must not be empty");
        }
        return values;
    }

    private String parseDefault(String name, PropertyType type, SortedSet<String> enumValues, JsonNode defaultNode) {
        if (defaultNode == null) {
            return null;
        }
        String canonical = switch (type) {
            case STRING -> {
                if (!defaultNode.isTextual()) {
                    throw new ContractValidationException(
                            "property '" + name + "'.default must be a string");
                }
                yield quote(defaultNode.textValue());
            }
            case INTEGER -> {
                if (!defaultNode.isIntegralNumber()) {
                    throw new ContractValidationException(
                            "property '" + name + "'.default must be an integer");
                }
                yield canonicalNumber(defaultNode.decimalValue());
            }
            case NUMBER -> {
                if (!defaultNode.isNumber()) {
                    throw new ContractValidationException(
                            "property '" + name + "'.default must be a number");
                }
                yield canonicalNumber(defaultNode.decimalValue());
            }
            case BOOLEAN -> {
                if (!defaultNode.isBoolean()) {
                    throw new ContractValidationException(
                            "property '" + name + "'.default must be a boolean");
                }
                yield defaultNode.booleanValue() ? "true" : "false";
            }
        };
        if (enumValues != null && type == PropertyType.STRING && !enumValues.contains(defaultNode.textValue())) {
            throw new ContractValidationException(
                    "property '" + name + "'.default is not one of its enum values");
        }
        return canonical;
    }

    private String canonicalize(SortedMap<String, PropertyContract> properties) {
        StringBuilder out = new StringBuilder("{\"properties\":{");
        boolean first = true;
        for (PropertyContract property : properties.values()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(property.name())).append(":{");
            out.append("\"type\":").append(quote(property.type().jsonName()));
            out.append(",\"required\":").append(property.required());
            if (property.enumValues() != null) {
                out.append(",\"enum\":[");
                boolean firstValue = true;
                for (String value : property.enumValues()) {
                    if (!firstValue) {
                        out.append(',');
                    }
                    firstValue = false;
                    out.append(quote(value));
                }
                out.append(']');
            }
            if (property.hasDefault()) {
                out.append(",\"default\":").append(property.canonicalDefault());
            }
            out.append('}');
        }
        return out.append("}}").toString();
    }

    private String quote(String value) {
        return writer.writeValueAsString(value);
    }

    private static String canonicalNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    private static String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
