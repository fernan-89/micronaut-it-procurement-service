package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to an order shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded (ADR-033). */
@Singleton
public class PurchaseOrderWorkflow {

    private final PurchaseOrderRepository repository;

    public PurchaseOrderWorkflow(PurchaseOrderRepository repository) {
        this.repository = repository;
    }

    public Mono<PurchaseOrder> apply(UUID id, UUID organisationId, String role, String operation, Function<PurchaseOrder, AuditEntry> action) {
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .flatMap(order -> {
                    var statusBefore = order.getStatus();
                    AuditEntry entry = action.apply(order);
                    return repository.save(order, statusBefore, entry).thenReturn(order);
                });
    }
}
