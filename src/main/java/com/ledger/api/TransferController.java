package com.ledger.api;

import com.ledger.core.AccountId;
import com.ledger.core.IdempotentTransferService;
import com.ledger.core.Money;
import com.ledger.core.TransferResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final IdempotentTransferService idempotentTransferService;

    public TransferController(IdempotentTransferService idempotentTransferService) {
        this.idempotentTransferService = idempotentTransferService;
    }

    @PostMapping
    public ResponseEntity<?> postTransfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransferRequest request) {
        AccountId fromId = new AccountId(request.fromAccountId());
        AccountId toId = new AccountId(request.toAccountId());
        Money amount = Money.ofMinorUnits(request.amountMinorUnits());

        TransferResult result = idempotentTransferService.transfer(idempotencyKey, fromId, toId, amount);

        // Exhaustive over the sealed TransferResult -- adding a variant without a case here won't compile.
        return switch (result) {
            case TransferResult.Success ignored ->
                    ResponseEntity.ok(new TransferResponse("SUCCESS"));
            case TransferResult.InsufficientFunds ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("Insufficient funds"));
            case TransferResult.AccountNotFound(AccountId accountId) ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse("Account not found: " + accountId.value()));
        };
    }
}
