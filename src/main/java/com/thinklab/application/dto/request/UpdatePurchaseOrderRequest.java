package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTO for {@code update} of a draft: the vendor, the lines (all of them again) and the expected day. The reference, the requester and the currency stay. */
@Serdeable
public record UpdatePurchaseOrderRequest(
        @NotNull(message = "The vendor is required") UUID vendorId,
        @Nullable LocalDate expectedOn,
        @NotNull(message = "The lines are required") @Size(max = 50, message = "A purchase order has up to 50 lines") @Valid List<LineRequest> lines
) {}
