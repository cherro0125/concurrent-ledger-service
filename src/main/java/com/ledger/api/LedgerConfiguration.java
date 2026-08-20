package com.ledger.api;

import com.ledger.core.AccountRepository;
import com.ledger.core.IdempotentTransferService;
import com.ledger.core.TransferService;
import com.ledger.store.InMemoryAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place Spring wiring touches the plain-Java ledger core. Swapping
 * {@link InMemoryAccountRepository} for a real storage engine later is a
 * one-line change here — nothing in {@code com.ledger.core} would need to
 * change.
 */
@Configuration
public class LedgerConfiguration {

    @Bean
    public AccountRepository accountRepository() {
        return new InMemoryAccountRepository();
    }

    @Bean
    public TransferService transferService(AccountRepository accountRepository) {
        return new TransferService(accountRepository);
    }

    @Bean
    public IdempotentTransferService idempotentTransferService(TransferService transferService) {
        return new IdempotentTransferService(transferService);
    }
}
