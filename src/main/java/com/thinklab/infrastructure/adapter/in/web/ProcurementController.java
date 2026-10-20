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
import com.thinklab.application.usecase.RetrievePurchaseOrderTrailsUseCase;
import com.thinklab.application.usecase.RetrievePurchaseOrderUseCase;
import com.thinklab.application.usecase.RetrievePurchaseOrdersUseCase;
import com.thinklab.application.usecase.SubmitPurchaseOrderUseCase;
import com.thinklab.application.usecase.UpdatePurchaseOrderUseCase;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder.Fulfilment;
import com.thinklab.domain.model.PurchaseOrder.Status;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-procurement} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.PurchaseOrder} is the Control Record, at the root; what arrived is a
 * sub-resource under {@code /receipt}. There is no {@code DELETE}: an order is cancelled, and a receipt is a fact that is never edited.
 *
 * <p><b>Tenant on every route, staff only (ADR-034):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's order is a 404) and {@code X-Role} makes every
 * route refuse a {@code REQUESTER} with 403 {@code ERR-PRC-00403}.
 */
@Controller("/it-procurement/v1")
public class ProcurementController {

    private static final Logger log = LoggerFactory.getLogger(ProcurementController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiatePurchaseOrderUseCase initiate;
    private final RetrievePurchaseOrderUseCase retrieveOne;
    private final RetrievePurchaseOrdersUseCase retrieveAll;
    private final UpdatePurchaseOrderUseCase update;
    private final SubmitPurchaseOrderUseCase submit;
    private final CaptureApprovalDecisionUseCase captureDecision;
    private final ControlPurchaseOrderUseCase control;
    private final ReportReceiptUseCase receive;
    private final RetrievePurchaseOrderTrailsUseCase trails;

    public ProcurementController(InitiatePurchaseOrderUseCase initiate, RetrievePurchaseOrderUseCase retrieveOne, RetrievePurchaseOrdersUseCase retrieveAll,
                                 UpdatePurchaseOrderUseCase update, SubmitPurchaseOrderUseCase submit, CaptureApprovalDecisionUseCase captureDecision,
                                 ControlPurchaseOrderUseCase control, ReportReceiptUseCase receive, RetrievePurchaseOrderTrailsUseCase trails) {
        this.initiate = initiate;
        this.retrieveOne = retrieveOne;
        this.retrieveAll = retrieveAll;
        this.update = update;
        this.submit = submit;
        this.captureDecision = captureDecision;
        this.control = control;
        this.receive = receive;
        this.trails = trails;
    }

    /** Behavior Qualifier: {@code initiate}. A purchase order drafted for a vendor. */
    @Post("/initiate")
    public Mono<HttpResponse<PurchaseOrderResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiatePurchaseOrderRequest request
    ) {
        log.info("[ACTION: INITIATE_PURCHASE_ORDER] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiate.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One order of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<PurchaseOrderResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveOne.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), newest first, filterable, at most 500 (100 unless asked). */
    @Get("/retrieve")
    public Mono<List<PurchaseOrderResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                         @QueryValue @Nullable Status status, @QueryValue @Nullable UUID vendorId, @QueryValue @Nullable Currency currency,
                                                         @QueryValue @Nullable Fulfilment fulfilment, @QueryValue @Nullable Boolean late, @QueryValue @Nullable Integer changedWithinDays,
                                                         @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveAll.execute(UUID.fromString(tenantId), status, vendorId, currency, fulfilment, late, changedWithinDays, limit, role).collectList());
    }

    /** Behavior Qualifier: {@code update}. The vendor, the lines and the expected day of a draft. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdatePurchaseOrderRequest request
    ) {
        return Mono.defer(() -> update.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/submit}. DRAFT -&gt; PENDING_APPROVAL: the approval is filed with the approval service. */
    @Put("/{id}/control/submit")
    public Mono<HttpResponse<Void>> submit(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                           @Header(ROLE_HEADER) @Nullable String role) {
        log.info("[ACTION: SUBMIT_PURCHASE_ORDER] [EXECUTOR: {}] for ID: {}", executor, id);
        return Mono.defer(() -> submit.execute(id, UUID.fromString(tenantId), executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code approval/capture}. One approver's decision; the answer is the order after it. */
    @Put("/{id}/approval/capture")
    public Mono<HttpResponse<PurchaseOrderResponse>> capture(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid CaptureApprovalDecisionRequest request
    ) {
        return Mono.defer(() -> captureDecision.execute(id, UUID.fromString(tenantId), request, executor, role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code control/place}. APPROVED -&gt; ORDERED. */
    @Put("/{id}/control/place")
    public Mono<HttpResponse<Void>> place(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                          @Header(ROLE_HEADER) @Nullable String role) {
        return controlOf(id, tenantId, ControlPurchaseOrderUseCase.Action.PLACE, executor, role);
    }

    /** Behavior Qualifier: {@code control/cancel}. Final. */
    @Put("/{id}/control/cancel")
    public Mono<HttpResponse<Void>> cancel(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                           @Header(ROLE_HEADER) @Nullable String role) {
        return controlOf(id, tenantId, ControlPurchaseOrderUseCase.Action.CANCEL, executor, role);
    }

    /** Behavior Qualifier: {@code receipt/initiate}. That much of a line arrived: 201 for a new receipt, 200 for one already reported. */
    @Post("/{id}/receipt/initiate")
    public Mono<HttpResponse<ReceiptResponse>> receive(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid ReceiveRequest request
    ) {
        log.info("[ACTION: RECEIVE_GOODS] [EXECUTOR: {}] for ID: {}", executor, id);
        return Mono.defer(() -> receive.execute(id, UUID.fromString(tenantId), request, executor, role))
                .map(reported -> reported.created() ? HttpResponse.created(reported.receipt()) : HttpResponse.ok(reported.receipt()));
    }

    /** Behavior Qualifier: {@code receipt/retrieve}. What arrived, in the order it was reported. */
    @Get("/{id}/receipt/retrieve")
    public Mono<List<ReceiptResponse>> receipts(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> trails.receipts(id, UUID.fromString(tenantId), role));
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> auditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> trails.auditLog(id, UUID.fromString(tenantId), role));
    }

    private Mono<HttpResponse<Void>> controlOf(UUID id, String tenantId, ControlPurchaseOrderUseCase.Action action, String executor, String role) {
        log.info("[ACTION: {}_PURCHASE_ORDER] [EXECUTOR: {}] for ID: {}", action, executor, id);

        return Mono.defer(() -> control.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
