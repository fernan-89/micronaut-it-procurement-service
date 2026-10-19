package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.dto.response.PurchaseOrderResponse.LineResponse;
import com.thinklab.application.dto.response.ReceiptResponse;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.Assessment;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Receipt;

import java.time.LocalDate;

public final class ProcurementMapper {

    private ProcurementMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The order as it stands, with what was received and whether it is late on {@code today}. */
    public static PurchaseOrderResponse toResponse(PurchaseOrder order, LocalDate today) {
        Assessment judged = order.assess(today);
        return new PurchaseOrderResponse(order.getId(), order.getOrganisationId(), order.getReference(), order.getVendorId(), order.getRequesterId(), order.getCurrency().name(),
                order.getLines().stream().map(line -> new LineResponse(line.lineNo(), line.category().name(), line.item(), line.quantity(), Money.toMajor(line.unitPriceMinor()),
                        line.quantity() - line.remaining(), line.remaining())).toList(),
                Money.toMajor(order.totalMinor()), order.getExpectedOn(), order.getStatus().name(), order.getApprovalRequestId(), judged.fulfilment().name(), judged.late(),
                order.getOrderedAt(), order.getCreatedAt(), order.getUpdatedAt());
    }

    public static ReceiptResponse toResponse(Receipt receipt) {
        return new ReceiptResponse(receipt.lineNo(), receipt.deliveryRef(), receipt.quantity(), receipt.receivedAt(), receipt.reportedBy());
    }

    public static AuditEntryResponse toResponse(AuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null, entry.toStatus().name(), entry.detail());
    }
}
