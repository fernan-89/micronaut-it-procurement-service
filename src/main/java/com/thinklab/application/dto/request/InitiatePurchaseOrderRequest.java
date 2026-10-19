package com.thinklab.application.dto.request;

import com.thinklab.domain.model.Money.Currency;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTO for {@code initiate}: a purchase order drafted for a vendor (a vendor registry id, not checked) by a requester, in one currency, with its lines and, if known,
 * the day the goods are expected. The reference is the organisation's own number for the order: an identifier, unique per organisation.
 */
@Serdeable
public record InitiatePurchaseOrderRequest(
        @NotBlank(message = "The reference is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,39}", message = "The reference must be up to 40 letters, digits, dots, dashes, underscores or slashes")
        String reference,
        @NotNull(message = "The vendor is required") UUID vendorId,
        @NotNull(message = "The requester is required") UUID requesterId,
        @NotNull(message = "The currency is required") Currency currency,
        @Nullable LocalDate expectedOn,
        @NotNull(message = "The lines are required") @Size(max = 50, message = "A purchase order has up to 50 lines") @Valid List<LineRequest> lines
) {}
