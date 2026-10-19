package com.thinklab.domain.repository;

import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Receipt;
import com.thinklab.domain.model.PurchaseOrder.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Outbound Port for PurchaseOrder persistence. {@link #save} and {@link #receive} are guarded writes (ADR-033); every lookup is tenant-scoped. */
public interface PurchaseOrderRepository {

    /** Inserts the order. A reference already used in the organisation is {@link com.thinklab.domain.exception.DuplicatePurchaseOrderException}: a unique index is the atomic backstop. */
    Mono<PurchaseOrder> create(PurchaseOrder order);

    Mono<PurchaseOrder> findById(UUID id, UUID organisationId);

    /**
     * The tenant's orders, newest first, at most {@code limit}; every filter that is not null narrows the list. {@code expectedBefore} keeps the orders whose expected
     * delivery is before that day; {@code changedSince} keeps the orders last changed at or after that moment. What was received and whether an order is late are
     * judged when read, so the caller asks for the placed orders and judges them.
     */
    Flux<PurchaseOrder> findAll(UUID organisationId, Status status, UUID vendorId, Currency currency, LocalDate expectedBefore, Instant changedSince, int limit);

    default Flux<PurchaseOrder> findAll(UUID organisationId, Status status, UUID vendorId, Currency currency, LocalDate expectedBefore, int limit) {
        return findAll(organisationId, status, vendorId, currency, expectedBefore, null, limit);
    }

    /**
     * Saves the change of status, the approval request and the moment of the order, guarded on the status loaded; the lines are written only for a draft (once placed,
     * receipts change them concurrently). Cancelling a placed order also needs that nothing was received. A lost guard is an InvalidPurchaseOrderStatusException.
     */
    Mono<Void> save(PurchaseOrder order, Status expectedStatus, AuditEntry auditEntry);

    /**
     * Adds one receipt in one atomic update: the order is still ORDERED, the line has at least that much still to arrive, the same line and delivery reference was
     * not received before and the receipts are not full. Answers whether it was applied.
     */
    Mono<Boolean> receive(UUID id, UUID organisationId, Receipt receipt, AuditEntry auditEntry);
}
