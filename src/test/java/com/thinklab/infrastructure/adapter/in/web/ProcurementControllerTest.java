package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.request.InitiatePurchaseOrderRequest;
import com.thinklab.application.dto.request.ReceiveRequest;
import com.thinklab.application.dto.request.UpdatePurchaseOrderRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.dto.response.ReceiptResponse;
import com.thinklab.application.usecase.CaptureApprovalDecisionUseCase;
import com.thinklab.application.usecase.ControlPurchaseOrderUseCase;
import com.thinklab.application.usecase.InitiatePurchaseOrderUseCase;
import com.thinklab.application.usecase.ReportReceiptUseCase;
import com.thinklab.application.usecase.ReportReceiptUseCase.Reported;
import com.thinklab.application.usecase.RetrievePurchaseOrderTrailsUseCase;
import com.thinklab.application.usecase.RetrievePurchaseOrderUseCase;
import com.thinklab.application.usecase.RetrievePurchaseOrdersUseCase;
import com.thinklab.application.usecase.SubmitPurchaseOrderUseCase;
import com.thinklab.application.usecase.UpdatePurchaseOrderUseCase;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder.Fulfilment;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class ProcurementControllerTest {

    private static final String EXECUTOR = "buyer-1";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @Mock private InitiatePurchaseOrderUseCase initiate;
    @Mock private RetrievePurchaseOrderUseCase retrieveOne;
    @Mock private RetrievePurchaseOrdersUseCase retrieveAll;
    @Mock private UpdatePurchaseOrderUseCase update;
    @Mock private SubmitPurchaseOrderUseCase submit;
    @Mock private CaptureApprovalDecisionUseCase captureDecision;
    @Mock private ControlPurchaseOrderUseCase control;
    @Mock private ReportReceiptUseCase receive;
    @Mock private RetrievePurchaseOrderTrailsUseCase trails;

    private ProcurementController controller() {
        return new ProcurementController(initiate, retrieveOne, retrieveAll, update, submit, captureDecision, control, receive, trails);
    }

    private PurchaseOrderResponse response() {
        return new PurchaseOrderResponse(id, tenant, "PO-1", UUID.randomUUID(), UUID.randomUUID(), "BRL", List.of(), new BigDecimal("10.00"), null, "DRAFT", null, "NOT_ORDERED", false, null, now, now);
    }

    @Test
    @DisplayName("drafting answers 201, reading 200, the list passes every filter, and update and control answer 204")
    void orders() {
        InitiatePurchaseOrderRequest request = new InitiatePurchaseOrderRequest("PO-1", UUID.randomUUID(), UUID.randomUUID(), Currency.BRL, null, List.of());
        UpdatePurchaseOrderRequest change = new UpdatePurchaseOrderRequest(UUID.randomUUID(), null, List.of());
        UUID vendor = UUID.randomUUID();
        when(initiate.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(response()));
        when(retrieveOne.execute(id, tenant, ROLE)).thenReturn(Mono.just(response()));
        when(retrieveAll.execute(tenant, Status.ORDERED, vendor, Currency.BRL, Fulfilment.PARTIAL, true, 7, 10, ROLE)).thenReturn(Flux.just(response()));
        when(update.execute(id, tenant, change, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(submit.execute(id, tenant, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(control.execute(id, tenant, ControlPurchaseOrderUseCase.Action.PLACE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(control.execute(id, tenant, ControlPurchaseOrderUseCase.Action.CANCEL, EXECUTOR, ROLE)).thenReturn(Mono.empty());

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, Status.ORDERED, vendor, Currency.BRL, Fulfilment.PARTIAL, true, 7, 10)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().update(id, tenantHeader, EXECUTOR, ROLE, change)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().submit(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().place(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().cancel(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("a decision answers the order after it (200)")
    void decision() {
        CaptureApprovalDecisionRequest request = new CaptureApprovalDecisionRequest(UUID.randomUUID(), DecisionOutcome.APPROVE);
        when(captureDecision.execute(id, tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(response()));

        StepVerifier.create(controller().capture(id, tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("a new receipt is 201 and one already reported is 200; receipts and the audit log are read")
    void receipts() {
        ReceiveRequest request = new ReceiveRequest(1, "DN-1", 3);
        ReceiptResponse receipt = new ReceiptResponse(1, "DN-1", 3, now, EXECUTOR);
        when(receive.execute(id, tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(new Reported(receipt, true)), Mono.just(new Reported(receipt, false)));
        when(trails.receipts(id, tenant, ROLE)).thenReturn(Mono.just(List.of(receipt)));
        when(trails.auditLog(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "INITIATED", EXECUTOR, null, "DRAFT", null))));

        StepVerifier.create(controller().receive(id, tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().receive(id, tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().receipts(id, tenantHeader, ROLE)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().auditLog(id, tenantHeader, ROLE)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
    }
}
