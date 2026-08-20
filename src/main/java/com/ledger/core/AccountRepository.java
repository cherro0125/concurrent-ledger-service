package com.ledger.core;

import java.util.Optional;

/**
 * Port for account persistence, implemented by an in-memory adapter today
 * (see {@code com.ledger.store}) and swappable for a real storage engine
 * later without touching the transfer logic.
 */
public interface AccountRepository {

    Account create(Money initialBalance);

    Optional<Account> findById(AccountId id);
}
