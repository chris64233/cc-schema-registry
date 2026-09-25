package com.chris64233.cc.schemaregistry.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 解析并校验对象契约，产出规范化模型。拒绝重复属性、未知类型和不一致声明。
 */
@Component
public class ContractParser {

    private static final Set<String> ROOT_KEYS = Set.of("properties");
    private static final Set<String> PROPERTY_KEYS = Set.of("type", "required", "enum", "default");

    private final JsonMapper strictMapper = JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .build();

    public ObjectContract parse(String raw) {
        JsonNode root;
        try {
            root = strictMapper.readTree(raw);
        } catch (JacksonException e) {
            String detail = e.getOriginalMessage() == null ? "malformed JSON" : e.getOriginalMessage();
            if (detail.startsWith("Duplicate")) {
                detail = "duplicate property declaration: " + detail;
            }
            throw new InvalidContractException(List.of(detail));
        }

        List<String> violations = new ArrayList<>();
        TreeMap<String, PropertyContract> properties = new TreeMap<>();

        if (!root.isObject()) {
            violations.add("contract root must be a JSON object");
        } else {
            for (String key : root.propertyNames()) {
                if (!ROOT_KEYS.contains(key)) {
                    violations.add("contract contains unknown field '" + key + "'");
                }
            }
            JsonNode propertiesNode = root.get("properties");
            if (propertiesNode == null) {
                violations.add("contract must declare 'properties'");
            } else if (!propertiesNode.isObject()) {
                violations.add("'properties' must be a JSON object");
            } else {
                for (String name : propertiesNode.propertyNames()) {
                    PropertyContract property = parseProperty(name, propertiesNode.get(name), violations);
                    if (property != null) {
                        properties.put(name, property);
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            violations.sort(String::compareTo);
            throw new InvalidContractException(violations);
        }
        return new ObjectContract(properties);
    }

    private PropertyContract parseProperty(String name, JsonNode node, List<String> violations) {
        if (node == null || !node.isObject()) {
            violations.add("property '" + name + "' must be a JSON object");
            return null;
        }
        for (String key : node.propertyNames()) {
            if (!PROPERTY_KEYS.contains(key)) {
                violations.add("property '" + name + "' contains unknown field '" + key + "'");
            }
        }

        JsonNode typeNode = node.get("type");
        PropertyType type = null;
        if (typeNode == null || !typeNode.isString()) {
            violations.add("property '" + name + "' must declare a string 'type'");
        } else {
            type = PropertyType.fromJsonName(typeNode.stringValue()).orElse(null);
            if (type == null) {
                violations.add("property '" + name + "' declares unknown type '" + typeNode.stringValue() + "'");
            }
        }

        JsonNode requiredNode = node.get("required");
        boolean required = false;
        if (requiredNode != null) {
            if (requiredNode.isBoolean()) {
                required = requiredNode.booleanValue();
            } else {
                violations.add("property '" + name + "' field 'required' must be a boolean");
            }
        }

        TreeSet<String> enumValues = null;
        JsonNode enumNode = node.get("enum");
        if (enumNode != null) {
            enumValues = parseEnum(name, type, enumNode, violations);
        }

        String canonicalDefault = null;
        JsonNode defaultNode = node.get("default");
        if (defaultNode != null && type != null) {
            canonicalDefault = parseDefault(name, type, enumValues, defaultNode, violations);
        } else if (defaultNode != null) {
            violations.add("property '" + name + "' declares 'default' without a valid type");
        }

        if (type == null) {
            return null;
        }
        return new PropertyContract(name, type, required, enumValues, canonicalDefault);
    }

    private TreeSet<String> parseEnum(String name, PropertyType type, JsonNode enumNode, List<String> violations) {
        if (type != null && type != PropertyType.STRING) {
            violations.add("property '" + name + "' declares 'enum' but only string properties may declare enum");
            return null;
        }
        if (!enumNode.isArray() || enumNode.size() == 0) {
            violations.add("property '" + name + "' field 'enum' must be a non-empty array");
            return null;
        }
        TreeSet<String> values = new TreeSet<>();
        for (JsonNode element : enumNode) {
            if (!element.isString()) {
                violations.add("property '" + name + "' field 'enum' must contain only strings");
                return null;
            }
            values.add(element.stringValue());
        }
        if (values.size() != enumNode.size()) {
            violations.add("property '" + name + "' field 'enum' contains duplicate values");
            return null;
        }
        return values;
    }

    private String parseDefault(String name, PropertyType type, TreeSet<String> enumValues,
            JsonNode defaultNode, List<String> violations) {
        boolean matches = switch (type) {
            case STRING -> defaultNode.isString();
            case INTEGER -> defaultNode.isIntegralNumber();
            case NUMBER -> defaultNode.isNumber();
            case BOOLEAN -> defaultNode.isBoolean();
        };
        if (!matches) {
            violations.add("property '" + name + "' default value does not match type '" + type.jsonName() + "'");
            return null;
        }
        String canonical = switch (type) {
            case STRING -> CanonicalJson.quote(defaultNode.stringValue());
            case INTEGER -> defaultNode.bigIntegerValue().toString();
            case NUMBER -> canonicalNumber(defaultNode.decimalValue());
            case BOOLEAN -> Boolean.toString(defaultNode.booleanValue());
        };
        if (type == PropertyType.STRING && enumValues != null && !enumValues.contains(defaultNode.stringValue())) {
            violations.add("property '" + name + "' default value is not one of its enum values");
            return null;
        }
        return canonical;
    }

    private static String canonicalNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
