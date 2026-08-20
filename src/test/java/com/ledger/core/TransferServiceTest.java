package com.ledger.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    private final Map<AccountId, Account> accounts = new HashMap<>();
    private final AccountRepository repository = new AccountRepository() {
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
    };
    private final TransferService transferService = new TransferService(repository);

    private Account funded;
    private Account empty;

    @BeforeEach
    void setUp() {
        funded = repository.create(Money.ofMinorUnits(100));
        empty = repository.create(Money.ofMinorUnits(0));
    }

    @Test
    void movesFundsBetweenAccounts() {
        TransferResult result = transferService.transfer(funded.id(), empty.id(), Money.ofMinorUnits(40));

        assertThat(result).isEqualTo(new TransferResult.Success());
        assertThat(funded.balance()).isEqualTo(Money.ofMinorUnits(60));
        assertThat(empty.balance()).isEqualTo(Money.ofMinorUnits(40));
    }

    @Test
    void returnsInsufficientFundsAndLeavesBalancesUnchanged() {
        TransferResult result = transferService.transfer(funded.id(), empty.id(), Money.ofMinorUnits(1_000));

        assertThat(result).isEqualTo(new TransferResult.InsufficientFunds());
        assertThat(funded.balance()).isEqualTo(Money.ofMinorUnits(100));
        assertThat(empty.balance()).isEqualTo(Money.ofMinorUnits(0));
    }

    @Test
    void returnsAccountNotFoundForUnknownSourceAccount() {
        AccountId unknown = AccountId.generate();

        TransferResult result = transferService.transfer(unknown, empty.id(), Money.ofMinorUnits(10));

        assertThat(result).isEqualTo(new TransferResult.AccountNotFound(unknown));
    }

    @Test
    void returnsAccountNotFoundForUnknownDestinationAccount() {
        AccountId unknown = AccountId.generate();

        TransferResult result = transferService.transfer(funded.id(), unknown, Money.ofMinorUnits(10));

        assertThat(result).isEqualTo(new TransferResult.AccountNotFound(unknown));
    }

    @Test
    void rejectsSelfTransfer() {
        assertThatThrownBy(() -> transferService.transfer(funded.id(), funded.id(), Money.ofMinorUnits(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> transferService.transfer(funded.id(), empty.id(), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksAreReleasedAfterEachTransferRegardlessOfOutcome() {
        // If locks leaked, this sequence of calls on the same accounts would hang.
        transferService.transfer(funded.id(), empty.id(), Money.ofMinorUnits(1_000)); // fails: insufficient funds
        transferService.transfer(funded.id(), empty.id(), Money.ofMinorUnits(10));    // succeeds
        transferService.transfer(empty.id(), funded.id(), Money.ofMinorUnits(5));     // opposite direction

        assertThat(funded.balance()).isEqualTo(Money.ofMinorUnits(95));
        assertThat(empty.balance()).isEqualTo(Money.ofMinorUnits(5));
    }

    @Test
    void rollsBackTheDebitIfCreditOverflows() {
        Account nearlyMaxed = repository.create(Money.ofMinorUnits(Long.MAX_VALUE - 5));

        assertThatThrownBy(() -> transferService.transfer(funded.id(), nearlyMaxed.id(), Money.ofMinorUnits(10)))
                .isInstanceOf(ArithmeticException.class);

        assertThat(funded.balance()).isEqualTo(Money.ofMinorUnits(100));
        assertThat(nearlyMaxed.balance()).isEqualTo(Money.ofMinorUnits(Long.MAX_VALUE - 5));
    }
}
