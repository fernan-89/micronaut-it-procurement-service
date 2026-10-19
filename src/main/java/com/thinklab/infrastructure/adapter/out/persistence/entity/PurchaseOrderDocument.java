package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.Line;
import com.thinklab.domain.model.PurchaseOrder.Receipt;
import com.thinklab.domain.model.PurchaseOrder.Status;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the PurchaseOrder Aggregate for MongoDB. Prices are in minor units (whole numbers); the expected day is ISO text. */
@Introspected
public class PurchaseOrderDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private String reference;
    private UUID vendorId;
    private UUID requesterId;
    private String currency;
    private List<LineDocument> lines = new ArrayList<>();
    private String expectedOn;
    private String status;
    private UUID approvalRequestId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant orderedAt;
    private List<ReceiptDocument> receipts = new ArrayList<>();
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public UUID getRequesterId() { return requesterId; }
    public void setRequesterId(UUID requesterId) { this.requesterId = requesterId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public List<LineDocument> getLines() { return lines; }
    public void setLines(List<LineDocument> lines) { this.lines = lines; }
    public String getExpectedOn() { return expectedOn; }
    public void setExpectedOn(String expectedOn) { this.expectedOn = expectedOn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getApprovalRequestId() { return approvalRequestId; }
    public void setApprovalRequestId(UUID approvalRequestId) { this.approvalRequestId = approvalRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getOrderedAt() { return orderedAt; }
    public void setOrderedAt(Instant orderedAt) { this.orderedAt = orderedAt; }
    public List<ReceiptDocument> getReceipts() { return receipts; }
    public void setReceipts(List<ReceiptDocument> receipts) { this.receipts = receipts; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record LineDocument(int lineNo, String category, String item, int quantity, long unitPriceMinor, int remaining) {

        public static LineDocument fromDomain(Line line) {
            return new LineDocument(line.lineNo(), line.category().name(), line.item(), line.quantity(), line.unitPriceMinor(), line.remaining());
        }

        Line toDomain() {
            return new Line(lineNo, Category.valueOf(category), item, quantity, unitPriceMinor, remaining);
        }
    }

    @Introspected
    public record ReceiptDocument(int lineNo, String deliveryRef, int quantity, Instant receivedAt, String reportedBy) {

        public static ReceiptDocument fromDomain(Receipt receipt) {
            return new ReceiptDocument(receipt.lineNo(), receipt.deliveryRef(), receipt.quantity(), receipt.receivedAt(), receipt.reportedBy());
        }

        Receipt toDomain() {
            return new Receipt(lineNo, deliveryRef, quantity, receivedAt, reportedBy);
        }
    }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(AuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        AuditEntry toDomain() {
            return new AuditEntry(occurredAt, action, executor, fromStatus != null ? Status.valueOf(fromStatus) : null, Status.valueOf(toStatus), detail);
        }
    }

    public static final class PurchaseOrderPersistenceMapper {

        private PurchaseOrderPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static PurchaseOrderDocument toDocument(PurchaseOrder order) {
            PurchaseOrderDocument doc = new PurchaseOrderDocument();
            doc.setId(order.getId());
            doc.setOrganisationId(order.getOrganisationId());
            doc.setReference(order.getReference());
            doc.setVendorId(order.getVendorId());
            doc.setRequesterId(order.getRequesterId());
            doc.setCurrency(order.getCurrency().name());
            doc.setLines(order.getLines().stream().map(LineDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            doc.setExpectedOn(order.getExpectedOn() != null ? order.getExpectedOn().toString() : null);
            doc.setStatus(order.getStatus().name());
            doc.setApprovalRequestId(order.getApprovalRequestId());
            doc.setCreatedAt(order.getCreatedAt());
            doc.setUpdatedAt(order.getUpdatedAt());
            doc.setOrderedAt(order.getOrderedAt());
            doc.setReceipts(order.getReceipts().stream().map(ReceiptDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            doc.setAuditTrail(order.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static PurchaseOrder toDomain(PurchaseOrderDocument doc) {
            return PurchaseOrder.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getReference(), doc.getVendorId(), doc.getRequesterId(), Currency.valueOf(doc.getCurrency()),
                    doc.getLines().stream().map(LineDocument::toDomain).collect(Collectors.toList()), doc.getExpectedOn() != null ? LocalDate.parse(doc.getExpectedOn()) : null,
                    Status.valueOf(doc.getStatus()), doc.getApprovalRequestId(), doc.getCreatedAt(), doc.getUpdatedAt(), doc.getOrderedAt(),
                    doc.getReceipts().stream().map(ReceiptDocument::toDomain).collect(Collectors.toList()),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
