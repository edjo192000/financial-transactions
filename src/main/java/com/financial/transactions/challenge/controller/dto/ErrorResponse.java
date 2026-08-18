package com.financial.transactions.challenge.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ErrorResponse(
        @Schema(description = "Machine-readable error code.", example = "INVALID_AMOUNT")
        String code,
        String message,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
