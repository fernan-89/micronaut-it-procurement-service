package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate Root (BIAN Control Record) of the IT Procurement Service Domain: one purchase order for a vendor, in one {@link Currency} (never
 * converted, ADR-030), made of lines (a category from a fixed list, an item identifier, a quantity and a unit price). It is drafted, sent for approval
 * to the shared approval service (ADR-032), placed with the vendor once approved, and then received, in part or in whole, by people who state what
 * arrived (ADR-033). It records: it never calls a vendor, a bank or a store. What has been received, and whether the delivery is late, are judged
 * when read (ADR-031).
 */
public class PurchaseOrder {

    public static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,39}");
    public static final Pattern ITEM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
    public static final Pattern DELIVERY_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,39}");
    public static final int MAX_LINES = 50;
    public static final int MAX_QUANTITY = 100_000;
    public static final int MAX_RECEIPTS = 200;
    /** How far ahead the expected delivery may be: two years. */
    public static final int MAX_DAYS_AHEAD = 730;

    private final UUID id;
    private final UUID organisationId;
    private final String reference;
    private UUID vendorId;
    private final UUID requesterId;
    private final Currency currency;
    private List<Line> lines;
    private LocalDate expectedOn;
    private Status status;
    private UUID approvalRequestId;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant orderedAt;
    private final List<Receipt> receipts;
    private final List<AuditEntry> auditTrail;

    private PurchaseOrder(UUID id, UUID organisationId, String reference, UUID vendorId, UUID requesterId, Currency currency, List<Line> lines, LocalDate expectedOn,
                          Status status, UUID approvalRequestId, Instant createdAt, Instant updatedAt, Instant orderedAt, List<Receipt> receipts, List<AuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.reference = reference;
        this.vendorId = vendorId;
        this.requesterId = requesterId;
        this.currency = currency;
        this.lines = new ArrayList<>(lines);
        this.expectedOn = expectedOn;
        this.status = status != null ? status : Status.DRAFT;
        this.approvalRequestId = approvalRequestId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.orderedAt = orderedAt;
        this.receipts = receipts != null ? new ArrayList<>(receipts) : new ArrayList<>();
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static PurchaseOrder createNew(UUID id, UUID organisationId, String reference, UUID vendorId, UUID requesterId, Currency currency, List<NewLine> lines,
                                          LocalDate expectedOn, LocalDate today, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for PurchaseOrder creation.");
        }
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("The reference must be up to 40 letters, digits, dots, dashes, underscores or slashes.");
        }
        if (vendorId == null || requesterId == null || currency == null) {
            throw new IllegalArgumentException("The vendor, the requester and the currency of the purchase order are mandatory.");
        }
        List<Line> numbered = number(lines);
        validateExpected(expectedOn, today);
        requireExecutor(executor);
        PurchaseOrder order = new PurchaseOrder(id, organisationId, reference, vendorId, requesterId, currency, numbered, expectedOn, Status.DRAFT, null, null, null, null, null, null);
        order.auditTrail.add(new AuditEntry(order.createdAt, "INITIATED", executor, null, Status.DRAFT, "Drafted with " + numbered.size() + " line(s) in " + currency + "."));
        return order;
    }

    public static PurchaseOrder reconstitute(UUID id, UUID organisationId, String reference, UUID vendorId, UUID requesterId, Currency currency, List<Line> lines,
                                             LocalDate expectedOn, Status status, UUID approvalRequestId, Instant createdAt, Instant updatedAt, Instant orderedAt,
                                             List<Receipt> receipts, List<AuditEntry> auditTrail) {
        if (id == null || organisationId == null || reference == null || vendorId == null || requesterId == null || currency == null || lines == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Reference, Vendor, Requester, Currency and Lines are mandatory to reconstitute a PurchaseOrder.");
        }
        return new PurchaseOrder(id, organisationId, reference, vendorId, requesterId, currency, lines, expectedOn, status, approvalRequestId, createdAt, updatedAt, orderedAt,
                receipts, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. The vendor, the lines and the expected delivery, while the order is still a draft. The reference, the requester and the currency never change. */
    public AuditEntry update(UUID newVendorId, List<NewLine> newLines, LocalDate newExpectedOn, LocalDate today, String executor) {
        requireStatus(Status.DRAFT, "Only a draft purchase order can be changed.");
        if (newVendorId == null) {
            throw new IllegalArgumentException("The vendor of the purchase order is mandatory.");
        }
        List<Line> numbered = number(newLines);
        validateExpected(newExpectedOn, today);
        requireExecutor(executor);
        this.vendorId = newVendorId;
        this.lines = numbered;
        this.expectedOn = newExpectedOn;
        return record("UPDATED", executor, Status.DRAFT, Status.DRAFT, "Now " + numbered.size() + " line(s).");
    }

    /** Behavior Qualifier: {@code control/submit}. DRAFT -&gt; PENDING_APPROVAL, once the approval was filed with the shared approval service (its id is kept). */
    public AuditEntry submit(UUID newApprovalRequestId, String executor) {
        requireSubmittable();
        if (newApprovalRequestId == null) {
            throw new IllegalArgumentException("The approval request is mandatory to send a purchase order for approval.");
        }
        requireExecutor(executor);
        this.approvalRequestId = newApprovalRequestId;
        return transition(Status.PENDING_APPROVAL, "SUBMITTED", executor, "Sent for approval, " + Money.toMajor(totalMinor()) + " " + currency + ".");
    }

    /** Whether the order can be sent for approval now: a draft with at least one line. Asked before the approval service is called, so nothing is filed for an order that cannot go. */
    public void requireSubmittable() {
        requireStatus(Status.DRAFT, "Only a draft purchase order can be sent for approval.");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order needs at least one line before it is sent for approval.");
        }
    }

    /** The approval service resolved the request as approved: PENDING_APPROVAL -&gt; APPROVED. */
    public AuditEntry approve(String executor) {
        requireStatus(Status.PENDING_APPROVAL, "Only a purchase order waiting for approval can be approved.");
        requireExecutor(executor);
        return transition(Status.APPROVED, "APPROVED", executor, "Approved.");
    }

    /** The approval service resolved the request as rejected: PENDING_APPROVAL -&gt; REJECTED (final). */
    public AuditEntry reject(String executor) {
        requireStatus(Status.PENDING_APPROVAL, "Only a purchase order waiting for approval can be rejected.");
        requireExecutor(executor);
        return transition(Status.REJECTED, "REJECTED", executor, "Rejected.");
    }

    /** Behavior Qualifier: {@code control/place}. APPROVED -&gt; ORDERED: the order was placed with the vendor; receiving starts. */
    public AuditEntry place(String executor, Instant now) {
        requireStatus(Status.APPROVED, "Only an approved purchase order can be placed with the vendor.");
        requireExecutor(executor);
        this.orderedAt = now;
        return transition(Status.ORDERED, "PLACED", executor, "Placed with the vendor.");
    }

    /** Behavior Qualifier: {@code control/cancel}. From DRAFT, PENDING_APPROVAL, APPROVED, or ORDERED while nothing has been received (final). */
    public AuditEntry cancel(String executor) {
        if (status == Status.REJECTED || status == Status.CANCELLED || (status == Status.ORDERED && !receipts.isEmpty())) {
            throw new InvalidPurchaseOrderStatusException("Illegal transition: a purchase order that is " + status + (status == Status.ORDERED ? " and has received goods" : "") + " cannot be cancelled.");
        }
        requireExecutor(executor);
        return transition(Status.CANCELLED, "CANCELLED", executor, "Cancelled.");
    }

    /**
     * Behavior Qualifier: {@code receipt/initiate}. People say that {@code quantity} of line {@code lineNo} arrived with the delivery reference {@code deliveryRef}.
     * The same line and delivery reference again with the same quantity is the same receipt (the stored one is returned, {@code created} false); with another
     * quantity it is a conflict (a delivery note number cannot be reused). A receipt can never exceed what is still to arrive on the line (ADR-033).
     */
    public Received receive(int lineNo, String deliveryRef, int quantity, String executor, Instant now) {
        requireStatus(Status.ORDERED, "Goods can only be received against a purchase order that was placed with the vendor.");
        if (deliveryRef == null || !DELIVERY_REF.matcher(deliveryRef).matches()) {
            throw new IllegalArgumentException("The delivery reference must be up to 40 letters, digits, dots, dashes, underscores or slashes.");
        }
        Line line = lines.stream().filter(candidate -> candidate.lineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The purchase order has no such line."));
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("The quantity received must be between 1 and " + MAX_QUANTITY + ".");
        }
        requireExecutor(executor);
        for (Receipt existing : receipts) {
            if (existing.lineNo() == lineNo && existing.deliveryRef().equals(deliveryRef)) {
                if (existing.quantity() != quantity) {
                    throw new InvalidPurchaseOrderStatusException("Illegal receipt: that delivery reference was already used for this line with another quantity.");
                }
                return new Received(existing, false, null);
            }
        }
        if (receipts.size() >= MAX_RECEIPTS) {
            throw new InvalidPurchaseOrderStatusException("Illegal receipt: this purchase order already holds " + MAX_RECEIPTS + " receipts.");
        }
        if (quantity > line.remaining()) {
            throw new InvalidPurchaseOrderStatusException("Illegal receipt: only " + line.remaining() + " of line " + lineNo + " is still to arrive.");
        }
        Receipt receipt = new Receipt(lineNo, deliveryRef, quantity, now, executor);
        lines.set(lines.indexOf(line), line.received(quantity));
        receipts.add(receipt);
        AuditEntry entry = record("RECEIVED", executor, Status.ORDERED, Status.ORDERED, quantity + " of line " + lineNo + " (delivery " + deliveryRef + ").");
        return new Received(receipt, true, entry);
    }

    /** Where the order stands on {@code today} (ADR-031): what was received so far, and whether the delivery is late. Nothing of it is stored. */
    public Assessment assess(LocalDate today) {
        if (status != Status.ORDERED) {
            return new Assessment(Fulfilment.NOT_ORDERED, false);
        }
        long ordered = lines.stream().mapToLong(Line::quantity).sum();
        long remaining = lines.stream().mapToLong(Line::remaining).sum();
        Fulfilment fulfilment = remaining == 0 ? Fulfilment.COMPLETE : remaining == ordered ? Fulfilment.NONE : Fulfilment.PARTIAL;
        boolean late = fulfilment != Fulfilment.COMPLETE && expectedOn != null && today.isAfter(expectedOn);
        return new Assessment(fulfilment, late);
    }

    /** The whole order in minor units: what the lines add up to (a line is its quantity times its unit price). */
    public long totalMinor() {
        return lines.stream().mapToLong(line -> line.quantity() * line.unitPriceMinor()).sum();
    }

    // --- Internal helpers ---

    private AuditEntry transition(Status to, String action, String executor, String detail) {
        Status from = this.status;
        this.status = to;
        return record(action, executor, from, to, detail);
    }

    private AuditEntry record(String action, String executor, Status from, Status to, String detail) {
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(Status expected, String message) {
        if (this.status != expected) {
            throw new InvalidPurchaseOrderStatusException("Illegal transition: PurchaseOrder is [" + this.status + "]. " + message);
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable PurchaseOrder mutations.");
        }
    }

    private static void validateExpected(LocalDate expectedOn, LocalDate today) {
        if (expectedOn != null && (expectedOn.isBefore(today) || expectedOn.isAfter(today.plusDays(MAX_DAYS_AHEAD)))) {
            throw new IllegalArgumentException("The expected delivery must be between today and " + MAX_DAYS_AHEAD + " days ahead.");
        }
    }

    /** Numbers the lines 1..n in the order given, after checking each one and that the whole stays within the largest amount. */
    private static List<Line> number(List<NewLine> newLines) {
        if (newLines == null || newLines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A purchase order has up to " + MAX_LINES + " lines.");
        }
        List<Line> numbered = new ArrayList<>();
        long total = 0;
        for (NewLine candidate : newLines) {
            if (candidate == null || candidate.category() == null) {
                throw new IllegalArgumentException("Every line needs a category.");
            }
            if (candidate.item() == null || !ITEM.matcher(candidate.item()).matches()) {
                throw new IllegalArgumentException("The item must be up to 64 letters, digits, dots, dashes, underscores or slashes.");
            }
            if (candidate.quantity() < 1 || candidate.quantity() > MAX_QUANTITY) {
                throw new IllegalArgumentException("The quantity of a line must be between 1 and " + MAX_QUANTITY + ".");
            }
            if (candidate.unitPriceMinor() < 0 || candidate.unitPriceMinor() > Money.MAX_MINOR) {
                throw new IllegalArgumentException("The unit price must be zero or more, and not absurdly large.");
            }
            total += (long) candidate.quantity() * candidate.unitPriceMinor();
            if (total > Money.MAX_MINOR) {
                throw new IllegalArgumentException("The purchase order is too large.");
            }
            numbered.add(new Line(numbered.size() + 1, candidate.category(), candidate.item(), candidate.quantity(), candidate.unitPriceMinor(), candidate.quantity()));
        }
        return numbered;
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getReference() { return reference; }
    public UUID getVendorId() { return vendorId; }
    public UUID getRequesterId() { return requesterId; }
    public Currency getCurrency() { return currency; }
    public List<Line> getLines() { return Collections.unmodifiableList(lines); }
    public LocalDate getExpectedOn() { return expectedOn; }
    public Status getStatus() { return status; }
    public UUID getApprovalRequestId() { return approvalRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getOrderedAt() { return orderedAt; }
    public List<Receipt> getReceipts() { return Collections.unmodifiableList(receipts); }
    public List<AuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, ORDERED, CANCELLED }

    /** What a line buys, from a fixed list: never a description in words. */
    public enum Category { HARDWARE, SOFTWARE, CLOUD, SERVICES, SUPPLIES, OTHER }

    /** NOT_ORDERED: not placed (or not any more). NONE, PARTIAL, COMPLETE: how much of what was ordered has arrived. */
    public enum Fulfilment { NOT_ORDERED, NONE, PARTIAL, COMPLETE }

    /** A line as asked for: its number is given by the order. */
    public record NewLine(Category category, String item, int quantity, long unitPriceMinor) {}

    /** A line of the order: {@code remaining} is what is still to arrive. */
    public record Line(int lineNo, Category category, String item, int quantity, long unitPriceMinor, int remaining) {

        Line received(int arrived) {
            return new Line(lineNo, category, item, quantity, unitPriceMinor, remaining - arrived);
        }
    }

    /** A fact: that much of a line arrived with that delivery reference, said by that person or tool. Never edited. */
    public record Receipt(int lineNo, String deliveryRef, int quantity, Instant receivedAt, String reportedBy) {}

    public record Received(Receipt receipt, boolean created, AuditEntry entry) {}

    public record AuditEntry(Instant occurredAt, String action, String executor, Status fromStatus, Status toStatus, String detail) {}

    /** The judgment of one moment: nothing of it is stored. */
    public record Assessment(Fulfilment fulfilment, boolean late) {}
}
