package com.ledger.core;

import com.ledger.store.InMemoryAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the concurrency guarantees under real thread interleaving,
 * as opposed to {@link TransferServiceTest} and
 * {@link IdempotentTransferServiceTest}, which prove individual behaviors
 * with engineered (single- or two-thread) interleavings.
 */
class ConcurrencyTest {

    /**
     * Many threads, a small shared pool of accounts, all starting at the
     * same instant. The account pool is deliberately small relative to
     * the thread count so that same-account and same-pair-opposite-
     * direction collisions are frequent, not rare — a large pool would
     * let most transfers proceed on disjoint accounts and barely
     * exercise the locking at all.
     */
    @Test
    void heavyConcurrentTransfersPreserveTotalBalanceAndNeverGoNegative() throws Exception {
        int accountCount = 8;
        int threadCount = 32;
        int transfersPerThread = 2_000;
        long initialBalance = 200; // small relative to transfer size, so funds actually run out sometimes

        InMemoryAccountRepository repository = new InMemoryAccountRepository();
        List<Account> accounts = IntStream.range(0, accountCount)
                .mapToObj(i -> repository.create(Money.ofMinorUnits(initialBalance)))
                .toList();
        TransferService transferService = new TransferService(repository);
        Money totalBefore = totalBalance(accounts);

        CyclicBarrier startingLine = new CyclicBarrier(threadCount);
        AtomicInteger insufficientFundsCount = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    Random random = new Random();
                    awaitBarrier(startingLine);
                    for (int i = 0; i < transfersPerThread; i++) {
                        int fromIndex = random.nextInt(accountCount);
                        // guaranteed distinct from fromIndex, so every iteration is a real transfer
                        int toIndex = (fromIndex + 1 + random.nextInt(accountCount - 1)) % accountCount;
                        Account from = accounts.get(fromIndex);
                        Account to = accounts.get(toIndex);
                        Money amount = Money.ofMinorUnits(1 + random.nextInt(100));
                        TransferResult result = transferService.transfer(from.id(), to.id(), amount);
                        if (result instanceof TransferResult.InsufficientFunds) {
                            insufficientFundsCount.incrementAndGet();
                        }
                    }
                }));
            }
            executor.shutdown();
            boolean finishedInTime = executor.awaitTermination(30, TimeUnit.SECONDS);

            assertThat(finishedInTime)
                    .as("all transfers should finish well within the timeout; a hang here means a deadlock")
                    .isTrue();
            for (Future<?> future : futures) {
                future.get(); // surface any exception a worker thread swallowed
            }
        } finally {
            // Best-effort only: Account uses plain lock(), not lockInterruptibly(), so a
            // thread genuinely deadlocked here would not respond to this interrupt.
            executor.shutdownNow();
        }

        assertThat(totalBalance(accounts))
                .as("money must be conserved: no lost updates, no double-spend")
                .isEqualTo(totalBefore);
        assertThat(accounts).allSatisfy(account ->
                assertThat(account.balance().minorUnits()).isGreaterThanOrEqualTo(0));
        assertThat(insufficientFundsCount.get())
                .as("zero insufficient-funds outcomes means this run never actually contended for scarce "
                        + "funds, so the balance check isn't meaningfully exercised under load")
                .isGreaterThan(0);
    }

    /**
     * Sixteen threads, one idempotency key, one synchronized start: the
     * hard version of the wait-on-in-flight guarantee, proven under a
     * true simultaneous start rather than one engineered interleaving.
     */
    @Test
    void concurrentRetriesWithSameIdempotencyKeyExecuteExactlyOnce() throws Exception {
        int threadCount = 16;

        InMemoryAccountRepository repository = new InMemoryAccountRepository();
        Account from = repository.create(Money.ofMinorUnits(1_000));
        Account to = repository.create(Money.ofMinorUnits(0));
        AtomicInteger lookups = new AtomicInteger();
        AccountRepository countingRepository = new CountingAccountRepository(repository, lookups);
        IdempotentTransferService service = new IdempotentTransferService(new TransferService(countingRepository));

        CyclicBarrier startingLine = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<TransferResult>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    awaitBarrier(startingLine);
                    return service.transfer("shared-key", from.id(), to.id(), Money.ofMinorUnits(40));
                }));
            }
            executor.shutdown();
            boolean finishedInTime = executor.awaitTermination(30, TimeUnit.SECONDS);
            assertThat(finishedInTime).isTrue();

            List<TransferResult> results = new ArrayList<>();
            for (Future<TransferResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).allMatch(result -> result.equals(new TransferResult.Success()));
        } finally {
            executor.shutdownNow();
        }

        assertThat(from.balance()).isEqualTo(Money.ofMinorUnits(960)); // moved exactly once
        assertThat(to.balance()).isEqualTo(Money.ofMinorUnits(40));
        // TransferService.transfer looks up both accounts exactly once per real execution;
        // more than 2 lookups would mean the transfer ran more than once.
        assertThat(lookups.get()).isEqualTo(2);
    }

    private static Money totalBalance(List<Account> accounts) {
        return accounts.stream()
                .map(Account::balance)
                .reduce(Money.ZERO, Money::plus);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
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
