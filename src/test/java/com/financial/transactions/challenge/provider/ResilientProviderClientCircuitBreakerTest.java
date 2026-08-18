package com.financial.transactions.challenge.provider;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(
        classes = HttpTransactionProviderTest.ProviderTestApplication.class,
        properties = {
                "app.provider.base-url=http://localhost:9091/provider",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "resilience4j.circuitbreaker.instances.transactionProvider.sliding-window-size=4",
                "resilience4j.circuitbreaker.instances.transactionProvider.minimum-number-of-calls=4",
                "resilience4j.circuitbreaker.instances.transactionProvider.wait-duration-in-open-state=10s",
                "resilience4j.retry.instances.transactionProvider.max-attempts=1"
        }
)
@DisplayName("ResilientProviderClient circuit breaker")
class ResilientProviderClientCircuitBreakerTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(9091))
            .build();

    @Autowired
    private HttpTransactionProvider provider;

    @Nested
    @DisplayName("Given enough consecutive failures have opened the circuit")
    class CircuitOpensAfterConsecutiveFailures {

        @Test
        @DisplayName("Given the circuit is open, when calling again, then it fails fast without hitting the network")
        void failsFastWithoutHittingNetwork() {
            // given
            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute")).willReturn(aResponse().withStatus(503)));

            for (int i = 0; i < 4; i++) {
                catchThrowable(() -> provider.execute("acc-cb", TransactionType.CREDIT, new Money(new BigDecimal("10.00"), "MXN")));
            }

            // when
            Throwable thrown = catchThrowable(() ->
                    provider.execute("acc-cb", TransactionType.CREDIT, new Money(new BigDecimal("10.00"), "MXN")));

            // then
            assertThat(thrown).isInstanceOf(ProviderCommunicationException.class);
            wireMock.verify(4, postRequestedFor(urlEqualTo("/provider/v1/execute")));
        }
    }
}
