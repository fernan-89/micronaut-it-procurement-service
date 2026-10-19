package com.thinklab.infrastructure.adapter.out.integration.workflowapproval;

import com.thinklab.domain.exception.ApprovalUnavailableException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.port.ApprovalServicePort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Adapter for the workflow-approval Service Domain (ADR-032). A failure says that the approval service could not be reached or refused, and with
 * which HTTP status when there was one, and never repeats its answer; a 409 on a decision (an approver who is not eligible, a request already decided) is a
 * refusal the caller should read, so it is relayed as this service's own 409 with the reason the approval service gave.
 */
@Singleton
public class WorkflowApprovalServiceAdapter implements ApprovalServicePort {

    private static final Logger log = LoggerFactory.getLogger(WorkflowApprovalServiceAdapter.class);
    private static final String SUBJECT_TYPE = "PurchaseOrder";

    private final WorkflowApprovalApiClient apiClient;

    public WorkflowApprovalServiceAdapter(WorkflowApprovalApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<UUID> initiateApprovalRequest(UUID organisationId, UUID purchaseOrderId, UUID requesterId, UUID policyId, String executor) {
        log.debug("[INTEGRATION] Filing an ApprovalRequest for PurchaseOrder: {}", purchaseOrderId);
        return apiClient.initiate(organisationId.toString(), executor, new InitiateApprovalRequestApiRequest(SUBJECT_TYPE, purchaseOrderId, requesterId, policyId))
                .map(ApprovalRequestApiResponse::id)
                .onErrorMap(WorkflowApprovalServiceAdapter::unavailable);
    }

    @Override
    public Mono<ApprovalOutcome> captureDecision(UUID approvalRequestId, DecisionOutcome outcome, String executor) {
        log.debug("[INTEGRATION] Forwarding decision [{}] for ApprovalRequest: {}", outcome, approvalRequestId);
        return apiClient.captureDecision(approvalRequestId, executor, new CaptureDecisionApiRequest(outcome.name(), null))
                .map(response -> ApprovalOutcome.valueOf(response.status()))
                .onErrorMap(WorkflowApprovalServiceAdapter::relayConflict);
    }

    static Throwable relayConflict(Throwable error) {
        if (error instanceof HttpClientResponseException http && http.getStatus() == HttpStatus.CONFLICT) {
            String reason = http.getResponse().getBody(Map.class).map(body -> body.get("detail")).map(String::valueOf).orElse("The approval service refused the decision.");
            return new InvalidPurchaseOrderStatusException(reason);
        }
        return unavailable(error);
    }

    static Throwable unavailable(Throwable error) {
        String what = error instanceof HttpClientResponseException http ? " (HTTP " + http.getStatus().getCode() + ")" : "";
        log.warn("[INTEGRATION] The approval service could not be used{}: {}", what, error.getClass().getSimpleName());
        return new ApprovalUnavailableException("The approval service could not be reached or refused" + what + ".");
    }

    @Serdeable
    @Introspected
    record InitiateApprovalRequestApiRequest(String subjectType, UUID subjectId, UUID requesterId, UUID policyId) {}

    @Serdeable
    @Introspected
    record CaptureDecisionApiRequest(String outcome, @Nullable String comment) {}

    @Serdeable
    @Introspected
    record ApprovalRequestApiResponse(UUID id, String status) {}
}

/** Declarative Micronaut HTTP Client for the workflow-approval Service Domain. The 'id' maps to the configuration in application.yml. */
@Client(id = "workflow-approval-service", path = "/workflow-approval/v1")
interface WorkflowApprovalApiClient {

    @Post("/initiate")
    Mono<WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse> initiate(
            @Header("X-Tenant-Id") String tenantId,
            @Header("X-Executor") String executor,
            @Body WorkflowApprovalServiceAdapter.InitiateApprovalRequestApiRequest request
    );

    @Put("/{id}/decision/capture")
    Mono<WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse> captureDecision(
            @PathVariable UUID id,
            @Header("X-Executor") String executor,
            @Body WorkflowApprovalServiceAdapter.CaptureDecisionApiRequest request
    );
}
