package com.ledger.api;

import com.ledger.core.Account;
import com.ledger.core.AccountId;
import com.ledger.core.AccountRepository;
import com.ledger.core.Money;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody(required = false) CreateAccountRequest request) {
        long initialBalance = request == null || request.initialBalanceMinorUnits() == null
                ? 0
                : request.initialBalanceMinorUnits();
        Account account = accountRepository.create(Money.ofMinorUnits(initialBalance));
        return ResponseEntity.created(URI.create("/accounts/" + account.id().value() + "/balance"))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<AccountResponse> getBalance(@PathVariable String id) {
        return accountRepository.findById(new AccountId(id))
                .map(account -> ResponseEntity.ok(AccountResponse.from(account)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
