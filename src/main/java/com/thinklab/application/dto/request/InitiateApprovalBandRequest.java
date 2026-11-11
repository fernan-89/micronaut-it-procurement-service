package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Money.Currency;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

/** DTO for {@code band/initiate} (ADR-036): from this amount up, in this currency, send the order for approval against this policy. Nothing in words. */
@Serdeable
public record InitiateApprovalBandRequest(
        @NotNull(message = "The currency is required") Currency currency,
        @NotNull(message = "The lower bound is required") @PositiveOrZero(message = "The lower bound cannot be negative") BigDecimal fromAmount,
        @NotNull(message = "The approval policy is required") UUID policyId
) {}
