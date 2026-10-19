package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicatePurchaseOrderException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.model.PurchaseOrder.Received;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Procurement against a real MongoDB: the unique reference, the save guarded on the status loaded, the lines written only for a draft, and the receipt as one
 * atomic update: the same delivery reference received twenty times at once is one receipt, twenty receipts of one unit against a line with five to arrive
 * leave exactly five, a placed order with goods received cannot be cancelled, and the indexes.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcurementPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "procurement_it";
    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject PurchaseOrderRepository orders;
    @Inject MongoClient mongoClient;

    private PurchaseOrder draft(UUID organisation, String reference, int quantity) {
        return PurchaseOrder.createNew(UUID.randomUUID(), organisation, reference, UUID.randomUUID(), UUID.randomUUID(), Currency.BRL,
                List.of(new NewLine(Category.HARDWARE, "LAP-14", quantity, 150_000), new NewLine(Category.SOFTWARE, "OFFICE", 3, 2_000)), TODAY.plusDays(30), TODAY, "buyer");
    }

    private PurchaseOrder placed(UUID organisation, String reference, int quantity) {
        PurchaseOrder order = draft(organisation, reference, quantity);
        orders.create(order).block();
        order.submit(UUID.randomUUID(), "buyer");
        orders.save(order, Status.DRAFT, order.getAuditTrail().get(1)).block();
        AuditEntry approved = order.approve("cab");
        orders.save(order, Status.PENDING_APPROVAL, approved).block();
        AuditEntry placedEntry = order.place("buyer", NOW);
        orders.save(order, Status.APPROVED, placedEntry).block();
        return orders.findById(order.getId(), organisation).block();
    }

    @Test
    @DisplayName("an order is stored and found by id for its tenant only, with its lines, prices, day and trail")
    void storeAndFind() {
        UUID org = UUID.randomUUID();
        PurchaseOrder order = draft(org, "PO-1", 10);

        orders.create(order).block();

        PurchaseOrder found = orders.findById(order.getId(), org).block();
        assertNotNull(found);
        assertEquals(2, found.getLines().size());
        assertEquals(150_000, found.getLines().get(0).unitPriceMinor());
        assertEquals(TODAY.plusDays(30), found.getExpectedOn());
        assertEquals(Status.DRAFT, found.getStatus());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(orders.findById(order.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("a reference is used once per organisation, and another organisation may use it")
    void uniqueReference() {
        UUID org = UUID.randomUUID();
        orders.create(draft(org, "PO-DUP", 1)).block();

        assertThrows(DuplicatePurchaseOrderException.class, () -> orders.create(draft(org, "PO-DUP", 1)).block());
        assertNotNull(orders.create(draft(UUID.randomUUID(), "PO-DUP", 1)).block());
    }

    @Test
    @DisplayName("a change is guarded on the status loaded: the loser of two simultaneous changes gets a conflict; the lines are written only for a draft")
    void guardedSave() {
        UUID org = UUID.randomUUID();
        PurchaseOrder order = draft(org, "PO-G", 1);
        orders.create(order).block();
        PurchaseOrder first = orders.findById(order.getId(), org).block();
        PurchaseOrder second = orders.findById(order.getId(), org).block();
        AuditEntry cancelled = first.cancel("a");
        AuditEntry submitted = second.submit(UUID.randomUUID(), "b");

        orders.save(first, Status.DRAFT, cancelled).block();

        assertThrows(InvalidPurchaseOrderStatusException.class, () -> orders.save(second, Status.DRAFT, submitted).block());
        assertEquals(Status.CANCELLED, orders.findById(order.getId(), org).block().getStatus());

        PurchaseOrder editable = draft(org, "PO-G2", 1);
        orders.create(editable).block();
        AuditEntry updated = editable.update(editable.getVendorId(), List.of(new NewLine(Category.CLOUD, "RI", 2, 10)), null, TODAY, "buyer");
        orders.save(editable, Status.DRAFT, updated).block();
        PurchaseOrder reread = orders.findById(editable.getId(), org).block();
        assertEquals(1, reread.getLines().size());
        assertNull(reread.getExpectedOn());
        assertEquals(2, reread.getAuditTrail().size());
    }

    @Test
    @DisplayName("the same delivery reference received twenty times at once is one receipt: nineteen are refused by the store and find it already there")
    void sameReceiptTwentyTimes() {
        UUID org = UUID.randomUUID();
        PurchaseOrder order = placed(org, "PO-R1", 10);
        Received prepared = order.receive(1, "DN-1", 4, "dock", NOW);

        List<Boolean> applied = Flux.range(0, 20).flatMap(i -> orders.receive(order.getId(), org, prepared.receipt(), prepared.entry()).subscribeOn(Schedulers.parallel())).collectList().block();

        assertEquals(1, applied.stream().filter(a -> a).count());
        PurchaseOrder stored = orders.findById(order.getId(), org).block();
        assertEquals(1, stored.getReceipts().size());
        assertEquals(6, stored.getLines().get(0).remaining());
        assertEquals(3, stored.getLines().get(1).remaining());
    }

    @Test
    @DisplayName("twenty receipts of one unit with different references against a line with five to arrive leave exactly five applied: never more than was ordered")
    void neverMoreThanOrdered() {
        UUID org = UUID.randomUUID();
        PurchaseOrder order = placed(org, "PO-R2", 5);

        List<Boolean> applied = Flux.range(0, 20).flatMap(i -> {
            PurchaseOrder fresh = orders.findById(order.getId(), org).block();
            return Mono.fromCallable(() -> fresh.receive(1, "DN-" + i, 1, "dock-" + i, NOW)).flatMap(received -> orders.receive(order.getId(), org, received.receipt(), received.entry()))
                    .onErrorReturn(InvalidPurchaseOrderStatusException.class, false).subscribeOn(Schedulers.parallel());
        }).collectList().block();

        assertEquals(5, applied.stream().filter(a -> a).count());
        PurchaseOrder stored = orders.findById(order.getId(), org).block();
        assertEquals(0, stored.getLines().get(0).remaining());
        assertEquals(5, stored.getReceipts().size());
        assertEquals(5, stored.getAuditTrail().stream().filter(entry -> entry.action().equals("RECEIVED")).count());
    }

    @Test
    @DisplayName("an order that is not placed takes no receipt, and a placed order that received goods cannot be cancelled")
    void receiptAndCancelGuards() {
        UUID org = UUID.randomUUID();
        PurchaseOrder notPlaced = draft(org, "PO-NP", 5);
        orders.create(notPlaced).block();
        PurchaseOrder order = placed(org, "PO-R3", 5);
        Received received = order.receive(1, "DN-1", 2, "dock", NOW);
        assertTrue(orders.receive(order.getId(), org, received.receipt(), received.entry()).block());
        PurchaseOrder withoutReceipt = placed(org, "PO-R4", 5);
        AuditEntry cancelled = withoutReceipt.cancel("buyer");
        orders.save(withoutReceipt, Status.ORDERED, cancelled).block();
        PurchaseOrder lateCancel = orders.findById(order.getId(), org).block();
        PurchaseOrder asIfNoReceipts = PurchaseOrder.reconstitute(lateCancel.getId(), org, lateCancel.getReference(), lateCancel.getVendorId(), lateCancel.getRequesterId(), Currency.BRL,
                lateCancel.getLines(), lateCancel.getExpectedOn(), Status.ORDERED, lateCancel.getApprovalRequestId(), lateCancel.getCreatedAt(), lateCancel.getUpdatedAt(), lateCancel.getOrderedAt(), List.of(),
                lateCancel.getAuditTrail());
        AuditEntry entry = asIfNoReceipts.cancel("buyer");

        assertThrows(InvalidPurchaseOrderStatusException.class, () -> orders.save(asIfNoReceipts, Status.ORDERED, entry).block());
        assertEquals(Status.CANCELLED, orders.findById(withoutReceipt.getId(), org).block().getStatus());
        assertEquals(Status.ORDERED, orders.findById(order.getId(), org).block().getStatus());
        assertEquals(1, orders.findById(order.getId(), org).block().getReceipts().size());
        PurchaseOrder.Receipt receiptForDraft = new PurchaseOrder.Receipt(1, "DN-X", 1, NOW, "dock");
        AuditEntry entryForDraft = new AuditEntry(NOW, "RECEIVED", "dock", Status.ORDERED, Status.ORDERED, "x");
        assertEquals(false, orders.receive(notPlaced.getId(), org, receiptForDraft, entryForDraft).block());
    }

    @Test
    @DisplayName("the list is scoped to the tenant, narrowed by status, vendor, currency and the day expected before, newest first and limited")
    void list() {
        UUID org = UUID.randomUUID();
        PurchaseOrder first = draft(org, "PO-L1", 1);
        orders.create(first).block();
        PurchaseOrder second = placed(org, "PO-L2", 1);
        orders.create(draft(UUID.randomUUID(), "PO-OTHER", 1)).block();

        assertEquals(2, orders.findAll(org, null, null, null, null, 50).count().block());
        assertEquals(List.of(second.getId()), orders.findAll(org, Status.ORDERED, null, null, null, 50).map(PurchaseOrder::getId).collectList().block());
        assertEquals(1, orders.findAll(org, null, first.getVendorId(), null, null, 50).count().block());
        assertEquals(2, orders.findAll(org, null, null, Currency.BRL, null, 50).count().block());
        assertEquals(0, orders.findAll(org, null, null, Currency.USD, null, 50).count().block());
        assertEquals(2, orders.findAll(org, null, null, null, TODAY.plusDays(31), 50).count().block());
        assertEquals(0, orders.findAll(org, null, null, null, TODAY.plusDays(30), 50).count().block());
        assertEquals(1, orders.findAll(org, null, null, null, null, 1).count().block());
        assertEquals(2, orders.findAll(org, null, null, null, null, Instant.now().minusSeconds(3600), 50).count().block());
        assertEquals(0, orders.findAll(org, null, null, null, null, Instant.now().plusSeconds(3600), 50).count().block());
    }

    @Test
    @DisplayName("the unique reference and the list index exist")
    void indexes() {
        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("purchase_orders").listIndexes()).collectList().block();

        Document key = indexes.stream().filter(i -> "organisationId_1_reference_1".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(true, key.getBoolean("unique"));
        assertTrue(indexes.stream().anyMatch(i -> "organisationId_1_status_1_createdAt_-1".equals(i.getString("name"))));
    }
}
