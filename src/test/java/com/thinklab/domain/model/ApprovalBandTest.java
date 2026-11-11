package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.ApprovalBand.Status;
import com.thinklab.domain.model.Money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalBandTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();

    private ApprovalBand band(long from) {
        return ApprovalBand.createNew(UUID.randomUUID(), org, Currency.BRL, from, policy, "admin", NOW);
    }

    @Test
    @DisplayName("a band is made active, with its currency, lower bound, policy and who made it")
    void create() {
        ApprovalBand band = band(1_000_00);

        assertEquals(Status.ACTIVE, band.getStatus());
        assertEquals(Currency.BRL, band.getCurrency());
        assertEquals(1_000_00, band.getFromMinor());
        assertEquals(policy, band.getPolicyId());
        assertEquals(org, band.getOrganisationId());
        assertEquals("admin", band.getCreatedBy());
        assertEquals(NOW, band.getCreatedAt());
        assertNull(band.getRetiredAt());
        assertNull(band.getRetiredBy());
        assertEquals(0, band(0).getFromMinor());
        assertEquals(Money.MAX_MINOR, band(Money.MAX_MINOR).getFromMinor());
    }

    @Test
    @DisplayName("a band without an id, an organisation, a currency, a policy or an executor, or with a lower bound out of range, is refused")
    void refused() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(null, org, Currency.BRL, 0, policy, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, null, Currency.BRL, 0, policy, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, null, 0, policy, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, Currency.BRL, 0, null, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, Currency.BRL, -1, policy, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, Currency.BRL, Money.MAX_MINOR + 1, policy, "a", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, Currency.BRL, 0, policy, " ", NOW));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.createNew(id, org, Currency.BRL, 0, policy, null, NOW));
    }

    @Test
    @DisplayName("an active band is retired once, by someone, and a retired one cannot be retired again")
    void retire() {
        ApprovalBand band = band(0);
        assertThrows(IllegalArgumentException.class, () -> band.retire(" ", NOW));
        assertEquals(Status.ACTIVE, band.getStatus());

        band.retire("admin2", NOW.plusSeconds(5));

        assertEquals(Status.RETIRED, band.getStatus());
        assertEquals("admin2", band.getRetiredBy());
        assertEquals(NOW.plusSeconds(5), band.getRetiredAt());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> band.retire("admin2", NOW));
    }

    @Test
    @DisplayName("a band is rebuilt from what was stored; everything but the retirement is mandatory")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        ApprovalBand back = ApprovalBand.reconstitute(id, org, Currency.USD, 5, policy, Status.RETIRED, NOW, "a", NOW, "b");

        assertEquals(id, back.getId());
        assertEquals(Currency.USD, back.getCurrency());
        assertEquals("b", back.getRetiredBy());
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(null, org, Currency.USD, 5, policy, Status.ACTIVE, NOW, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, null, Currency.USD, 5, policy, Status.ACTIVE, NOW, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, org, null, 5, policy, Status.ACTIVE, NOW, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, org, Currency.USD, 5, null, Status.ACTIVE, NOW, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, org, Currency.USD, 5, policy, null, NOW, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, org, Currency.USD, 5, policy, Status.ACTIVE, null, "a", null, null));
        assertThrows(IllegalArgumentException.class, () -> ApprovalBand.reconstitute(id, org, Currency.USD, 5, policy, Status.ACTIVE, NOW, null, null, null));
    }

    @Test
    @DisplayName("the band of a total is the active one with the greatest lower bound not above it; a retired band, or a total below the lowest bound, gives none")
    void select() {
        ApprovalBand zero = band(0);
        ApprovalBand thousand = band(1_000_00);
        ApprovalBand million = band(1_000_000_00);
        ApprovalBand retired = band(5_000_00);
        retired.retire("admin", NOW);
        List<ApprovalBand> all = List.of(million, zero, retired, thousand);

        assertEquals(zero, ApprovalBand.select(all, 0).orElseThrow());
        assertEquals(zero, ApprovalBand.select(all, 999_99).orElseThrow());
        assertEquals(thousand, ApprovalBand.select(all, 1_000_00).orElseThrow());
        assertEquals(thousand, ApprovalBand.select(all, 999_999_99).orElseThrow());
        assertEquals(million, ApprovalBand.select(all, 1_000_000_00).orElseThrow());
        assertEquals(million, ApprovalBand.select(all, Money.MAX_MINOR).orElseThrow());
        assertTrue(ApprovalBand.select(List.of(thousand, million), 999_99).isEmpty());
        assertTrue(ApprovalBand.select(List.of(retired), 10_000_00).isEmpty());
        assertTrue(ApprovalBand.select(List.of(), 1).isEmpty());
    }
}
