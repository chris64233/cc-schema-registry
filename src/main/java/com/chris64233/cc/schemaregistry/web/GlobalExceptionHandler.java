package com.chris64233.cc.schemaregistry.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chris64233.cc.schemaregistry.contract.InvalidContractException;
import com.chris64233.cc.schemaregistry.registry.ApiException;
import com.chris64233.cc.schemaregistry.registry.ErrorCodes;
import com.chris64233.cc.schemaregistry.registry.IncompatibleContractException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Dto.ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new Dto.ErrorResponse(ex.code(), ex.getMessage(), null, null));
    }

    @ExceptionHandler(InvalidContractException.class)
    public ResponseEntity<Dto.ErrorResponse> handleInvalidContract(InvalidContractException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dto.ErrorResponse(ErrorCodes.INVALID_CONTRACT, ex.getMessage(), ex.violations(), null));
    }

    @ExceptionHandler(IncompatibleContractException.class)
    public ResponseEntity<Dto.ErrorResponse> handleIncompatible(IncompatibleContractException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new Dto.ErrorResponse(ErrorCodes.CONTRACT_INCOMPATIBLE, ex.getMessage(), null, ex.diffs()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dto.ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .sorted()
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dto.ErrorResponse(ErrorCodes.INVALID_REQUEST, "request validation failed", details,
                        null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dto.ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dto.ErrorResponse(ErrorCodes.INVALID_REQUEST, "request body is not readable", null,
                        null));
    }
}
