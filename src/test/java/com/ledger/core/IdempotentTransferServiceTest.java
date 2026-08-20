package com.ledger.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentTransferServiceTest {

    private final InMemoryAccountRepositoryStub repository = new InMemoryAccountRepositoryStub();

    @Test
    void sameKeyAppliesTheTransferAtMostOnce() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(repository));

        TransferResult first = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40));
        TransferResult retry = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40));

        assertThat(first).isEqualTo(retry).isEqualTo(new TransferResult.Success());
        assertThat(from.balance()).isEqualTo(Money.ofMinorUnits(60)); // moved once, not twice
        assertThat(to.balance()).isEqualTo(Money.ofMinorUnits(40));
    }

    @Test
    void sameKeyReplaysABusinessOutcomeWithoutReExecuting() {
        Account from = repository.create(Money.ofMinorUnits(10));
        Account to = repository.create(Money.ofMinorUnits(0));
        AtomicInteger lookups = new AtomicInteger();
        AccountRepository countingRepository = new CountingAccountRepository(repository, lookups);
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(countingRepository));

        TransferResult first = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(1_000));
        int lookupsAfterFirstCall = lookups.get();

        TransferResult retry = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(1_000));

        assertThat(first).isEqualTo(retry).isEqualTo(new TransferResult.InsufficientFunds());
        assertThat(lookups.get()).isEqualTo(lookupsAfterFirstCall); // no new lookups => no re-execution
    }

    @Test
    void differentKeysEachExecuteIndependently() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(repository));

        service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40));
        service.transfer("key-2", from.id(), to.id(), Money.ofMinorUnits(40));

        assertThat(from.balance()).isEqualTo(Money.ofMinorUnits(20));
        assertThat(to.balance()).isEqualTo(Money.ofMinorUnits(80));
    }

    @Test
    void exceptionDuringOriginalDoesNotPermanentlyBlockRetriesWithTheSameKey() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        AccountRepository flakyRepository = new AccountRepository() {
            @Override
            public Account create(Money initialBalance) {
                return repository.create(initialBalance);
            }

            @Override
            public Optional<Account> findById(AccountId id) {
                if (shouldFail.getAndSet(false)) {
                    throw new RuntimeException("simulated transient failure");
                }
                return repository.findById(id);
            }
        };
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(flakyRepository));

        assertThatThrownBy(() -> service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated transient failure");

        TransferResult retryResult = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40));

        assertThat(retryResult).isEqualTo(new TransferResult.Success());
        assertThat(from.balance()).isEqualTo(Money.ofMinorUnits(60));
        assertThat(to.balance()).isEqualTo(Money.ofMinorUnits(40));
    }

    @Test
    void errorDuringOriginalDoesNotLeaveARetryHangingForever() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        AccountRepository flakyRepository = new AccountRepository() {
            @Override
            public Account create(Money initialBalance) {
                return repository.create(initialBalance);
            }

            @Override
            public Optional<Account> findById(AccountId id) {
                if (shouldFail.getAndSet(false)) {
                    throw new SimulatedInfrastructureFailure();
                }
                return repository.findById(id);
            }
        };
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(flakyRepository));

        assertThatThrownBy(() -> service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40)))
                .isInstanceOf(SimulatedInfrastructureFailure.class);

        // If the Error had leaked the key, this retry would hang forever instead of returning.
        TransferResult retryResult = service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40));

        assertThat(retryResult).isEqualTo(new TransferResult.Success());
    }

    @Test
    void reusingKeyWithDifferentParametersIsRejected() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        Account other = repository.create(Money.ofMinorUnits(0));
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(repository));

        service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(10));

        assertThatThrownBy(() -> service.transfer("key-1", from.id(), other.id(), Money.ofMinorUnits(10)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThatThrownBy(() -> service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(999)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    /** A dedicated {@link Error} subtype so this test can't be confused with a real infrastructure fault. */
    private static final class SimulatedInfrastructureFailure extends Error {}

    @Test
    void concurrentRetryDuringInFlightOriginalWaitsAndSeesTheSameResult() throws Exception {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        CountDownLatch releaseOriginal = new CountDownLatch(1);
        CountDownLatch originalIsInFlight = new CountDownLatch(1);
        AtomicBoolean firstLookupSeen = new AtomicBoolean(false);
        AccountRepository blockingOnFirstLookup = new AccountRepository() {
            @Override
            public Account create(Money initialBalance) {
                return repository.create(initialBalance);
            }

            @Override
            public Optional<Account> findById(AccountId id) {
                if (firstLookupSeen.compareAndSet(false, true)) {
                    originalIsInFlight.countDown();
                    awaitUninterruptibly(releaseOriginal);
                }
                return repository.findById(id);
            }
        };
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(blockingOnFirstLookup));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TransferResult> original = executor.submit(
                    () -> service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40)));
            originalIsInFlight.await();

            Future<TransferResult> retry = executor.submit(
                    () -> service.transfer("key-1", from.id(), to.id(), Money.ofMinorUnits(40)));
            Thread.sleep(100); // give the retry a moment to reach the map and start waiting
            assertThat(retry.isDone()).isFalse();

            releaseOriginal.countDown();

            assertThat(original.get(2, TimeUnit.SECONDS)).isEqualTo(retry.get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(from.balance()).isEqualTo(Money.ofMinorUnits(60)); // moved once, not twice
        assertThat(to.balance()).isEqualTo(Money.ofMinorUnits(40));
    }

    @Test
    void rejectsNullIdempotencyKey() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(repository));

        assertThatThrownBy(() -> service.transfer(null, from.id(), to.id(), Money.ofMinorUnits(10)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        Account from = repository.create(Money.ofMinorUnits(100));
        Account to = repository.create(Money.ofMinorUnits(0));
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(repository));

        assertThatThrownBy(() -> service.transfer("  ", from.id(), to.id(), Money.ofMinorUnits(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** A minimal in-memory {@link AccountRepository}, local to this test so it stays independent of com.ledger.store. */
    private static final class InMemoryAccountRepositoryStub implements AccountRepository {
        private final java.util.Map<AccountId, Account> accounts = new java.util.concurrent.ConcurrentHashMap<>();

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
    }

    private record CountingAccountRepository(AccountRepository delegate, AtomicInteger lookups)
            implements AccountRepository {
        @Override
        public Account create(Money initialBalance) {
            return delegate.create(initialBalance);
        }

        @Override
        public Optional<Account> findById(AccountId id) {
            lookups.incrementAndGet();
            return delegate.findById(id);
        }
    }
}
