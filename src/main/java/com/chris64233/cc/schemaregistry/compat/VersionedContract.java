package com.chris64233.cc.schemaregistry.compat;

import com.chris64233.cc.schemaregistry.contract.ObjectContract;

public record VersionedContract(int version, ObjectContract contract) {
}
