package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Use Case for forwarding one approver's decision to the approval service (BIAN Behavior Qualifier: {@code approval/capture}). It reads the resolved status back
 * in the same response (ADR-032): only APPROVED or REJECTED moves the order; a quorum still PENDING leaves it waiting, since the approval service keeps that
 * decision in its own ledger. Only an order that is waiting for approval takes a decision.
 */
@Singleton
public class CaptureApprovalDecisionUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaptureApprovalDecisionUseCase.class);

    private final PurchaseOrderRepository repository;
    private final ApprovalServicePort approvalServicePort;
    private final Clock clock;

    public CaptureApprovalDecisionUseCase(PurchaseOrderRepository repository, ApprovalServicePort approvalServicePort, Clock clock) {
        this.repository = repository;
        this.approvalServicePort = approvalServicePort;
        this.clock = clock;
    }

    public Mono<PurchaseOrderResponse> execute(UUID id, UUID organisationId, CaptureApprovalDecisionRequest request, String executor, String role) {
        log.info("[USE CASE] Capturing approval decision [{}] for PurchaseOrder ID: {}", request.outcome(), id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "decide on a purchase order"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .flatMap(order -> {
                    if (order.getStatus() != Status.PENDING_APPROVAL || order.getApprovalRequestId() == null) {
                        return Mono.error(new InvalidPurchaseOrderStatusException("Illegal transition: no approval is currently in progress for this PurchaseOrder."));
                    }
                    return approvalServicePort.captureDecision(order.getApprovalRequestId(), request.outcome(), request.approverId().toString())
                            .flatMap(outcome -> apply(order, outcome, executor));
                });
    }

    private Mono<PurchaseOrderResponse> apply(PurchaseOrder order, ApprovalOutcome outcome, String executor) {
        Mono<Void> saved = switch (outcome) {
            case APPROVED -> repository.save(order, Status.PENDING_APPROVAL, order.approve(executor));
            case REJECTED -> repository.save(order, Status.PENDING_APPROVAL, order.reject(executor));
            default -> Mono.empty();
        };
        return saved.thenReturn(ProcurementMapper.toResponse(order, InitiatePurchaseOrderUseCase.today(clock)));
    }
}
