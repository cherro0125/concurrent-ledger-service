package com.ledger.core;

public record Money(long minorUnits) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("minorUnits must not be negative: " + minorUnits);
        }
    }

    public static Money ofMinorUnits(long minorUnits) {
        return new Money(minorUnits);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(this.minorUnits, other.minorUnits));
    }

    /**
     * Rejects a result below zero rather than clamping or going negative,
     * since {@code Money} itself can never represent a negative amount.
     */
    public Money minus(Money other) {
        long result = Math.subtractExact(this.minorUnits, other.minorUnits);
        if (result < 0) {
            throw new IllegalArgumentException(
                    "Cannot subtract %s from %s: result would be negative".formatted(other, this));
        }
        return new Money(result);
    }

    public boolean isLessThan(Money other) {
        return this.minorUnits < other.minorUnits;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(this.minorUnits, other.minorUnits);
    }
}
