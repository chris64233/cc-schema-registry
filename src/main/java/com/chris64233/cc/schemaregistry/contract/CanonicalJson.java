package com.chris64233.cc.schemaregistry.contract;

import tools.jackson.databind.json.JsonMapper;

final class CanonicalJson {

    private CanonicalJson() {
    }

    static String quote(String value) {
        return JsonMapper.shared().writeValueAsString(value);
    }
}
