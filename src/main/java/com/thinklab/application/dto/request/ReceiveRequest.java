package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** DTO for {@code receipt/initiate}: that much of a line arrived, with the reference of the delivery (a delivery note number: an identifier, never a sentence). */
@Serdeable
public record ReceiveRequest(
        @NotNull(message = "The line is required") @Min(value = 1, message = "The line number must be at least 1") @Max(value = 50, message = "The line number must not exceed 50")
        Integer lineNo,
        @NotBlank(message = "The delivery reference is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,39}", message = "The delivery reference must be up to 40 letters, digits, dots, dashes, underscores or slashes")
        String deliveryRef,
        @NotNull(message = "The quantity is required") @Min(value = 1, message = "The quantity must be at least 1") @Max(value = 100000, message = "The quantity must not exceed 100000")
        Integer quantity
) {}
