package com.ledger.core;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutable account state. Every balance mutation must happen while holding
 * {@link #lock()}; {@link #balance()} acquires it internally for a
 * consistent read. The lock and mutators are package-private so only the
 * transfer logic in this package can coordinate locking across two
 * accounts (e.g. lock ordering by {@link AccountId} to avoid deadlocks).
 */
public final class Account {

    private final AccountId id;
    private final ReentrantLock lock = new ReentrantLock();
    private Money balance;

    public Account(AccountId id, Money initialBalance) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.balance = Objects.requireNonNull(initialBalance, "initialBalance must not be null");
    }

    public AccountId id() {
        return id;
    }

    public Money balance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }

    ReentrantLock lock() {
        return lock;
    }

    /**
     * Debits {@code amount} if sufficient funds are available, returning
     * whether it happened. Caller must hold {@link #lock()}.
     */
    boolean tryDebit(Money amount) {
        requireLockHeldByCurrentThread();
        if (balance.isLessThan(amount)) {
            return false;
        }
        balance = balance.minus(amount);
        return true;
    }

    /**
     * Credits {@code amount} unconditionally. Caller must hold {@link #lock()}.
     */
    void credit(Money amount) {
        requireLockHeldByCurrentThread();
        balance = balance.plus(amount);
    }

    private void requireLockHeldByCurrentThread() {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Account balance must only be mutated while holding its lock");
        }
    }
}
