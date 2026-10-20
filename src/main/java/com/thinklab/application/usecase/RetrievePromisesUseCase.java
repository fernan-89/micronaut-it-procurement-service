package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.model.Promise;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use Case for the promises of the tenant's purchase orders, judged today (BIAN Behavior Qualifier: {@code promises/retrieve}, platform ADR-001): a placed order
 * past its expected day and not completely received is broken ({@code PURCHASE_ORDER_LATE}); a placed order that is not late is kept, and so is a cancelled one
 * (status {@code CANCELLED}, so it never opens an alert); the rest are not judged. Both sets are asked of the store: the placed orders expected before today
 * (judged for lateness) and the orders that changed within the window, because an old order that is finally received is not among the newest. Staff only.
 */
@Singleton
public class RetrievePromisesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePromisesUseCase.class);

    private final PurchaseOrderRepository repository;
    private final Clock clock;

    public RetrievePromisesUseCase(PurchaseOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<List<PromiseResponse>> execute(UUID organisationId, Integer changedWithinDays, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the promises of the PurchaseOrders of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "list the promises of purchase orders"))
                .then(Mono.fromCallable(() -> new int[]{PromiseSelection.days(changedWithinDays), PromiseSelection.limit(limit)}))
                .flatMap(window -> {
                    Instant now = Instant.now(clock);
                    LocalDate today = InitiatePurchaseOrderUseCase.today(clock);
                    Instant since = now.minus(Duration.ofDays(window[0]));
                    return Mono.zip(repository.findAll(organisationId, Status.ORDERED, null, null, today, null, RetrievePurchaseOrdersUseCase.JUDGED).collectList(),
                                    repository.findAll(organisationId, null, null, null, null, since, window[1]).collectList())
                            .map(read -> {
                                Map<UUID, PromiseResponse> all = new LinkedHashMap<>();
                                read.getT1().forEach(order -> all.putIfAbsent(order.getId(), toResponse(order, today)));
                                read.getT2().forEach(order -> all.putIfAbsent(order.getId(), toResponse(order, today)));
                                return PromiseSelection.select(List.copyOf(all.values()), since, window[1]);
                            });
                });
    }

    private static PromiseResponse toResponse(PurchaseOrder order, LocalDate today) {
        Promise promise = order.promise(today);
        return new PromiseResponse(order.getId(), order.getReference(), null, promise.verdict().name(), promise.code(), order.promiseStatus(), order.getUpdatedAt());
    }
}
