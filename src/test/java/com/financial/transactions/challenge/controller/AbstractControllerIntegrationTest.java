package com.financial.transactions.challenge.controller;

import com.financial.transactions.challenge.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Full end-to-end stack: real controller, real service, real repository (Testcontainers
 * Postgres, inherited from {@link AbstractIntegrationTest}), and a real
 * {@code HttpTransactionProvider} pointed at a WireMock instance standing in for the
 * external provider. {@code application-test.yml} (activated by the "test" profile)
 * shrinks retry/timeout durations so provider-failure tests don't burn real seconds.
 *
 * <p>The WireMock port is fixed rather than dynamic: JUnit 5 restarts a static
 * {@code @RegisterExtension} extension (with a fresh port, if dynamic) once per
 * {@code @Nested} class, but the Spring context — and the {@code RestClient} bean baked
 * with the base-url from {@link #overrideProviderBaseUrl} — is created once and cached
 * across every {@code @Nested} class in the subclass. A dynamic port would leave every
 * class after the first pointing at a WireMock instance that no longer exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractControllerIntegrationTest extends AbstractIntegrationTest {

    private static final int WIREMOCK_PORT = 9095;

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().port(WIREMOCK_PORT))
            .build();

    @DynamicPropertySource
    static void overrideProviderBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", () -> "http://localhost:" + WIREMOCK_PORT + "/provider");
    }
}
