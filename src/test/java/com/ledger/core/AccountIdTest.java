package com.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountIdTest {

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> new AccountId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> new AccountId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateProducesUniqueIds() {
        assertThat(AccountId.generate()).isNotEqualTo(AccountId.generate());
    }

    @Test
    void compareToOrdersByValue() {
        AccountId a = new AccountId("a");
        AccountId b = new AccountId("b");
        assertThat(a.compareTo(b)).isNegative();
        assertThat(b.compareTo(a)).isPositive();
        assertThat(a.compareTo(new AccountId("a"))).isZero();
    }
}
