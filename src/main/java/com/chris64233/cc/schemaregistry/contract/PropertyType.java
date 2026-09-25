package com.chris64233.cc.schemaregistry.contract;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum PropertyType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN;

    public String jsonName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PropertyType> fromJsonName(String name) {
        return Arrays.stream(values())
                .filter(type -> type.jsonName().equals(name))
                .findFirst();
    }
}
