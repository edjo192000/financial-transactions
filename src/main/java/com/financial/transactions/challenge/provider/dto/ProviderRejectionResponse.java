package com.financial.transactions.challenge.provider.dto;

public record ProviderRejectionResponse(
        String status,
        String code,
        String message
) {
}
