package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ApprovalUnavailableException;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.repository.ApprovalBandRepository;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for sending a draft for approval (BIAN Behavior Qualifier: {@code control/submit}). The policy is the one of the approval band the order's total falls
 * in (ADR-036); with no band that covers it, nothing is filed and the order stays a draft. It then files a new {@code ApprovalRequest} on the approval service
 * against that policy (ADR-032) and only then moves the order, guarded on the status loaded, and the audit entry says which band was used. An order that cannot go
 * (not a draft, or no lines) is refused before anything is looked up or filed. If the save then loses a race, the approval request that was filed stays unused: the
 * approval service is the one that keeps it, and nothing here decides on it.
 */
@Singleton
public class SubmitPurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitPurchaseOrderUseCase.class);

    private final PurchaseOrderRepository repository;
    private final ApprovalBandRepository bandRepository;
    private final ApprovalServicePort approvalServicePort;

    public SubmitPurchaseOrderUseCase(PurchaseOrderRepository repository, ApprovalBandRepository bandRepository, ApprovalServicePort approvalServicePort) {
        this.repository = repository;
        this.bandRepository = bandRepository;
        this.approvalServicePort = approvalServicePort;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Sending PurchaseOrder ID: {} for approval", id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "send a purchase order for approval"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .flatMap(order -> {
                    order.requireSubmittable();
                    var statusBefore = order.getStatus();
                    return bandRepository.findActive(organisationId, order.getCurrency()).collectList()
                            .flatMap(bands -> ApprovalBand.select(bands, order.totalMinor()).map(Mono::just)
                                    .orElseGet(() -> Mono.error(new ApprovalUnavailableException(
                                            "No approval band covers this purchase order: make a band for " + order.getCurrency() + " that starts at or below its total."))))
                            .flatMap(band -> approvalServicePort.initiateApprovalRequest(organisationId, id, order.getRequesterId(), band.getPolicyId(), executor)
                                    .flatMap(approvalRequestId -> repository.save(order, statusBefore, order.submit(approvalRequestId, executor, band))));
                });
    }
}
