package com.chris64233.cc.schemaregistry.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractParserTest {

    private final ContractParser parser = new ContractParser();

    @Test
    void normalizesPropertyOrderAndWhitespace() {
        ObjectContract first = parser.parse("""
                {"properties": {"b": {"type": "integer"}, "a": {"type": "string", "required": true}}}
                """);
        ObjectContract second = parser.parse("""
                {
                  "properties": {
                    "a": {"required": true, "type": "string"},
                    "b": {"type": "integer"}
                  }
                }
                """);
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void normalizesEnumOrderAndNumberDefaults() {
        ObjectContract first = parser.parse(
                "{\"properties\":{\"s\":{\"type\":\"string\",\"enum\":[\"b\",\"a\"]},"
                        + "\"n\":{\"type\":\"number\",\"default\":1.0}}}");
        ObjectContract second = parser.parse(
                "{\"properties\":{\"n\":{\"type\":\"number\",\"default\":1},"
                        + "\"s\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}}}");
        assertThat(second.contentHash()).isEqualTo(first.contentHash());
        assertThat(first.canonicalJson()).contains("\"default\":1");
    }

    @Test
    void rejectsDuplicateProperties() {
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"string\"},\"a\":{\"type\":\"integer\"}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"date\"}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void rejectsEnumOnNonString() {
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"integer\",\"enum\":[\"x\"]}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("not a string");
    }

    @Test
    void rejectsInconsistentDeclarations() {
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"integer\",\"default\":\"x\"}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("default");
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"integer\",\"default\":1.5}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("integer");
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"string\",\"enum\":[\"x\"],\"default\":\"y\"}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("enum");
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"string\",\"enum\":[\"x\",\"x\"]}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> parser.parse(
                "{\"properties\":{\"a\":{\"type\":\"string\",\"label\":\"x\"}}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    void rejectsMalformedDocuments() {
        assertThatThrownBy(() -> parser.parse("not json"))
                .isInstanceOf(ContractValidationException.class);
        assertThatThrownBy(() -> parser.parse("[1,2]"))
                .isInstanceOf(ContractValidationException.class);
        assertThatThrownBy(() -> parser.parse("{\"other\":{}}"))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("unknown contract field");
        assertThatThrownBy(() -> parser.parse("{\"properties\":[]}"))
                .isInstanceOf(ContractValidationException.class);
    }
}
