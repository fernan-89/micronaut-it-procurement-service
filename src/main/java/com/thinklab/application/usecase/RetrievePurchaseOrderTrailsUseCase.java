package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.ReceiptResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Use Case for the two records an order keeps: what arrived ({@code receipt/retrieve}) and the forensic ledger ({@code audit-log/retrieve}). Staff only. */
@Singleton
public class RetrievePurchaseOrderTrailsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePurchaseOrderTrailsUseCase.class);

    private final PurchaseOrderRepository repository;

    public RetrievePurchaseOrderTrailsUseCase(PurchaseOrderRepository repository) {
        this.repository = repository;
    }

    public Mono<List<ReceiptResponse>> receipts(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the receipts of PurchaseOrder ID: {}", id);
        return load(id, organisationId, role, "read the receipts of a purchase order")
                .map(order -> order.getReceipts().stream().map(ProcurementMapper::toResponse).toList());
    }

    public Mono<List<AuditEntryResponse>> auditLog(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of PurchaseOrder ID: {}", id);
        return load(id, organisationId, role, "read the audit trail of a purchase order")
                .map(order -> order.getAuditTrail().stream().map(ProcurementMapper::toResponse).toList());
    }

    private Mono<PurchaseOrder> load(UUID id, UUID organisationId, String role, String operation) {
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")));
    }
}
