package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    @DisplayName("an amount with up to two decimal places is kept in minor units, and comes back the same")
    void minorUnits() {
        assertEquals(123456L, Money.toMinor(new BigDecimal("1234.56")));
        assertEquals(1200L, Money.toMinor(new BigDecimal("12")));
        assertEquals(1250L, Money.toMinor(new BigDecimal("12.500")));
        assertEquals(0L, Money.toMinor(BigDecimal.ZERO));
        assertEquals(Money.MAX_MINOR, Money.toMinor(new BigDecimal("100000000000")));
        assertEquals(new BigDecimal("1234.56"), Money.toMajor(123456L));
        assertEquals(new BigDecimal("0.05"), Money.toMajor(5L));
    }

    @Test
    @DisplayName("null, negative, more than two decimal places and absurd amounts are refused, never rounded")
    void refused() {
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(null));
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(new BigDecimal("1.005")));
        assertThrows(IllegalArgumentException.class, () -> Money.toMinor(new BigDecimal("100000000000.01")));
    }

    @Test
    @DisplayName("it is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<Money> constructor = Money.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
