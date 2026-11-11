package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An approval band (ADR-036): in this currency, from this amount up, the order is sent for approval against this policy. */
@Serdeable
public record ApprovalBandResponse(UUID id, UUID organisationId, String currency, BigDecimal fromAmount, UUID policyId, String status, Instant createdAt, String createdBy,
                                   @Nullable Instant retiredAt, @Nullable String retiredBy) {}
