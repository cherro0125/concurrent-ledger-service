package com.ledger.core;

import java.util.UUID;

public record AccountId(String value) implements Comparable<AccountId> {

    public AccountId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomUUID().toString());
    }

    @Override
    public int compareTo(AccountId other) {
        return value.compareTo(other.value);
    }
}
