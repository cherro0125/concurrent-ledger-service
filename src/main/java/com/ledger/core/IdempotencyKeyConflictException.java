package com.ledger.core;

/**
 * Thrown when an idempotency key is reused with different transfer
 * parameters than the request that originally claimed it.
 */
public final class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key already used with different transfer parameters: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
