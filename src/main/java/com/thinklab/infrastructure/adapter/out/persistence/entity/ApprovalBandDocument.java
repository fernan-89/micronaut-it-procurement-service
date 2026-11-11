package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.ApprovalBand.Status;
import com.thinklab.domain.model.Money.Currency;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/** Infrastructure-specific representation of the ApprovalBand Aggregate for MongoDB (ADR-036). The lower bound is in minor units, a whole number. */
@Introspected
public class ApprovalBandDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private String currency;
    private long fromMinor;
    private UUID policyId;
    private String status;
    private Instant createdAt;
    private String createdBy;
    private Instant retiredAt;
    private String retiredBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getFromMinor() { return fromMinor; }
    public void setFromMinor(long fromMinor) { this.fromMinor = fromMinor; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getRetiredAt() { return retiredAt; }
    public void setRetiredAt(Instant retiredAt) { this.retiredAt = retiredAt; }
    public String getRetiredBy() { return retiredBy; }
    public void setRetiredBy(String retiredBy) { this.retiredBy = retiredBy; }

    public static final class ApprovalBandPersistenceMapper {

        private ApprovalBandPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static ApprovalBandDocument toDocument(ApprovalBand band) {
            ApprovalBandDocument doc = new ApprovalBandDocument();
            doc.setId(band.getId());
            doc.setOrganisationId(band.getOrganisationId());
            doc.setCurrency(band.getCurrency().name());
            doc.setFromMinor(band.getFromMinor());
            doc.setPolicyId(band.getPolicyId());
            doc.setStatus(band.getStatus().name());
            doc.setCreatedAt(band.getCreatedAt());
            doc.setCreatedBy(band.getCreatedBy());
            doc.setRetiredAt(band.getRetiredAt());
            doc.setRetiredBy(band.getRetiredBy());
            return doc;
        }

        public static ApprovalBand toDomain(ApprovalBandDocument doc) {
            return ApprovalBand.reconstitute(doc.getId(), doc.getOrganisationId(), Currency.valueOf(doc.getCurrency()), doc.getFromMinor(), doc.getPolicyId(),
                    Status.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getCreatedBy(), doc.getRetiredAt(), doc.getRetiredBy());
        }
    }
}
