package com.ledger.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Moves money between two accounts atomically. Locks are acquired in a
 * fixed global order (by {@link AccountId#compareTo}, not "from" then
 * "to") so that two threads transferring in opposite directions between
 * the same pair of accounts can never deadlock waiting on each other.
 */
public final class TransferService {

    private final AccountRepository accounts;

    public TransferService(AccountRepository accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

    public TransferResult transfer(AccountId fromId, AccountId toId, Money amount) {
        if (fromId == null) {
            throw new IllegalArgumentException("fromId must not be null");
        }
        if (toId == null) {
            throw new IllegalArgumentException("toId must not be null");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("Cannot transfer to the same account: " + fromId);
        }
        if (amount.minorUnits() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive: " + amount);
        }

        Optional<Account> from = accounts.findById(fromId);
        if (from.isEmpty()) {
            return new TransferResult.AccountNotFound(fromId);
        }
        Optional<Account> to = accounts.findById(toId);
        if (to.isEmpty()) {
            return new TransferResult.AccountNotFound(toId);
        }

        return executeUnderLocks(from.get(), to.get(), amount);
    }

    private TransferResult executeUnderLocks(Account from, Account to, Money amount) {
        Account first = from.id().compareTo(to.id()) < 0 ? from : to;
        Account second = (first == from) ? to : from;

        first.mutex().lock();
        try {
            second.mutex().lock();
            try {
                return moveFunds(from, to, amount);
            } finally {
                second.mutex().unlock();
            }
        } finally {
            first.mutex().unlock();
        }
    }

    private TransferResult moveFunds(Account from, Account to, Money amount) {
        if (!from.tryDebit(amount)) {
            return new TransferResult.InsufficientFunds();
        }
        try {
            to.credit(amount);
        } catch (RuntimeException creditFailed) {
            from.credit(amount); // roll back the debit: both locks are still held, so this is safe
            throw creditFailed;
        }
        return new TransferResult.Success();
    }
}
