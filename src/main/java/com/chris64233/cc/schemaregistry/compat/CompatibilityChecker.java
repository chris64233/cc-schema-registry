package com.chris64233.cc.schemaregistry.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.chris64233.cc.schemaregistry.contract.ObjectContract;
import com.chris64233.cc.schemaregistry.contract.PropertyContract;
import com.chris64233.cc.schemaregistry.registry.CompatibilityMode;

/**
 * 兼容性检查。BACKWARD：新契约能读取旧数据；FORWARD：反向应用同一组规则；
 * FULL：两个方向同时满足。始终针对主题全部历史版本检查。
 */
@Component
public class CompatibilityChecker {

    public List<CompatibilityDiff> check(CompatibilityMode mode, ObjectContract candidate,
            List<VersionedContract> history) {
        List<CompatibilityDiff> diffs = new ArrayList<>();
        for (VersionedContract old : history) {
            if (mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL) {
                diffs.addAll(checkBackward(candidate, old.contract(), old.version(), "BACKWARD"));
            }
            if (mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL) {
                diffs.addAll(checkBackward(old.contract(), candidate, old.version(), "FORWARD"));
            }
        }
        Collections.sort(diffs);
        return diffs;
    }

    /**
     * 校验 newContract 能否读取 oldContract 写入的数据。
     */
    private List<CompatibilityDiff> checkBackward(ObjectContract newContract, ObjectContract oldContract,
            int oldVersion, String direction) {
        List<CompatibilityDiff> diffs = new ArrayList<>();

        for (PropertyContract oldProperty : oldContract.properties().values()) {
            PropertyContract newProperty = newContract.properties().get(oldProperty.name());
            if (oldProperty.required()) {
                if (newProperty == null) {
                    diffs.add(new CompatibilityDiff(oldVersion, direction,
                            CompatibilityDiff.RULE_REQUIRED_PROPERTY_REMOVED, oldProperty.name(),
                            "required property '" + oldProperty.name() + "' from version " + oldVersion
                                    + " is removed"));
                } else if (newProperty.type() != oldProperty.type()) {
                    diffs.add(new CompatibilityDiff(oldVersion, direction,
                            CompatibilityDiff.RULE_PROPERTY_TYPE_CHANGED, oldProperty.name(),
                            "required property '" + oldProperty.name() + "' changes type from '"
                                    + oldProperty.type().jsonName() + "' to '" + newProperty.type().jsonName()
                                    + "'"));
                }
            }
            if (newProperty != null && oldProperty.enumValues() != null && newProperty.enumValues() != null
                    && !newProperty.enumValues().containsAll(oldProperty.enumValues())) {
                TreeSet<String> missing = new TreeSet<>(oldProperty.enumValues());
                missing.removeAll(newProperty.enumValues());
                diffs.add(new CompatibilityDiff(oldVersion, direction,
                        CompatibilityDiff.RULE_ENUM_VALUES_REMOVED, oldProperty.name(),
                        "enum of property '" + oldProperty.name() + "' misses values " + missing));
            }
        }

        for (PropertyContract newProperty : newContract.properties().values()) {
            PropertyContract oldProperty = oldContract.properties().get(newProperty.name());
            boolean newlyRequired = newProperty.required()
                    && (oldProperty == null || !oldProperty.required());
            if (newlyRequired && !newProperty.hasDefault()) {
                diffs.add(new CompatibilityDiff(oldVersion, direction,
                        CompatibilityDiff.RULE_REQUIRED_PROPERTY_WITHOUT_DEFAULT, newProperty.name(),
                        "newly required property '" + newProperty.name() + "' must declare a default value"));
            }
        }

        return diffs;
    }
}
