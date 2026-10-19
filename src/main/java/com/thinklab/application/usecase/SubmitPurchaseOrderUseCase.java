package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ApprovalUnavailableException;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for sending a draft for approval (BIAN Behavior Qualifier: {@code control/submit}). It files a new {@code ApprovalRequest} on the approval service
 * against the configured policy (ADR-032) and only then moves the order, guarded on the status loaded. An order that cannot go (not a draft, or no lines) is
 * refused before anything is filed. If the save then loses a race, the approval request that was filed stays unused: the approval service is the one that
 * keeps it, and nothing here decides on it.
 */
@Singleton
public class SubmitPurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitPurchaseOrderUseCase.class);

    private final PurchaseOrderRepository repository;
    private final ApprovalServicePort approvalServicePort;
    private final UUID policyId;

    public SubmitPurchaseOrderUseCase(PurchaseOrderRepository repository, ApprovalServicePort approvalServicePort,
                                      @Value("${thinklab.procurement.approval-policy-id}") String policyId) {
        this.repository = repository;
        this.approvalServicePort = approvalServicePort;
        this.policyId = policyId == null || policyId.isBlank() ? null : UUID.fromString(policyId);
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Sending PurchaseOrder ID: {} for approval", id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "send a purchase order for approval"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .flatMap(order -> {
                    order.requireSubmittable();
                    if (policyId == null) {
                        return Mono.error(new ApprovalUnavailableException("No approval policy is configured for purchase orders."));
                    }
                    var statusBefore = order.getStatus();
                    return approvalServicePort.initiateApprovalRequest(organisationId, id, order.getRequesterId(), policyId, executor)
                            .flatMap(approvalRequestId -> repository.save(order, statusBefore, order.submit(approvalRequestId, executor)));
                });
    }
}
