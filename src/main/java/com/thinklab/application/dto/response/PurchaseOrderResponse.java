package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A purchase order as it stands, with what was received and whether the delivery is late, judged now. */
@Serdeable
public record PurchaseOrderResponse(UUID id, UUID organisationId, String reference, UUID vendorId, UUID requesterId, String currency, List<LineResponse> lines, BigDecimal total,
                                    @Nullable LocalDate expectedOn, String status, @Nullable UUID approvalRequestId, String fulfilment, boolean late, @Nullable Instant orderedAt,
                                    Instant createdAt, Instant updatedAt) {

    @Serdeable
    public record LineResponse(int lineNo, String category, String item, int quantity, BigDecimal unitPrice, int received, int remaining) {}
}
