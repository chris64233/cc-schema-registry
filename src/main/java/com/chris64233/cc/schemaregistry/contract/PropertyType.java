package com.chris64233.cc.schemaregistry.contract;

import java.util.Arrays;
import java.util.Optional;

public enum PropertyType {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean");

    private final String jsonName;

    PropertyType(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static Optional<PropertyType> fromJsonName(String name) {
        return Arrays.stream(values()).filter(t -> t.jsonName.equals(name)).findFirst();
    }
}
