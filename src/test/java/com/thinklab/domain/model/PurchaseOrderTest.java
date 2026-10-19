package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.Fulfilment;
import com.thinklab.domain.model.PurchaseOrder.Line;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.model.PurchaseOrder.Received;
import com.thinklab.domain.model.PurchaseOrder.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseOrderTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID vendor = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private static List<NewLine> lines() {
        return List.of(new NewLine(Category.HARDWARE, "LAP-14", 10, 150_000), new NewLine(Category.SOFTWARE, "OFFICE-E3", 5, 2_000));
    }

    private PurchaseOrder draft() {
        return PurchaseOrder.createNew(id, org, "PO-2026-001", vendor, requester, Currency.BRL, lines(), TODAY.plusDays(30), TODAY, "buyer");
    }

    private PurchaseOrder placed() {
        PurchaseOrder order = draft();
        order.submit(UUID.randomUUID(), "buyer");
        order.approve("cab");
        order.place("buyer", NOW);
        return order;
    }

    private static void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test
    @DisplayName("a new order is a draft with numbered lines, a total in minor units, everything still to arrive and one entry in its trail")
    void createNew() {
        PurchaseOrder order = draft();

        assertEquals(Status.DRAFT, order.getStatus());
        assertEquals("PO-2026-001", order.getReference());
        assertEquals(Currency.BRL, order.getCurrency());
        assertEquals(List.of(new Line(1, Category.HARDWARE, "LAP-14", 10, 150_000, 10), new Line(2, Category.SOFTWARE, "OFFICE-E3", 5, 2_000, 5)), order.getLines());
        assertEquals(10 * 150_000 + 5 * 2_000, order.totalMinor());
        assertEquals(TODAY.plusDays(30), order.getExpectedOn());
        assertEquals(1, order.getAuditTrail().size());
        assertEquals("INITIATED", order.getAuditTrail().get(0).action());
        assertNull(order.getApprovalRequestId());
        assertNull(order.getOrderedAt());
        assertTrue(order.getReceipts().isEmpty());
        assertEquals(vendor, order.getVendorId());
        assertEquals(requester, order.getRequesterId());
        assertEquals(org, order.getOrganisationId());
        assertEquals(id, order.getId());
        assertTrue(order.getCreatedAt() != null && order.getUpdatedAt() != null);
    }

    @Test
    @DisplayName("what is mandatory and what is an identifier is checked, and a draft may have no lines yet")
    void validation() {
        UUID x = UUID.randomUUID();
        rejects(() -> PurchaseOrder.createNew(null, org, "PO-1", vendor, requester, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, null, "PO-1", vendor, requester, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, null, vendor, requester, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "has space", vendor, requester, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", null, requester, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, null, Currency.USD, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, null, lines(), null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, null, null, TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, lines(), null, TODAY, " "));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, lines(), null, TODAY, null));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, lines(), TODAY.minusDays(1), TODAY, "b"));
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, lines(), TODAY.plusDays(731), TODAY, "b"));
        PurchaseOrder empty = PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, List.of(), TODAY, TODAY, "b");
        assertTrue(empty.getLines().isEmpty());
        assertEquals(0, empty.totalMinor());
        assertEquals(TODAY, empty.getExpectedOn());
    }

    @Test
    @DisplayName("a line needs a category, an item identifier, a quantity from 1 to 100000 and a price that is not negative or absurd; the order has up to 50 lines and a total within the largest amount")
    void lineValidation() {
        UUID x = UUID.randomUUID();
        List<NewLine> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new NewLine(Category.OTHER, "I-" + i, 1, 1));
        }
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, tooMany, null, TODAY, "b"));
        java.util.function.Function<NewLine, Runnable> with = line -> () -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, List.of(line), null, TODAY, "b");
        rejects(with.apply(new NewLine(null, "I-1", 1, 1)));
        rejects(with.apply(new NewLine(Category.OTHER, null, 1, 1)));
        rejects(with.apply(new NewLine(Category.OTHER, "has space", 1, 1)));
        rejects(with.apply(new NewLine(Category.OTHER, "I-1", 0, 1)));
        rejects(with.apply(new NewLine(Category.OTHER, "I-1", 100_001, 1)));
        rejects(with.apply(new NewLine(Category.OTHER, "I-1", 1, -1)));
        rejects(with.apply(new NewLine(Category.OTHER, "I-1", 1, Money.MAX_MINOR + 1)));
        rejects(with.apply(new NewLine(Category.OTHER, "I-1", 100_000, Money.MAX_MINOR)));
        List<NewLine> withNull = new ArrayList<>();
        withNull.add(null);
        rejects(() -> PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, withNull, null, TODAY, "b"));
        assertEquals(Money.MAX_MINOR, PurchaseOrder.createNew(x, org, "PO-1", vendor, requester, Currency.USD, List.of(new NewLine(Category.OTHER, "I-1", 1, Money.MAX_MINOR)), null, TODAY, "b").totalMinor());
    }

    @Test
    @DisplayName("a draft is updated (vendor, all the lines again, the day), and nothing else changes; it is a conflict once it left the draft")
    void update() {
        PurchaseOrder order = draft();
        UUID other = UUID.randomUUID();

        AuditEntry entry = order.update(other, List.of(new NewLine(Category.CLOUD, "RI-1Y", 2, 500)), null, TODAY, "buyer");

        assertEquals("UPDATED", entry.action());
        assertEquals(other, order.getVendorId());
        assertEquals(1, order.getLines().size());
        assertEquals(1, order.getLines().get(0).lineNo());
        assertNull(order.getExpectedOn());
        assertEquals("PO-2026-001", order.getReference());
        rejects(() -> order.update(null, lines(), null, TODAY, "buyer"));
        rejects(() -> order.update(other, null, null, TODAY, "buyer"));
        rejects(() -> order.update(other, lines(), TODAY.minusDays(1), TODAY, "buyer"));
        rejects(() -> order.update(other, lines(), null, TODAY, " "));
        order.submit(UUID.randomUUID(), "buyer");
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.update(other, lines(), null, TODAY, "buyer"));
    }

    @Test
    @DisplayName("submitting needs a draft with lines and an approval request; approving or rejecting needs an order waiting for approval; a rejected one is final")
    void approval() {
        UUID request = UUID.randomUUID();
        PurchaseOrder empty = PurchaseOrder.createNew(id, org, "PO-1", vendor, requester, Currency.EUR, List.of(), null, TODAY, "b");
        rejects(empty::requireSubmittable);
        rejects(() -> empty.submit(request, "b"));
        PurchaseOrder order = draft();
        order.requireSubmittable();
        rejects(() -> order.submit(null, "b"));
        rejects(() -> order.submit(request, " "));

        AuditEntry submitted = order.submit(request, "buyer");

        assertEquals(Status.PENDING_APPROVAL, order.getStatus());
        assertEquals(request, order.getApprovalRequestId());
        assertEquals(Status.DRAFT, submitted.fromStatus());
        assertEquals(Status.PENDING_APPROVAL, submitted.toStatus());
        assertThrows(InvalidPurchaseOrderStatusException.class, order::requireSubmittable);
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.submit(request, "b"));
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.place("b", NOW));
        rejects(() -> order.approve(" "));
        order.approve("cab");
        assertEquals(Status.APPROVED, order.getStatus());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.approve("cab"));
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.reject("cab"));
        PurchaseOrder other = draft();
        other.submit(request, "b");
        rejects(() -> other.reject(null));
        other.reject("cab");
        assertEquals(Status.REJECTED, other.getStatus());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> other.cancel("b"));
    }

    @Test
    @DisplayName("an approved order is placed with the vendor, once, and keeps the moment")
    void place() {
        PurchaseOrder order = draft();
        order.submit(UUID.randomUUID(), "b");
        order.approve("cab");
        rejects(() -> order.place(" ", NOW));

        order.place("buyer", NOW);

        assertEquals(Status.ORDERED, order.getStatus());
        assertEquals(NOW, order.getOrderedAt());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.place("buyer", NOW));
    }

    @Test
    @DisplayName("an order is cancelled from a draft, while waiting, once approved, or placed with nothing received; never twice, and never after goods arrived")
    void cancel() {
        PurchaseOrder draft = draft();
        draft.cancel("b");
        assertEquals(Status.CANCELLED, draft.getStatus());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> draft.cancel("b"));

        PurchaseOrder waiting = draft();
        waiting.submit(UUID.randomUUID(), "b");
        rejects(() -> waiting.cancel(" "));
        waiting.cancel("b");
        assertEquals(Status.CANCELLED, waiting.getStatus());

        PurchaseOrder approved = draft();
        approved.submit(UUID.randomUUID(), "b");
        approved.approve("cab");
        approved.cancel("b");
        assertEquals(Status.CANCELLED, approved.getStatus());

        PurchaseOrder ordered = placed();
        ordered.cancel("b");
        assertEquals(Status.CANCELLED, ordered.getStatus());

        PurchaseOrder received = placed();
        received.receive(1, "DN-1", 1, "dock", NOW);
        InvalidPurchaseOrderStatusException refused = assertThrows(InvalidPurchaseOrderStatusException.class, () -> received.cancel("b"));
        assertTrue(refused.getMessage().contains("received goods"));
    }

    @Test
    @DisplayName("goods are received against a placed order: a fact with its delivery reference, the line's remaining goes down, and the same receipt again is the same receipt")
    void receive() {
        PurchaseOrder order = placed();

        Received first = order.receive(1, "DN-100", 4, "dock", NOW);

        assertTrue(first.created());
        assertEquals("RECEIVED", first.entry().action());
        assertEquals(4, first.receipt().quantity());
        assertEquals("dock", first.receipt().reportedBy());
        assertEquals(6, order.getLines().get(0).remaining());
        assertEquals(1, order.getReceipts().size());
        Received again = order.receive(1, "DN-100", 4, "dock-2", NOW.plusSeconds(60));
        assertFalse(again.created());
        assertSame(first.receipt(), again.receipt());
        assertNull(again.entry());
        assertEquals(6, order.getLines().get(0).remaining());
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.receive(1, "DN-100", 5, "dock", NOW));
        Received sameNumberOtherLine = order.receive(2, "DN-100", 5, "dock", NOW);
        assertTrue(sameNumberOtherLine.created());
        assertEquals(2, order.getReceipts().size());
    }

    @Test
    @DisplayName("a receipt is refused for an order not placed, an unknown line, a bad reference or quantity, more than is still to arrive, or when the receipts are full")
    void receiveRefusals() {
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> draft().receive(1, "DN-1", 1, "dock", NOW));
        PurchaseOrder order = placed();
        rejects(() -> order.receive(1, null, 1, "dock", NOW));
        rejects(() -> order.receive(1, "has space", 1, "dock", NOW));
        rejects(() -> order.receive(9, "DN-1", 1, "dock", NOW));
        rejects(() -> order.receive(1, "DN-1", 0, "dock", NOW));
        rejects(() -> order.receive(1, "DN-1", 100_001, "dock", NOW));
        rejects(() -> order.receive(1, "DN-1", 1, " ", NOW));
        InvalidPurchaseOrderStatusException over = assertThrows(InvalidPurchaseOrderStatusException.class, () -> order.receive(1, "DN-1", 11, "dock", NOW));
        assertTrue(over.getMessage().contains("only 10"));

        PurchaseOrder big = PurchaseOrder.createNew(UUID.randomUUID(), org, "PO-BIG", vendor, requester, Currency.USD, List.of(new NewLine(Category.SUPPLIES, "PEN", 100_000, 1)), null, TODAY, "b");
        big.submit(UUID.randomUUID(), "b");
        big.approve("cab");
        big.place("b", NOW);
        for (int i = 0; i < PurchaseOrder.MAX_RECEIPTS; i++) {
            big.receive(1, "DN-" + i, 1, "dock", NOW);
        }
        assertThrows(InvalidPurchaseOrderStatusException.class, () -> big.receive(1, "DN-LAST", 1, "dock", NOW));
    }

    @Test
    @DisplayName("what was received and whether the delivery is late are judged when read: none, partial, complete; late only while not complete and past the expected day")
    void assess() {
        PurchaseOrder order = placed();

        assertEquals(Fulfilment.NONE, order.assess(TODAY).fulfilment());
        assertFalse(order.assess(TODAY.plusDays(30)).late());
        assertTrue(order.assess(TODAY.plusDays(31)).late());
        order.receive(1, "DN-1", 10, "dock", NOW);
        assertEquals(Fulfilment.PARTIAL, order.assess(TODAY).fulfilment());
        assertTrue(order.assess(TODAY.plusDays(31)).late());
        order.receive(2, "DN-2", 5, "dock", NOW);
        assertEquals(Fulfilment.COMPLETE, order.assess(TODAY).fulfilment());
        assertFalse(order.assess(TODAY.plusDays(99)).late());
        assertEquals(Fulfilment.NOT_ORDERED, draft().assess(TODAY).fulfilment());
        PurchaseOrder noDay = PurchaseOrder.createNew(UUID.randomUUID(), org, "PO-ND", vendor, requester, Currency.USD, lines(), null, TODAY, "b");
        noDay.submit(UUID.randomUUID(), "b");
        noDay.approve("cab");
        noDay.place("b", NOW);
        assertFalse(noDay.assess(TODAY.plusDays(999)).late());
    }

    @Test
    @DisplayName("reconstituting needs what identifies and describes the order, and keeps what was stored")
    void reconstitute() {
        PurchaseOrder back = PurchaseOrder.reconstitute(id, org, "PO-1", vendor, requester, Currency.USD, List.of(new Line(1, Category.OTHER, "X", 1, 1, 1)), null, Status.APPROVED, null, null, null, null, null, null);

        assertEquals(Status.APPROVED, back.getStatus());
        assertTrue(back.getReceipts().isEmpty() && back.getAuditTrail().isEmpty());
        assertTrue(back.getCreatedAt() != null);
        assertEquals(back.getCreatedAt(), back.getUpdatedAt());
        assertEquals(Status.DRAFT, PurchaseOrder.reconstitute(id, org, "PO-1", vendor, requester, Currency.USD, List.of(), null, null, null, NOW, NOW, null, null, null).getStatus());
        rejects(() -> PurchaseOrder.reconstitute(null, org, "PO-1", vendor, requester, Currency.USD, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, null, "PO-1", vendor, requester, Currency.USD, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, org, null, vendor, requester, Currency.USD, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, org, "PO-1", null, requester, Currency.USD, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, org, "PO-1", vendor, null, Currency.USD, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, org, "PO-1", vendor, requester, null, List.of(), null, null, null, null, null, null, null, null));
        rejects(() -> PurchaseOrder.reconstitute(id, org, "PO-1", vendor, requester, Currency.USD, null, null, null, null, null, null, null, null, null));
    }
}
