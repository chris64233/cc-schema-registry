package com.chris64233.cc.schemaregistry.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractParserTest {

    private final ContractParser parser = new ContractParser();

    @Test
    void canonicalizationIgnoresPropertyOrderAndWhitespace() {
        ObjectContract a = parser.parse("""
                {"properties": {"name": {"type": "string", "required": true}, "age": {"type": "integer"}}}
                """);
        ObjectContract b = parser.parse("""
                {
                  "properties": {
                    "age":  { "type": "integer" },
                    "name": { "required": true, "type": "string" }
                  }
                }
                """);
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson());
        assertThat(a.contentHash()).isEqualTo(b.contentHash());
    }

    @Test
    void canonicalizationSortsEnumValues() {
        ObjectContract a = parser.parse("""
                {"properties": {"s": {"type": "string", "enum": ["b", "a"]}}}
                """);
        ObjectContract b = parser.parse("""
                {"properties": {"s": {"type": "string", "enum": ["a", "b"]}}}
                """);
        assertThat(a.contentHash()).isEqualTo(b.contentHash());
    }

    @Test
    void rejectsDuplicateProperties() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "string"}, "a": {"type": "integer"}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "date"}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void rejectsEnumOnNonString() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "integer", "enum": ["x"]}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("enum");
    }

    @Test
    void rejectsDuplicateEnumValues() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "string", "enum": ["x", "x"]}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsDefaultWithMismatchedType() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "integer", "default": "x"}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsDefaultOutsideEnum() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "string", "enum": ["x"], "default": "y"}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("not one of its enum values");
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> parser.parse("""
                {"properties": {"a": {"type": "string", "minLength": 3}}}
                """))
                .isInstanceOf(InvalidContractException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    void acceptsFullContract() {
        ObjectContract contract = parser.parse("""
                {"properties": {
                  "id": {"type": "integer", "required": true},
                  "score": {"type": "number", "default": 1.50},
                  "flag": {"type": "boolean", "default": true},
                  "tag": {"type": "string", "enum": ["a", "b"], "default": "a"}
                }}
                """);
        assertThat(contract.properties()).containsOnlyKeys("id", "score", "flag", "tag");
        assertThat(contract.properties().get("score").canonicalDefault()).isEqualTo("1.5");
    }
}
