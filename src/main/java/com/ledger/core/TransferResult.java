package com.ledger.core;

public sealed interface TransferResult {

    record Success() implements TransferResult {}

    record InsufficientFunds() implements TransferResult {}

    record AccountNotFound(AccountId accountId) implements TransferResult {}
}
