package com.chris64233.cc.schemaregistry.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.schemaregistry.contract.CompatibilityChecker.VersionedContract;
import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityCheckerTest {

    private final ContractParser parser = new ContractParser();
    private final CompatibilityChecker checker = new CompatibilityChecker();

    private ObjectContract contract(String json) {
        return parser.parse(json);
    }

    private List<CompatibilityDiff> backward(String oldJson, String newJson) {
        return checker.check(contract(newJson),
                List.of(new VersionedContract(1, contract(oldJson))), CompatibilityMode.BACKWARD);
    }

    @Test
    void backwardRejectsRemovedOrRetypedRequiredProperty() {
        String oldContract = "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}";

        List<CompatibilityDiff> removed = backward(oldContract, "{\"properties\":{}}");
        assertThat(removed).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);

        List<CompatibilityDiff> retyped = backward(oldContract,
                "{\"properties\":{\"id\":{\"type\":\"string\",\"required\":true}}}");
        assertThat(retyped).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.PROPERTY_TYPE_CHANGED);
    }

    @Test
    void backwardAllowsRemovingOptionalProperty() {
        List<CompatibilityDiff> diffs = backward(
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},\"opt\":{\"type\":\"string\"}}}",
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}");
        assertThat(diffs).isEmpty();
    }

    @Test
    void backwardRequiresDefaultForNewlyRequiredProperty() {
        String oldContract = "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}";

        List<CompatibilityDiff> withoutDefault = backward(oldContract,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                        + "\"code\":{\"type\":\"string\",\"required\":true}}}");
        assertThat(withoutDefault).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.REQUIRED_PROPERTY_MISSING_DEFAULT);

        List<CompatibilityDiff> withDefault = backward(oldContract,
                "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                        + "\"code\":{\"type\":\"string\",\"required\":true,\"default\":\"N/A\"}}}");
        assertThat(withDefault).isEmpty();
    }

    @Test
    void backwardRequiresEnumSuperset() {
        String oldContract = "{\"properties\":{\"s\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}}}";

        assertThat(backward(oldContract,
                "{\"properties\":{\"s\":{\"type\":\"string\",\"enum\":[\"a\",\"b\",\"c\"]}}}"))
                .isEmpty();
        assertThat(backward(oldContract,
                "{\"properties\":{\"s\":{\"type\":\"string\"}}}"))
                .isEmpty();
        assertThat(backward(oldContract,
                "{\"properties\":{\"s\":{\"type\":\"string\",\"enum\":[\"a\"]}}}"))
                .extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.ENUM_VALUES_REMOVED);
        assertThat(backward("{\"properties\":{\"s\":{\"type\":\"string\"}}}",
                "{\"properties\":{\"s\":{\"type\":\"string\",\"enum\":[\"a\"]}}}"))
                .extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.ENUM_NEWLY_RESTRICTED);
    }

    @Test
    void forwardChecksReversedDirection() {
        String oldContract = "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}";

        // 新契约新增的 required 属性在旧契约中不存在：旧 reader 无法依赖该字段。
        List<CompatibilityDiff> diffs = checker.check(
                contract("{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                        + "\"code\":{\"type\":\"string\",\"required\":true,\"default\":\"x\"}}}"),
                List.of(new VersionedContract(1, contract(oldContract))), CompatibilityMode.FORWARD);
        assertThat(diffs).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);
        assertThat(diffs.getFirst().property()).isEqualTo("code");

        // 旧契约的 required 在新契约中变为可选且旧契约无默认值：FORWARD 不兼容。
        List<CompatibilityDiff> demoted = checker.check(
                contract("{\"properties\":{\"id\":{\"type\":\"integer\"}}}"),
                List.of(new VersionedContract(1, contract(oldContract))), CompatibilityMode.FORWARD);
        assertThat(demoted).extracting(CompatibilityDiff::rule)
                .containsExactly(CompatibilityDiff.REQUIRED_PROPERTY_MISSING_DEFAULT);
    }

    @Test
    void fullRequiresBothDirections() {
        String oldContract = "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true}}}";
        String newContract = "{\"properties\":{\"id\":{\"type\":\"integer\",\"required\":true},"
                + "\"code\":{\"type\":\"string\",\"required\":true,\"default\":\"x\"}}}";

        assertThat(checker.check(contract(newContract),
                List.of(new VersionedContract(1, contract(oldContract))), CompatibilityMode.BACKWARD))
                .isEmpty();
        assertThat(checker.check(contract(newContract),
                List.of(new VersionedContract(1, contract(oldContract))), CompatibilityMode.FULL))
                .isNotEmpty();
    }

    @Test
    void checksAllHistoricalVersionsWithDeterministicOrder() {
        List<VersionedContract> history = List.of(
                new VersionedContract(1, contract(
                        "{\"properties\":{\"a\":{\"type\":\"string\",\"required\":true}}}")),
                new VersionedContract(2, contract(
                        "{\"properties\":{\"a\":{\"type\":\"string\",\"required\":true},"
                                + "\"b\":{\"type\":\"integer\",\"required\":true}}}")));
        List<CompatibilityDiff> diffs = checker.check(contract("{\"properties\":{}}"),
                history, CompatibilityMode.BACKWARD);
        assertThat(diffs).extracting(CompatibilityDiff::againstVersion)
                .containsExactly(1, 2, 2);
        assertThat(diffs).extracting(CompatibilityDiff::property)
                .containsExactly("a", "a", "b");
        assertThat(diffs.getFirst().rule()).isEqualTo(CompatibilityDiff.REQUIRED_PROPERTY_REMOVED);
    }
}
