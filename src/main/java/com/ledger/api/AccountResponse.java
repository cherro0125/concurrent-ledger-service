package com.ledger.api;

import com.ledger.core.Account;

public record AccountResponse(String accountId, long balanceMinorUnits) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id().value(), account.balance().minorUnits());
    }
}
