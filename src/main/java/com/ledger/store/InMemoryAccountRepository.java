package com.ledger.store;

import com.ledger.core.Account;
import com.ledger.core.AccountId;
import com.ledger.core.AccountRepository;
import com.ledger.core.Money;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentHashMap<AccountId, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public Account create(Money initialBalance) {
        Account account = new Account(AccountId.generate(), initialBalance);
        accounts.put(account.id(), account);
        return account;
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
