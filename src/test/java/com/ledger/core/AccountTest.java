package com.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void balanceReturnsInitialBalance() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(500));
        assertThat(account.balance()).isEqualTo(Money.ofMinorUnits(500));
    }

    @Test
    void creditIncreasesBalanceWhileHoldingLock() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(100));

        withLock(account, () -> account.credit(Money.ofMinorUnits(50)));

        assertThat(account.balance()).isEqualTo(Money.ofMinorUnits(150));
    }

    @Test
    void tryDebitSucceedsAndReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(100));

        boolean result = withLock(account, () -> account.tryDebit(Money.ofMinorUnits(40)));

        assertThat(result).isTrue();
        assertThat(account.balance()).isEqualTo(Money.ofMinorUnits(60));
    }

    @Test
    void tryDebitFailsAndLeavesBalanceUnchangedWhenFundsAreInsufficient() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(30));

        boolean result = withLock(account, () -> account.tryDebit(Money.ofMinorUnits(40)));

        assertThat(result).isFalse();
        assertThat(account.balance()).isEqualTo(Money.ofMinorUnits(30));
    }

    @Test
    void creditWithoutHoldingLockThrows() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(100));

        assertThatThrownBy(() -> account.credit(Money.ofMinorUnits(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tryDebitWithoutHoldingLockThrows() {
        Account account = new Account(AccountId.generate(), Money.ofMinorUnits(100));

        assertThatThrownBy(() -> account.tryDebit(Money.ofMinorUnits(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static boolean withLock(Account account, java.util.function.BooleanSupplier action) {
        account.mutex().lock();
        try {
            return action.getAsBoolean();
        } finally {
            account.mutex().unlock();
        }
    }

    private static void withLock(Account account, Runnable action) {
        account.mutex().lock();
        try {
            action.run();
        } finally {
            account.mutex().unlock();
        }
    }
}
