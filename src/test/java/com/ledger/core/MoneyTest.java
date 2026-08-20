package com.ledger.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> new Money(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroIsAValidAmount() {
        assertThat(new Money(0).minorUnits()).isZero();
    }

    @Test
    void plusAddsAmounts() {
        Money result = Money.ofMinorUnits(100).plus(Money.ofMinorUnits(50));
        assertThat(result).isEqualTo(Money.ofMinorUnits(150));
    }

    @Test
    void minusSubtractsAmounts() {
        Money result = Money.ofMinorUnits(100).minus(Money.ofMinorUnits(30));
        assertThat(result).isEqualTo(Money.ofMinorUnits(70));
    }

    @Test
    void minusRejectsResultBelowZero() {
        assertThatThrownBy(() -> Money.ofMinorUnits(10).minus(Money.ofMinorUnits(20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isLessThanComparesAmounts() {
        assertThat(Money.ofMinorUnits(10).isLessThan(Money.ofMinorUnits(20))).isTrue();
        assertThat(Money.ofMinorUnits(20).isLessThan(Money.ofMinorUnits(10))).isFalse();
        assertThat(Money.ofMinorUnits(10).isLessThan(Money.ofMinorUnits(10))).isFalse();
    }
}
