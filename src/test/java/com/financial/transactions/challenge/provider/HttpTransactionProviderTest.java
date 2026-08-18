package com.financial.transactions.challenge.provider;

import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.domain.exception.ProviderCommunicationException;
import com.financial.transactions.challenge.domain.exception.ProviderRejectedException;
import com.financial.transactions.challenge.service.port.ProviderResult;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Each nested class gets its own Spring context (distinct {@code app.provider.base-url})
 * and its own WireMock port, so connection-pool/scenario state from one behavior never
 * leaks into another.
 */
@DisplayName("HttpTransactionProvider")
class HttpTransactionProviderTest {

    private static final String AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    @Nested
    @DisplayName("Given the provider approves the request")
    @SpringBootTest(
            classes = ProviderTestApplication.class,
            properties = {
                    "app.provider.base-url=http://localhost:9090/provider",
                    AUTOCONFIGURE_EXCLUDE
            }
    )
    class SuccessfulExecution {

        @RegisterExtension
        WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig().port(9090))
                .build();

        @Autowired
        private HttpTransactionProvider provider;

        @Test
        @DisplayName("Given the provider approves on the first attempt, when executing, then returns the result without retrying")
        void returnsResultOnFirstAttempt() {
            // given
            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .willReturn(okJson("""
                            {"transactionId":"prov-1","status":"APPROVED","balance":100.00,"executedAt":"2026-01-01T00:00:00Z"}
                            """)));

            // when
            ProviderResult result = provider.execute("acc-1", TransactionType.CREDIT, new Money(new BigDecimal("10.00"), "MXN"));

            // then
            assertThat(result.providerTransactionId()).isEqualTo("prov-1");
            assertThat(result.balanceAfter()).isEqualByComparingTo("100.00");
            wireMock.verify(1, postRequestedFor(urlEqualTo("/provider/v1/execute")));
        }
    }

    @Nested
    @DisplayName("Given the provider fails transiently")
    @SpringBootTest(
            classes = ProviderTestApplication.class,
            properties = {
                    "app.provider.base-url=http://localhost:9092/provider",
                    AUTOCONFIGURE_EXCLUDE
            }
    )
    class RetryOnTransientFailure {

        @RegisterExtension
        WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig().port(9092))
                .build();

        @Autowired
        private HttpTransactionProvider provider;

        @Test
        @DisplayName("Given the provider fails twice then succeeds, when executing, then it retries and eventually succeeds")
        void retriesUntilSuccess() {
            // given
            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .inScenario("retry-then-succeed")
                    .whenScenarioStateIs(STARTED)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("second-attempt"));

            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .inScenario("retry-then-succeed")
                    .whenScenarioStateIs("second-attempt")
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo("third-attempt"));

            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .inScenario("retry-then-succeed")
                    .whenScenarioStateIs("third-attempt")
                    .willReturn(okJson("""
                            {"transactionId":"prov-2","status":"APPROVED","balance":200.00,"executedAt":"2026-01-01T00:00:00Z"}
                            """)));

            // when
            ProviderResult result = provider.execute("acc-2", TransactionType.DEBIT, new Money(new BigDecimal("20.00"), "MXN"));

            // then
            assertThat(result.providerTransactionId()).isEqualTo("prov-2");
            wireMock.verify(3, postRequestedFor(urlEqualTo("/provider/v1/execute")));
        }

        @Test
        @DisplayName("Given the provider always fails with a server error, when executing, then it propagates the failure after exhausting retries")
        void propagatesFailureAfterExhaustingRetries() {
            // given
            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .willReturn(aResponse().withStatus(503)));

            // when
            Throwable thrown = catchThrowable(() ->
                    provider.execute("acc-3", TransactionType.CREDIT, new Money(new BigDecimal("30.00"), "MXN")));

            // then
            assertThat(thrown).isInstanceOf(ProviderCommunicationException.class);
            wireMock.verify(3, postRequestedFor(urlEqualTo("/provider/v1/execute")));
        }
    }

    @Nested
    @DisplayName("Given the provider rejects the request")
    @SpringBootTest(
            classes = ProviderTestApplication.class,
            properties = {
                    "app.provider.base-url=http://localhost:9093/provider",
                    AUTOCONFIGURE_EXCLUDE
            }
    )
    class RejectionByProvider {

        @RegisterExtension
        WireMockExtension wireMock = WireMockExtension.newInstance()
                .options(wireMockConfig().port(9093))
                .build();

        @Autowired
        private HttpTransactionProvider provider;

        @Test
        @DisplayName("Given the provider rejects with insufficient funds, when executing, then it fails immediately without retrying")
        void failsImmediatelyWithoutRetrying() {
            // given
            wireMock.stubFor(post(urlEqualTo("/provider/v1/execute"))
                    .willReturn(aResponse().withStatus(422)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"status":"REJECTED","code":"INSUFFICIENT_FUNDS","message":"Account balance too low"}
                                    """)));

            // when
            Throwable thrown = catchThrowable(() ->
                    provider.execute("acc-4", TransactionType.DEBIT, new Money(new BigDecimal("1000.00"), "MXN")));

            // then
            assertThat(thrown).isInstanceOf(ProviderRejectedException.class);
            assertThat(((ProviderRejectedException) thrown).code()).isEqualTo("INSUFFICIENT_FUNDS");
            wireMock.verify(1, postRequestedFor(urlEqualTo("/provider/v1/execute")));
        }
    }

    /**
     * Deliberately NOT a {@code @SpringBootApplication} (which is
     * {@code @SpringBootConfiguration}-meta-annotated): a second such class on the test
     * classpath confuses other bare {@code @SpringBootTest}s' primary-configuration
     * auto-detection elsewhere in the module (e.g. {@code ChallengeApplicationTests}).
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = ProviderExecutorConfig.class)
    static class ProviderTestApplication {
    }
}
