package com.financial.transactions.challenge.controller;

import com.financial.transactions.challenge.controller.dto.ErrorResponse;
import com.financial.transactions.challenge.domain.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    //Business rules
    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAmount(InvalidAmountException ex) {
        return badRequest("INVALID_AMOUNT", ex.getMessage());
    }

    @ExceptionHandler(DebitLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleDebitLimitExceeded(DebitLimitExceededException ex) {
        return badRequest("DEBIT_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return badRequest("UNSUPPORTED_CURRENCY", ex.getMessage());
    }

    // Idempotency
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_CONFLICT", ex.getMessage()));
    }

    // Provider communication issues
    @ExceptionHandler(ProviderTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleProviderTimeout(ProviderTimeoutException ex) {
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT) // 504
                .body(ErrorResponse.of("PROVIDER_TIMEOUT", ex.getMessage()));
    }

    @ExceptionHandler(ProviderCommunicationException.class)
    public ResponseEntity<ErrorResponse> handleProviderCommunication(ProviderCommunicationException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY) // 502
                .body(ErrorResponse.of("PROVIDER_UNAVAILABLE", ex.getMessage()));
    }

    // Validation request
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return badRequest("VALIDATION_ERROR", message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return badRequest("MISSING_HEADER", "Required header '" + ex.getHeaderName() + "' is missing");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue());
    }
    // Generic fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> badRequest(String code, String message) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(code, message));
    }
}
