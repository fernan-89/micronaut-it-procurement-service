package com.thinklab.infrastructure.adapter.out.integration.workflowapproval;

import com.thinklab.domain.exception.ApprovalUnavailableException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.ApprovalRequestApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.CaptureDecisionApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.workflowapproval.WorkflowApprovalServiceAdapter.InitiateApprovalRequestApiRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowApprovalServiceAdapterTest {

    private final WorkflowApprovalApiClient client = mock(WorkflowApprovalApiClient.class);
    private final WorkflowApprovalServiceAdapter adapter = new WorkflowApprovalServiceAdapter(client);
    private final UUID org = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();

    @Test
    @DisplayName("the approval is filed with the purchase order as the subject, and the id of the request comes back")
    void initiate() {
        UUID filed = UUID.randomUUID();
        when(client.initiate(eq(org.toString()), eq("buyer"), any())).thenReturn(Mono.just(new ApprovalRequestApiResponse(filed, "PENDING")));

        StepVerifier.create(adapter.initiateApprovalRequest(org, order, requester, policy, "buyer")).expectNext(filed).verifyComplete();

        ArgumentCaptor<InitiateApprovalRequestApiRequest> body = ArgumentCaptor.forClass(InitiateApprovalRequestApiRequest.class);
        verify(client).initiate(eq(org.toString()), eq("buyer"), body.capture());
        assertEquals(new InitiateApprovalRequestApiRequest("PurchaseOrder", order, requester, policy), body.getValue());
    }

    @Test
    @DisplayName("a decision is forwarded with no comment and the status read back is the outcome")
    void capture() {
        UUID request = UUID.randomUUID();
        when(client.captureDecision(eq(request), eq("approver-1"), any())).thenReturn(Mono.just(new ApprovalRequestApiResponse(request, "APPROVED")));

        StepVerifier.create(adapter.captureDecision(request, DecisionOutcome.APPROVE, "approver-1")).expectNext(ApprovalOutcome.APPROVED).verifyComplete();

        ArgumentCaptor<CaptureDecisionApiRequest> body = ArgumentCaptor.forClass(CaptureDecisionApiRequest.class);
        verify(client).captureDecision(eq(request), eq("approver-1"), body.capture());
        assertEquals("APPROVE", body.getValue().outcome());
        assertNull(body.getValue().comment());
    }

    private static HttpClientResponseException http(HttpStatus status, Object body) {
        return new HttpClientResponseException("boom", HttpResponse.status(status).body(body));
    }

    @Test
    @DisplayName("a 409 on a decision is relayed with the reason the approval service gave; without a reason it says so")
    void conflictIsRelayed() {
        Throwable withReason = WorkflowApprovalServiceAdapter.relayConflict(http(HttpStatus.CONFLICT, Map.of("detail", "Not an eligible approver.")));
        Throwable withoutReason = WorkflowApprovalServiceAdapter.relayConflict(http(HttpStatus.CONFLICT, Map.of("other", "x")));

        assertInstanceOf(InvalidPurchaseOrderStatusException.class, withReason);
        assertEquals("Not an eligible approver.", withReason.getMessage());
        assertEquals("The approval service refused the decision.", withoutReason.getMessage());
    }

    @Test
    @DisplayName("any other failure is the approval service being unavailable, with the status when there was one, and nothing of its answer")
    void otherFailuresAreUnavailable() {
        Throwable status = WorkflowApprovalServiceAdapter.relayConflict(http(HttpStatus.INTERNAL_SERVER_ERROR, Map.of("detail", "secret-detail")));
        Throwable plain = WorkflowApprovalServiceAdapter.unavailable(new IllegalStateException("secret-detail"));

        assertInstanceOf(ApprovalUnavailableException.class, status);
        assertTrue(status.getMessage().contains("HTTP 500"));
        assertFalse(status.getMessage().contains("secret"));
        assertEquals("The approval service could not be reached or refused.", plain.getMessage());
    }

    @Test
    @DisplayName("a failure while filing or deciding is mapped, never raw")
    void failuresAreMapped() {
        when(client.initiate(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));
        when(client.captureDecision(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(adapter.initiateApprovalRequest(org, order, requester, policy, "buyer")).expectError(ApprovalUnavailableException.class).verify();
        StepVerifier.create(adapter.captureDecision(UUID.randomUUID(), DecisionOutcome.REJECT, "a")).expectError(ApprovalUnavailableException.class).verify();
    }
}
