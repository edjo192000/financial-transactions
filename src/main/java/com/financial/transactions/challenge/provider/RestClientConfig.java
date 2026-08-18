package com.financial.transactions.challenge.provider;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RestClientConfig {

    @Bean
    public RestClient providerRestClient(ProviderProperties properties) {
        ProviderProperties.Provider config = properties.provider();

        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(config.connectTimeout())
                .withReadTimeout(config.readTimeout());

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
                .withConnectionManagerCustomizer(manager -> manager.setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS))
                .build(settings);

        return RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
