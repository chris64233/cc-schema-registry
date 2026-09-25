package com.chris64233.cc.schemaregistry.service;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    CONTRACT_INVALID(HttpStatus.BAD_REQUEST),
    SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND),
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    SUBJECT_ALREADY_EXISTS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    CONTRACT_INCOMPATIBLE(HttpStatus.UNPROCESSABLE_ENTITY);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
