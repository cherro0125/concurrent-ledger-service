package com.ledger.api;

public record TransferRequest(String fromAccountId, String toAccountId, long amountMinorUnits) {}
