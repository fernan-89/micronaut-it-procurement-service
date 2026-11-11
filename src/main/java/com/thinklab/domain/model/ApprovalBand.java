package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate Root of an approval band (ADR-036): for purchase orders of one {@link Currency}, from {@code fromMinor} up, send the order for approval against
 * {@code policyId}. A band has only a lower bound, so the bands of a currency can neither overlap nor leave a gap above the lowest one. A band is never edited or
 * deleted: it is retired, and a new one is made. It holds numbers and an id, never words.
 */
public class ApprovalBand {

    private final UUID id;
    private final UUID organisationId;
    private final Currency currency;
    private final long fromMinor;
    private final UUID policyId;
    private Status status;
    private final Instant createdAt;
    private final String createdBy;
    private Instant retiredAt;
    private String retiredBy;

    private ApprovalBand(UUID id, UUID organisationId, Currency currency, long fromMinor, UUID policyId, Status status, Instant createdAt, String createdBy,
                         Instant retiredAt, String retiredBy) {
        this.id = id;
        this.organisationId = organisationId;
        this.currency = currency;
        this.fromMinor = fromMinor;
        this.policyId = policyId;
        this.status = status;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.retiredAt = retiredAt;
        this.retiredBy = retiredBy;
    }

    public static ApprovalBand createNew(UUID id, UUID organisationId, Currency currency, long fromMinor, UUID policyId, String executor, Instant now) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for ApprovalBand creation.");
        }
        if (currency == null || policyId == null) {
            throw new IllegalArgumentException("The currency and the approval policy of a band are mandatory.");
        }
        if (fromMinor < 0 || fromMinor > Money.MAX_MINOR) {
            throw new IllegalArgumentException("The lower bound of a band must be zero or more, and not absurdly large.");
        }
        requireExecutor(executor);
        return new ApprovalBand(id, organisationId, currency, fromMinor, policyId, Status.ACTIVE, now, executor, null, null);
    }

    public static ApprovalBand reconstitute(UUID id, UUID organisationId, Currency currency, long fromMinor, UUID policyId, Status status, Instant createdAt, String createdBy,
                                            Instant retiredAt, String retiredBy) {
        if (id == null || organisationId == null || currency == null || policyId == null || status == null || createdAt == null || createdBy == null) {
            throw new IllegalArgumentException("Every field but the retirement is mandatory to reconstitute an ApprovalBand.");
        }
        return new ApprovalBand(id, organisationId, currency, fromMinor, policyId, status, createdAt, createdBy, retiredAt, retiredBy);
    }

    /** Behavior Qualifier: {@code control/retire}. ACTIVE -&gt; RETIRED (final); the lower bound can then be used by another band. */
    public void retire(String executor, Instant now) {
        if (status != Status.ACTIVE) {
            throw new InvalidPurchaseOrderStatusException("Illegal transition: ApprovalBand is [" + status + "]. Only an active band can be retired.");
        }
        requireExecutor(executor);
        this.status = Status.RETIRED;
        this.retiredAt = now;
        this.retiredBy = executor;
    }

    /**
     * The band that applies to an order of {@code totalMinor}: among the active bands given (all of the order's currency), the one with the greatest lower bound that
     * is not above the total. Empty when none covers it (none given, or the total is below the lowest bound).
     */
    public static Optional<ApprovalBand> select(List<ApprovalBand> bands, long totalMinor) {
        return bands.stream().filter(band -> band.status == Status.ACTIVE && band.fromMinor <= totalMinor).max(Comparator.comparingLong(band -> band.fromMinor));
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable ApprovalBand mutations.");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public Currency getCurrency() { return currency; }
    public long getFromMinor() { return fromMinor; }
    public UUID getPolicyId() { return policyId; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getRetiredAt() { return retiredAt; }
    public String getRetiredBy() { return retiredBy; }

    public enum Status { ACTIVE, RETIRED }
}
