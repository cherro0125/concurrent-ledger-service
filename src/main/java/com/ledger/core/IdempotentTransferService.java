package com.ledger.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps {@link TransferService} with idempotency-key semantics: a repeated
 * key returns the original outcome, and the underlying transfer runs at
 * most once — even if the retry arrives while the original is still in
 * flight.
 *
 * <p>Uses {@link ConcurrentHashMap#putIfAbsent} rather than
 * {@code computeIfAbsent} to claim a key. The actual transfer runs outside
 * the map call: {@code ConcurrentHashMap}'s remapping functions must be
 * quick and must not touch the map themselves, but the failure path here
 * needs to remove its own key from the same map — so the claim and the
 * work are two separate steps.
 *
 * <p>A business outcome (success, insufficient funds, account not found)
 * is cached forever: a retry must see the same answer, not get a second
 * attempt at a decision that was already correctly made. Any failure —
 * exception or error — is treated differently: the key is evicted so a
 * later retry gets a fresh attempt instead of permanently replaying (or
 * permanently hanging on) a transient failure. The catch is deliberately
 * {@link Throwable}, not {@link RuntimeException}: if anything escaped
 * uncaught, the future would never complete and every waiter — including
 * one already blocked on it — would hang forever, which is exactly the
 * failure mode this class exists to prevent. Nothing is suppressed; the
 * original throwable is always rethrown unchanged.
 *
 * <p>Reusing a key with different transfer parameters than the request
 * that originally claimed it is rejected with
 * {@link IdempotencyKeyConflictException} rather than silently replaying
 * an unrelated result.
 */
public final class IdempotentTransferService {

    private final TransferService transferService;
    private final ConcurrentHashMap<String, Attempt> inFlight = new ConcurrentHashMap<>();

    public IdempotentTransferService(TransferService transferService) {
        this.transferService = Objects.requireNonNull(transferService, "transferService must not be null");
    }

    public TransferResult transfer(String idempotencyKey, AccountId fromId, AccountId toId, Money amount) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        RequestFingerprint fingerprint = new RequestFingerprint(fromId, toId, amount);
        Attempt myAttempt = new Attempt(new CompletableFuture<>(), fingerprint);
        Attempt claimed = inFlight.putIfAbsent(idempotencyKey, myAttempt);
        if (claimed != null) {
            if (!claimed.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            return await(claimed.future());
        }

        try {
            TransferResult result = transferService.transfer(fromId, toId, amount);
            myAttempt.future().complete(result);
            return result;
        } catch (Throwable failure) {
            inFlight.remove(idempotencyKey, myAttempt);
            myAttempt.future().completeExceptionally(failure);
            throw failure;
        }
    }

    private TransferResult await(CompletableFuture<TransferResult> attempt) {
        try {
            return attempt.join();
        } catch (CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }

    private record RequestFingerprint(AccountId fromId, AccountId toId, Money amount) {}

    private record Attempt(CompletableFuture<TransferResult> future, RequestFingerprint fingerprint) {}
}
