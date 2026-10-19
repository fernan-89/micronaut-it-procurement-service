package com.thinklab.application.usecase;

import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for placing an approved order with the vendor and for cancelling an order (BIAN Behavior Qualifier: {@code control/*}). */
@Singleton
public class ControlPurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlPurchaseOrderUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        PLACE {
            @Override AuditEntry apply(PurchaseOrder order, String executor, Instant now) { return order.place(executor, now); }
        },
        CANCEL {
            @Override AuditEntry apply(PurchaseOrder order, String executor, Instant now) { return order.cancel(executor); }
        };

        abstract AuditEntry apply(PurchaseOrder order, String executor, Instant now);
    }

    private final PurchaseOrderWorkflow workflow;
    private final Clock clock;

    public ControlPurchaseOrderUseCase(PurchaseOrderWorkflow workflow, Clock clock) {
        this.workflow = workflow;
        this.clock = clock;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on PurchaseOrder ID: {}", action, id);
        return workflow.apply(id, organisationId, role, "place or cancel a purchase order", order -> action.apply(order, executor, clock.instant())).then();
    }
}
