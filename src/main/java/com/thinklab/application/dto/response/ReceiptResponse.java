package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record ReceiptResponse(int lineNo, String deliveryRef, int quantity, Instant receivedAt, String reportedBy) {}
