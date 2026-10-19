package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/** Use Case for one purchase order of the tenant, judged now (BIAN Behavior Qualifier: {@code retrieve}). Another tenant's order is a 404. */
@Singleton
public class RetrievePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePurchaseOrderUseCase.class);

    private final PurchaseOrderRepository repository;
    private final Clock clock;

    public RetrievePurchaseOrderUseCase(PurchaseOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<PurchaseOrderResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving PurchaseOrder ID: {}", id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "read a purchase order"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .map(order -> ProcurementMapper.toResponse(order, InitiatePurchaseOrderUseCase.today(clock)));
    }
}
