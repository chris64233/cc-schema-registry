package com.chris64233.cc.schemaregistry.registry;

public final class ErrorCodes {

    public static final String SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    public static final String SUBJECT_EXISTS = "SUBJECT_EXISTS";
    public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
    public static final String INVALID_CONTRACT = "INVALID_CONTRACT";
    public static final String CONTRACT_INCOMPATIBLE = "CONTRACT_INCOMPATIBLE";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    private ErrorCodes() {
    }
}
