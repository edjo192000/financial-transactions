package com.financial.transactions.challenge.controller;

import com.financial.transactions.challenge.controller.dto.ExecuteTransactionRequest;
import com.financial.transactions.challenge.controller.dto.TransactionResponse;
import com.financial.transactions.challenge.domain.Transaction;
import com.financial.transactions.challenge.domain.TransactionStatus;
import com.financial.transactions.challenge.domain.TransactionType;
import com.financial.transactions.challenge.service.ExecuteTransactionCommand;
import com.financial.transactions.challenge.service.ExecuteTransactionService;
import com.financial.transactions.challenge.service.QueryTransactionService;
import com.financial.transactions.challenge.service.port.TransactionFilters;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
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
    public List<TransactionResponse> query(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {

        TransactionFilters filters = TransactionFilters.of(accountId, status, type, page, limit);

        return queryTransactionService.query(filters).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
