package com.thinklab.domain.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for the workflow-approval Service Domain (ADR-032: synchronous HTTP, not events). This service is the sole client of an
 * {@code ApprovalRequest} filed against a purchase order (subjectType {@code "PurchaseOrder"}): the adapter owns that constant.
 */
public interface ApprovalServicePort {

    /** Files a new ApprovalRequest against the configured policy and returns its sovereign id. */
    Mono<UUID> initiateApprovalRequest(UUID organisationId, UUID purchaseOrderId, UUID requesterId, UUID policyId, String executor);

    /** Forwards one approver's decision, in the tenant of the order (never a comment: nothing in words travels) and returns the request's post-decision outcome, read back in the same cycle. */
    Mono<ApprovalOutcome> captureDecision(UUID organisationId, UUID approvalRequestId, DecisionOutcome outcome, String executor);

    /**
     * Whether the approval policy exists, looked up in the tenant of the band that names it (ADR-036). False when the approval service says there is none;
     * an approval service that cannot be reached or answers anything else is {@link com.thinklab.domain.exception.ApprovalUnavailableException}, never a false.
     */
    Mono<Boolean> policyExists(UUID organisationId, UUID policyId);

    enum DecisionOutcome { APPROVE, REJECT }

    /** Mirrors workflow-approval-service's own {@code ApprovalStatus}, kept as this service's own copy. */
    enum ApprovalOutcome { PENDING, APPROVED, REJECTED, CANCELLED }
}
