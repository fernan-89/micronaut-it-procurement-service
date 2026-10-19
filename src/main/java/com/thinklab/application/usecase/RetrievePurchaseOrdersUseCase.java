package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder.Fulfilment;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Use Case for listing the tenant's purchase orders, newest first, filterable by status, vendor, currency, how much was received and whether the delivery is late
 * (BIAN Behavior Qualifier: {@code retrieve}, collection). What was received and lateness are judged when read (ADR-031): asking for either narrows the store to
 * the placed orders (and, for late, to those expected before today) and only then judges them, so the limit applies to what was asked for. {@code changedWithinDays}
 * (1 to 365) keeps the orders last changed within that many days: what a reader that must also see what recovered (an alert) needs. At most
 * {@link #MAX_LIMIT} (100 unless asked).
 */
@Singleton
public class RetrievePurchaseOrdersUseCase {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    static final int MAX_CHANGED_WITHIN_DAYS = 365;
    /** The most placed orders judged for a fulfilment or lateness filter. */
    static final int JUDGED = 2000;
    private static final Logger log = LoggerFactory.getLogger(RetrievePurchaseOrdersUseCase.class);

    private final PurchaseOrderRepository repository;
    private final Clock clock;

    public RetrievePurchaseOrdersUseCase(PurchaseOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Flux<PurchaseOrderResponse> execute(UUID organisationId, Status status, UUID vendorId, Currency currency, Fulfilment fulfilment, Boolean late, Integer changedWithinDays, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the PurchaseOrders of organisation: {}", organisationId);
        if (changedWithinDays != null && (changedWithinDays < 1 || changedWithinDays > MAX_CHANGED_WITHIN_DAYS)) {
            throw new IllegalArgumentException("The days within which an order changed must be between 1 and " + MAX_CHANGED_WITHIN_DAYS + ".");
        }
        Instant changedSince = changedWithinDays == null ? null : clock.instant().minus(Duration.ofDays(changedWithinDays));
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDate today = InitiatePurchaseOrderUseCase.today(clock);
        boolean judged = fulfilment != null || late != null;
        Status effective = judged ? Status.ORDERED : status;
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "list purchase orders"))
                .thenMany(Flux.defer(() -> status != null && judged && status != Status.ORDERED ? Flux.empty()
                        : repository.findAll(organisationId, effective, vendorId, currency, Boolean.TRUE.equals(late) ? today : null, changedSince, judged ? JUDGED : cap)))
                .map(order -> ProcurementMapper.toResponse(order, today))
                .filter(response -> fulfilment == null || response.fulfilment().equals(fulfilment.name()))
                .filter(response -> late == null || response.late() == late)
                .take(cap);
    }
}
