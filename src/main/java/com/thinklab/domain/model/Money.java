package com.thinklab.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is kept in minor units (cents) as a whole number, never as a floating point value. The three currencies the registry knows all have two
 * decimal places; an amount with more is refused rather than rounded, so nobody's figure is silently changed.
 */
public final class Money {

    /** The largest amount accepted, in minor units: a hundred billion in the major unit, far above any purchase. */
    public static final long MAX_MINOR = 10_000_000_000_000L;

    private Money() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The amount in minor units; null, negative, more than two decimal places or above the maximum is refused. */
    public static long toMinor(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("An amount is mandatory.");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("An amount cannot be negative.");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("An amount has at most two decimal places.");
        }
        BigDecimal minor = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
        if (minor.compareTo(BigDecimal.valueOf(MAX_MINOR)) > 0) {
            throw new IllegalArgumentException("The amount is too large.");
        }
        return minor.longValueExact();
    }

    public static BigDecimal toMajor(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /** The three currencies, each with two decimal places. Amounts of different currencies are never added together or converted. */
    public enum Currency { BRL, USD, EUR }
}
