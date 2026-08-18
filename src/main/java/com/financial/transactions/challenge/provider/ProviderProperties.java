package com.financial.transactions.challenge.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record ProviderProperties(
        Provider provider,
        ProviderExecutor providerExecutor
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
}
