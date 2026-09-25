package com.chris64233.cc.schemaregistry.contract;

import com.chris64233.cc.schemaregistry.domain.CompatibilityMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 针对主题的全部历史版本执行确定性兼容性检查。
 *
 * <p>BACKWARD：新契约作为 reader、历史版本作为 writer；FORWARD 方向相反；
 * FULL 同时执行两个方向。差异按（历史版本号、属性名、规则码）排序。
 */
@Component
public class CompatibilityChecker {

    public record VersionedContract(int version, ObjectContract contract) {
    }

    public List<CompatibilityDiff> check(
            ObjectContract candidate, List<VersionedContract> history, CompatibilityMode mode) {
        List<CompatibilityDiff> diffs = new ArrayList<>();
        for (VersionedContract historical : history) {
            if (mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL) {
                checkReadableBy(candidate, "new contract", historical.contract(),
                        "version " + historical.version(), historical.version(), diffs);
            }
            if (mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL) {
                checkReadableBy(historical.contract(), "version " + historical.version(),
                        candidate, "new contract", historical.version(), diffs);
            }
        }
        Collections.sort(diffs);
        return diffs;
    }

    /**
     * 检查 reader 契约能否读取 writer 契约写出的数据。
     */
    private void checkReadableBy(
            ObjectContract reader,
            String readerLabel,
            ObjectContract writerContract,
            String writerLabel,
            int againstVersion,
            List<CompatibilityDiff> diffs) {
        for (PropertyContract writerProperty : writerContract.properties().values()) {
            PropertyContract readerProperty = reader.properties().get(writerProperty.name());
            if (writerProperty.required()) {
                if (readerProperty == null) {
                    diffs.add(new CompatibilityDiff(againstVersion,
                            CompatibilityDiff.REQUIRED_PROPERTY_REMOVED, writerProperty.name(),
                            "required property '" + writerProperty.name() + "' of " + writerLabel
                                    + " is missing in " + readerLabel));
                } else if (readerProperty.type() != writerProperty.type()) {
                    diffs.add(new CompatibilityDiff(againstVersion,
                            CompatibilityDiff.PROPERTY_TYPE_CHANGED, writerProperty.name(),
                            "required property '" + writerProperty.name() + "' of " + writerLabel
                                    + " changed type from "
                                    + writerProperty.type().jsonName() + " to "
                                    + readerProperty.type().jsonName() + " in " + readerLabel));
                }
            }
            if (readerProperty != null
                    && readerProperty.type() == PropertyType.STRING
                    && writerProperty.type() == PropertyType.STRING
                    && readerProperty.enumValues() != null) {
                if (writerProperty.enumValues() == null) {
                    diffs.add(new CompatibilityDiff(againstVersion,
                            CompatibilityDiff.ENUM_NEWLY_RESTRICTED, writerProperty.name(),
                            "property '" + writerProperty.name() + "' of " + writerLabel
                                    + " is unrestricted but " + readerLabel + " restricts it to an enum"));
                } else {
                    SortedSet<String> missing = new TreeSet<>(writerProperty.enumValues());
                    missing.removeAll(readerProperty.enumValues());
                    if (!missing.isEmpty()) {
                        diffs.add(new CompatibilityDiff(againstVersion,
                                CompatibilityDiff.ENUM_VALUES_REMOVED, writerProperty.name(),
                                "enum of property '" + writerProperty.name() + "' in " + readerLabel
                                        + " misses values " + missing + " required by " + writerLabel));
                    }
                }
            }
        }

        for (PropertyContract readerProperty : reader.properties().values()) {
            PropertyContract writerProperty = writerContract.properties().get(readerProperty.name());
            boolean newlyRequired = readerProperty.required()
                    && (writerProperty == null || !writerProperty.required());
            if (newlyRequired && !readerProperty.hasDefault()) {
                diffs.add(new CompatibilityDiff(againstVersion,
                        CompatibilityDiff.REQUIRED_PROPERTY_MISSING_DEFAULT, readerProperty.name(),
                        "property '" + readerProperty.name() + "' newly required by " + readerLabel
                                + " has no default value (data written by " + writerLabel
                                + " may lack it)"));
            }
        }
    }
}
