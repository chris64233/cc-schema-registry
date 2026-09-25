package com.chris64233.cc.schemaregistry.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chris64233.cc.schemaregistry.contract.ContractParser;
import com.chris64233.cc.schemaregistry.contract.ObjectContract;
import com.chris64233.cc.schemaregistry.registry.CompatibilityMode;

class CompatibilityCheckerTest {

    private final ContractParser parser = new ContractParser();
    private final CompatibilityChecker checker = new CompatibilityChecker();

    private List<CompatibilityDiff> check(CompatibilityMode mode, String candidate, String... history) {
        List<VersionedContract> versions = new java.util.ArrayList<>();
        for (int i = 0; i < history.length; i++) {
            versions.add(new VersionedContract(i + 1, parser.parse(history[i])));
        }
        return checker.check(mode, parser.parse(candidate), versions);
    }

    @Test
    void backwardRejectsRemovedRequiredProperty() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED);
    }

    @Test
    void backwardRejectsTypeChangeOfRequiredProperty() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {\"id\": {\"type\": \"string\", \"required\": true}}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_PROPERTY_TYPE_CHANGED);
    }

    @Test
    void backwardRejectsNewRequiredPropertyWithoutDefault() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                        + " \"n\": {\"type\": \"string\", \"required\": true}}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_REQUIRED_PROPERTY_WITHOUT_DEFAULT);
    }

    @Test
    void backwardAcceptsNewRequiredPropertyWithDefault() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                        + " \"n\": {\"type\": \"string\", \"required\": true, \"default\": \"x\"}}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).isEmpty();
    }

    @Test
    void backwardRejectsEnumNarrowing() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\"]}}}",
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\"]}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_ENUM_VALUES_REMOVED);
    }

    @Test
    void backwardAcceptsEnumWidening() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\", \"c\"]}}}",
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\"]}}}");
        assertThat(diffs).isEmpty();
    }

    @Test
    void forwardRejectsNewlyRequiredProperty() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.FORWARD,
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                        + " \"n\": {\"type\": \"string\", \"required\": true, \"default\": \"x\"}}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED);
        assertThat(diffs.get(0).direction()).isEqualTo("FORWARD");
    }

    @Test
    void forwardRejectsEnumWidening() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.FORWARD,
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\", \"c\"]}}}",
                "{\"properties\": {\"s\": {\"type\": \"string\", \"enum\": [\"a\", \"b\"]}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_ENUM_VALUES_REMOVED);
    }

    @Test
    void fullRequiresBothDirections() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.FULL,
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true},"
                        + " \"n\": {\"type\": \"string\", \"required\": true, \"default\": \"x\"}}}",
                "{\"properties\": {\"id\": {\"type\": \"integer\", \"required\": true}}}");
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED);
        assertThat(diffs.get(0).direction()).isEqualTo("FORWARD");
    }

    @Test
    void checksAllHistoricalVersionsAndReportsFirstDeterministicDiff() {
        List<CompatibilityDiff> diffs = check(CompatibilityMode.BACKWARD,
                "{\"properties\": {}}",
                "{\"properties\": {\"b\": {\"type\": \"string\", \"required\": true}}}",
                "{\"properties\": {\"a\": {\"type\": \"string\", \"required\": true},"
                        + " \"b\": {\"type\": \"string\", \"required\": true}}}");
        assertThat(diffs).hasSize(3);
        CompatibilityDiff first = diffs.get(0);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.property()).isEqualTo("b");
        assertThat(first.rule()).isEqualTo(CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED);
    }
}
