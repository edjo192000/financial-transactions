package com.financial.transactions.challenge.controller;

import com.financial.transactions.challenge.controller.dto.ErrorResponse;
import com.financial.transactions.challenge.controller.dto.ExecuteTransactionRequest;
import com.financial.transactions.challenge.controller.dto.TransactionResponse;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.ExecuteTransactionCommand;
import com.financial.transactions.challenge.service.ExecuteTransactionService;
import com.financial.transactions.challenge.service.QueryTransactionService;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Execution and querying of financial transactions")
public class TransactionController {

    private static final String API_VERSION_1 = "1";

    private final ExecuteTransactionService executeTransactionService;
    private final QueryTransactionService queryTransactionService;

    public TransactionController(ExecuteTransactionService executeTransactionService,
                                 QueryTransactionService queryTransactionService) {
        this.executeTransactionService = executeTransactionService;
        this.queryTransactionService = queryTransactionService;
    }

    @PostMapping(version = API_VERSION_1)
    @Operation(
            summary = "Executes a financial transaction",
            description = "Executes a CREDIT or DEBIT against the external provider. It is idempotent: "
                    + "retrying with the same Idempotency-Key and the same data returns the "
                    + "already EXECUTED/REJECTED transaction without calling the provider again; if the "
                    + "previous attempt ended up FAILED, it does retry against the provider, "
                    + "preserving the same transaction id."
    )
    @Parameter(
            name = "Idempotency-Key",
            in = ParameterIn.HEADER,
            required = true,
            description = "Unique key provided by the client to deduplicate retries of the same operation."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Transaction processed. The body status can be EXECUTED or REJECTED "
                            + "(a provider rejection is a valid business outcome, not an HTTP error).",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "EXECUTED",
                                            summary = "The provider approved the transaction",
                                            value = "{\"id\":\"8aefe9a9-2165-4d82-82f5-aef47a135929\","
                                                    + "\"accountId\":\"acc-1\",\"type\":\"CREDIT\",\"amount\":100.00,"
                                                    + "\"currency\":\"MXN\",\"description\":\"example\",\"status\":\"EXECUTED\","
                                                    + "\"providerTransactionId\":\"txn-qotioal1\",\"balanceAfter\":600.00,"
                                                    + "\"createdAt\":\"2026-01-01T00:00:00Z\"}"
                                    ),
                                    @ExampleObject(
                                            name = "REJECTED",
                                            summary = "The provider rejected the transaction (e.g. insufficient funds)",
                                            value = "{\"id\":\"3f8c9a2e-1b4d-4e9a-9c3f-2d5e6a7b8c9d\","
                                                    + "\"accountId\":\"acc-1\",\"type\":\"DEBIT\",\"amount\":9999999,"
                                                    + "\"currency\":\"MXN\",\"description\":\"example\",\"status\":\"REJECTED\","
                                                    + "\"createdAt\":\"2026-01-01T00:00:00Z\"}"
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request: amount out of range (INVALID_AMOUNT), DEBIT limit "
                            + "exceeded (DEBIT_LIMIT_EXCEEDED), unsupported currency (UNSUPPORTED_CURRENCY), "
                            + "invalid request body (VALIDATION_ERROR), or missing Idempotency-Key "
                            + "header (MISSING_HEADER).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "The Idempotency-Key was already used with different transaction data (IDEMPOTENCY_KEY_CONFLICT).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "It was not possible to communicate with the external provider, or the circuit "
                            + "breaker is open (PROVIDER_UNAVAILABLE).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "504",
                    description = "The external provider did not respond within the expected time, even after "
                            + "retries (PROVIDER_TIMEOUT).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<TransactionResponse> execute(
            @RequestBody @Valid ExecuteTransactionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        ExecuteTransactionCommand command = new ExecuteTransactionCommand(
                idempotencyKey,
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.description()
        );

        Transaction result = executeTransactionService.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(result));
    }

    @GetMapping(version = API_VERSION_1)
    @Operation(summary = "Queries transactions with filters and pagination")
    public List<TransactionResponse> query(
            @Parameter(description = "Filter by account.")
            @RequestParam(required = false) String accountId,

            @Parameter(description = "Filter by transaction status.")
            @RequestParam(required = false) TransactionStatus status,

            @Parameter(description = "Filter by transaction type.")
            @RequestParam(required = false) TransactionType type,

            @Parameter(description = "Page to query (0-indexed). Defaults to 0.")
            @RequestParam(required = false) Integer page,

            @Parameter(description = "Number of results per page. Defaults to 20, maximum 100.")
            @RequestParam(required = false) Integer limit) {

        TransactionFilters filters = TransactionFilters.of(accountId, status, type, page, limit);

        return queryTransactionService.query(filters).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
