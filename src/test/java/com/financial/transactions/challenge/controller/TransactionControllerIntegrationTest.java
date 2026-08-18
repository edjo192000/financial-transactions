package com.financial.transactions.challenge.controller;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.financial.transactions.challenge.controller.dto.ExecuteTransactionRequest;
import com.financial.transactions.challenge.domain.Money;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.port.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TransactionController (full stack)")
class TransactionControllerIntegrationTest extends AbstractControllerIntegrationTest {

    private static final String DEFAULT_CURRENCY = "MXN";
    private static final String PROVIDER_PATH = "/provider/v1/execute";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DELETE FROM transactions").update();
        wireMock.resetAll();
    }

    private String newIdempotencyKey() {
        return "idem-" + UUID.randomUUID();
    }

    private String requestJson(String accountId, TransactionType type, BigDecimal amount,
                                String currency, String description) throws Exception {
        return objectMapper.writeValueAsString(
                new ExecuteTransactionRequest(accountId, type, amount, currency, description));
    }

    private MvcResult postTransaction(String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID extractId(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").stringValue());
    }

    private List<Map<String, Object>> queryTransactions(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/transactions" + queryString))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private void stubProviderApproves(String providerTransactionId, BigDecimal balance) {
        wireMock.stubFor(post(urlEqualTo(PROVIDER_PATH))
                .willReturn(okJson("""
                        {"transactionId":"%s","status":"APPROVED","balance":%s,"executedAt":"2026-01-01T00:00:00Z"}
                        """.formatted(providerTransactionId, balance))));
    }

    private void stubProviderRejects(String code, String message) {
        wireMock.stubFor(post(urlEqualTo(PROVIDER_PATH))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"REJECTED","code":"%s","message":"%s"}
                                """.formatted(code, message))));
    }

    private void stubProviderTimesOut() {
        wireMock.stubFor(post(urlEqualTo(PROVIDER_PATH))
                .willReturn(aResponse().withFixedDelay(5000)));
    }

    private Transaction anExecutedTransaction(String accountId, TransactionType type, BigDecimal amount) {
        return Transaction.executed(
                "idem-" + UUID.randomUUID(), accountId, type, new Money(amount, DEFAULT_CURRENCY),
                "Seed transaction", "prov-" + UUID.randomUUID(), new BigDecimal("500.00"), Instant.now());
    }

    @Nested
    @DisplayName("Executing transactions")
    class ExecutingTransactions {

        @Test
        @DisplayName("GIVEN a valid CREDIT request, WHEN posting to /transactions, THEN returns 201 with status EXECUTED and the provider's data")
        void executesCreditSuccessfully() throws Exception {
            // given
            stubProviderApproves("prov-1", new BigDecimal("600.00"));
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("EXECUTED"))
                    .andExpect(jsonPath("$.providerTransactionId").value("prov-1"))
                    .andExpect(jsonPath("$.balanceAfter").value(600.00));
        }

        @Test
        @DisplayName("GIVEN a valid DEBIT request within limit, WHEN posting to /transactions, THEN returns 201 with status EXECUTED")
        void executesDebitWithinLimit() throws Exception {
            // given
            stubProviderApproves("prov-2", new BigDecimal("400.00"));
            String body = requestJson("acc-1", TransactionType.DEBIT, new BigDecimal("5000.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            postTransactionAndExpectStatus(body, 201, "EXECUTED");
        }

        private void postTransactionAndExpectStatus(String body, int httpStatus, String txStatus) throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().is(httpStatus))
                    .andExpect(jsonPath("$.status").value(txStatus));
        }

        @Test
        @DisplayName("GIVEN the provider rejects with INSUFFICIENT_FUNDS, WHEN posting to /transactions, THEN returns 201 with status REJECTED and no providerTransactionId")
        void returnsRejectedFromProvider() throws Exception {
            // given
            stubProviderRejects("INSUFFICIENT_FUNDS", "Account balance too low");
            String body = requestJson("acc-1", TransactionType.DEBIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("REJECTED"))
                    .andExpect(jsonPath("$.providerTransactionId").doesNotExist());
        }

        @Test
        @DisplayName("GIVEN the provider times out repeatedly beyond retry, WHEN posting to /transactions, THEN returns 504 and the transaction is persisted with status FAILED")
        void returnsGatewayTimeoutAndPersistsFailed() throws Exception {
            // given
            stubProviderTimesOut();
            String idempotencyKey = newIdempotencyKey();
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"));

            // then
            Optional<Transaction> persisted = repository.findByIdempotencyKey(idempotencyKey);
            assertThat(persisted).isPresent();
            assertThat(persisted.get().status().name()).isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("Business rule validation")
    class BusinessRuleValidation {

        @Test
        @DisplayName("GIVEN an amount less than or equal to $1.00, WHEN posting to /transactions, THEN returns 400 with code INVALID_AMOUNT and the provider is never called")
        void rejectsAmountAtOrBelowMinimum() throws Exception {
            // given
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("1.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
            wireMock.verify(0, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
        }

        @Test
        @DisplayName("GIVEN a DEBIT amount greater than $10,000.00, WHEN posting to /transactions, THEN returns 400 with code DEBIT_LIMIT_EXCEEDED")
        void rejectsDebitAboveLimit() throws Exception {
            // given
            String body = requestJson("acc-1", TransactionType.DEBIT, new BigDecimal("10000.01"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DEBIT_LIMIT_EXCEEDED"));
        }

        @Test
        @DisplayName("GIVEN a CREDIT amount greater than $10,000.00, WHEN posting to /transactions, THEN returns 201 since CREDIT has no limit")
        void allowsCreditAboveLimit() throws Exception {
            // given
            stubProviderApproves("prov-3", new BigDecimal("1000.00"));
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("50000.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("GIVEN a currency other than MXN, WHEN posting to /transactions, THEN returns 400 with code UNSUPPORTED_CURRENCY")
        void rejectsUnsupportedCurrency() throws Exception {
            // given
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), "USD", "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        }

        @Test
        @DisplayName("GIVEN a request missing required fields, WHEN posting to /transactions, THEN returns 400 with code VALIDATION_ERROR")
        void rejectsMissingRequiredFields() throws Exception {
            // given
            String body = "{}";

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("Idempotency behavior")
    class IdempotencyBehavior {

        @Test
        @DisplayName("GIVEN a request without the Idempotency-Key header, WHEN posting to /transactions, THEN returns 400")
        void requiresIdempotencyKeyHeader() throws Exception {
            // given
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GIVEN two identical requests with the same Idempotency-Key, WHEN both are posted, THEN the second returns the same transaction id and the provider is called only once")
        void reusesIdempotentRequest() throws Exception {
            // given
            stubProviderApproves("prov-4", new BigDecimal("700.00"));
            String idempotencyKey = newIdempotencyKey();
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            MvcResult first = postTransaction(idempotencyKey, body);
            MvcResult second = postTransaction(idempotencyKey, body);

            // then
            assertThat(extractId(second)).isEqualTo(extractId(first));
            wireMock.verify(1, postRequestedFor(urlEqualTo(PROVIDER_PATH)));
        }

        @Test
        @DisplayName("GIVEN the same Idempotency-Key reused with a different accountId, WHEN posting the second request, THEN returns 409 with code IDEMPOTENCY_KEY_CONFLICT")
        void conflictsOnDifferentData() throws Exception {
            // given
            stubProviderApproves("prov-5", new BigDecimal("700.00"));
            String idempotencyKey = newIdempotencyKey();
            String firstBody = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");
            String secondBody = requestJson("acc-2", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            postTransaction(idempotencyKey, firstBody);

            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(secondBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        }

        @Test
        @DisplayName("GIVEN a first attempt that resulted in FAILED, WHEN retrying with the same Idempotency-Key and the provider now succeeds, THEN returns 201 EXECUTED with the same transaction id as the original FAILED attempt")
        void retriesFailedTransaction() throws Exception {
            // given
            stubProviderTimesOut();
            String idempotencyKey = newIdempotencyKey();
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            postTransaction(idempotencyKey, body)
                    .getResponse();
            UUID originalId = repository.findByIdempotencyKey(idempotencyKey).orElseThrow().id();

            wireMock.resetAll();
            stubProviderApproves("prov-6", new BigDecimal("800.00"));

            // when
            MvcResult retry = postTransaction(idempotencyKey, body);

            // then
            assertThat(retry.getResponse().getStatus()).isEqualTo(201);
            assertThat(extractId(retry)).isEqualTo(originalId);
        }
    }

    @Nested
    @DisplayName("Querying transactions")
    class QueryingTransactions {

        @Test
        @DisplayName("GIVEN several persisted transactions across accounts, WHEN getting /transactions with an accountId filter, THEN returns only that account's transactions")
        void filtersByAccountId() throws Exception {
            // given
            repository.save(anExecutedTransaction("acc-1", TransactionType.CREDIT, new BigDecimal("100.00")));
            repository.save(anExecutedTransaction("acc-1", TransactionType.DEBIT, new BigDecimal("50.00")));
            repository.save(anExecutedTransaction("acc-2", TransactionType.CREDIT, new BigDecimal("300.00")));

            // when
            List<Map<String, Object>> results = queryTransactions("?accountId=acc-1");

            // then
            assertThat(results).hasSize(2);
            assertThat(results).allSatisfy(tx -> assertThat(tx.get("accountId")).isEqualTo("acc-1"));
        }

        @Test
        @DisplayName("GIVEN persisted transactions with different statuses, WHEN getting /transactions with a status filter, THEN returns only matching ones")
        void filtersByStatus() throws Exception {
            // given
            repository.save(anExecutedTransaction("acc-1", TransactionType.CREDIT, new BigDecimal("100.00")));
            repository.save(Transaction.rejected(
                    "idem-" + UUID.randomUUID(), "acc-1", TransactionType.DEBIT,
                    new Money(new BigDecimal("20.00"), DEFAULT_CURRENCY), "Rejected", Instant.now()));

            // when
            List<Map<String, Object>> results = queryTransactions("?status=REJECTED");

            // then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).get("status")).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("GIVEN persisted transactions with different types, WHEN getting /transactions with a type filter, THEN returns only matching ones")
        void filtersByType() throws Exception {
            // given
            repository.save(anExecutedTransaction("acc-1", TransactionType.CREDIT, new BigDecimal("100.00")));
            repository.save(anExecutedTransaction("acc-1", TransactionType.DEBIT, new BigDecimal("50.00")));

            // when
            List<Map<String, Object>> results = queryTransactions("?type=CREDIT");

            // then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).get("type")).isEqualTo("CREDIT");
        }

        @Test
        @DisplayName("GIVEN more transactions than the page limit, WHEN getting /transactions with page=0 and limit=2, THEN returns exactly 2 results")
        void limitsResultsToPageSize() throws Exception {
            // given
            for (int i = 0; i < 5; i++) {
                repository.save(anExecutedTransaction("acc-page", TransactionType.CREDIT, new BigDecimal("10.00")));
            }

            // when
            List<Map<String, Object>> results = queryTransactions("?accountId=acc-page&page=0&limit=2");

            // then
            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("GIVEN more transactions than the page limit, WHEN requesting page=0 and page=1 with the same limit, THEN the two pages return non-overlapping transaction ids")
        void pagesDoNotOverlap() throws Exception {
            // given
            for (int i = 0; i < 5; i++) {
                repository.save(anExecutedTransaction("acc-page", TransactionType.CREDIT, new BigDecimal("10.00")));
            }

            // when
            List<Map<String, Object>> firstPage = queryTransactions("?accountId=acc-page&page=0&limit=2");
            List<Map<String, Object>> secondPage = queryTransactions("?accountId=acc-page&page=1&limit=2");

            // then
            List<Object> firstIds = firstPage.stream().map(tx -> tx.get("id")).toList();
            List<Object> secondIds = secondPage.stream().map(tx -> tx.get("id")).toList();
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        }

        @Test
        @DisplayName("GIVEN no transactions match the filters, WHEN getting /transactions, THEN returns an empty JSON array with 200")
        void returnsEmptyArrayWhenNoMatches() throws Exception {
            // given
            // when
            List<Map<String, Object>> results = queryTransactions("?accountId=nonexistent");

            // then
            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("API versioning")
    class ApiVersioning {

        @Test
        @DisplayName("GIVEN a request with header X-API-Version: 1, WHEN posting to /transactions, THEN it is handled normally")
        void worksWithExplicitVersion() throws Exception {
            // given
            stubProviderApproves("prov-7", new BigDecimal("900.00"));
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .header("X-API-Version", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("GIVEN a request without the X-API-Version header, WHEN posting to /transactions, THEN it still works using the default version")
        void worksWithDefaultVersion() throws Exception {
            // given
            stubProviderApproves("prov-8", new BigDecimal("900.00"));
            String body = requestJson("acc-1", TransactionType.CREDIT, new BigDecimal("100.00"), DEFAULT_CURRENCY, "Test");

            // when
            // then
            mockMvc.perform(MockMvcRequestBuilders.post("/transactions")
                            .header("Idempotency-Key", newIdempotencyKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }
    }
}
