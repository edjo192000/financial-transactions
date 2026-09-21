package com.financial.transactions.challenge.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record ProviderProperties(
        Provider provider,
        ProviderExecutor providerExecutor,
        Idempotency idempotency
) {

    public record Provider(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout
    ) {
    }

    public record ProviderExecutor(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            Duration futureTimeout
    ) {
    }

    /**
     * Bounds how long a caller who lost the idempotency-key reservation race waits for the
     * winning request to resolve it, before giving up and responding 202 Accepted instead of
     * blocking a Tomcat thread indefinitely.
     */
    public record Idempotency(
            Duration pollInterval,
            int pollAttempts
    ) {
    }
}
