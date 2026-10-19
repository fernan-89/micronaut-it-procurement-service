package com.thinklab.application.dto.request;

import com.thinklab.domain.model.PurchaseOrder.Category;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** One line of a purchase order: a category from a fixed list, an item identifier (a part number or a SKU, never a sentence), a quantity and a unit price with at most two decimal places. */
@Serdeable
public record LineRequest(
        @NotNull(message = "The category is required") Category category,
        @NotBlank(message = "The item is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,63}", message = "The item must be up to 64 letters, digits, dots, dashes, underscores or slashes")
        String item,
        @NotNull(message = "The quantity is required") @Min(value = 1, message = "The quantity must be at least 1") @Max(value = 100000, message = "The quantity must not exceed 100000")
        Integer quantity,
        @NotNull(message = "The unit price is required") @PositiveOrZero(message = "The unit price cannot be negative")
        BigDecimal unitPrice
) {}
