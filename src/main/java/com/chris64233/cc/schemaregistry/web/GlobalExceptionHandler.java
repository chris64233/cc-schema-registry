package com.chris64233.cc.schemaregistry.web;

import com.chris64233.cc.schemaregistry.contract.ContractValidationException;
import com.chris64233.cc.schemaregistry.service.ApiException;
import com.chris64233.cc.schemaregistry.service.ErrorCode;
import com.chris64233.cc.schemaregistry.service.IncompatibleContractException;
import java.util.stream.Collectors;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.code().status())
                .body(ErrorResponse.of(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler(ContractValidationException.class)
    public ResponseEntity<ErrorResponse> handleContractInvalid(ContractValidationException exception) {
        return ResponseEntity.status(ErrorCode.CONTRACT_INVALID.status())
                .body(ErrorResponse.of(ErrorCode.CONTRACT_INVALID.name(), exception.getMessage()));
    }

    @ExceptionHandler(IncompatibleContractException.class)
    public ResponseEntity<ErrorResponse> handleIncompatible(IncompatibleContractException exception) {
        return ResponseEntity.status(ErrorCode.CONTRACT_INCOMPATIBLE.status())
                .body(new ErrorResponse(ErrorCode.CONTRACT_INCOMPATIBLE.name(),
                        exception.getMessage(), exception.firstDiff(), exception.diffs()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .sorted()
                .collect(Collectors.joining(", ", "invalid fields: ", ""));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.name(), "request body is not readable"));
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class})
    public ResponseEntity<ErrorResponse> handleLockFailure(RuntimeException exception) {
        return ResponseEntity.status(ErrorCode.CONCURRENT_MODIFICATION.status())
                .body(ErrorResponse.of(ErrorCode.CONCURRENT_MODIFICATION.name(),
                        "subject is being updated concurrently, please retry"));
    }
}
