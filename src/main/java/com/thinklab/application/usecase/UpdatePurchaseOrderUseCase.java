package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdatePurchaseOrderRequest;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Use Case for changing a draft purchase order (BIAN Behavior Qualifier: {@code update}). */
@Singleton
public class UpdatePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdatePurchaseOrderUseCase.class);

    private final PurchaseOrderWorkflow workflow;
    private final Clock clock;

    public UpdatePurchaseOrderUseCase(PurchaseOrderWorkflow workflow, Clock clock) {
        this.workflow = workflow;
        this.clock = clock;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdatePurchaseOrderRequest request, String executor, String role) {
        log.info("[USE CASE] Updating PurchaseOrder ID: {}", id);
        return Mono.fromCallable(() -> InitiatePurchaseOrderUseCase.toNewLines(request.lines()))
                .flatMap((List<NewLine> lines) -> workflow.apply(id, organisationId, role, "change a purchase order",
                        order -> order.update(request.vendorId(), lines, request.expectedOn(), InitiatePurchaseOrderUseCase.today(clock), executor)))
                .then();
    }
}
