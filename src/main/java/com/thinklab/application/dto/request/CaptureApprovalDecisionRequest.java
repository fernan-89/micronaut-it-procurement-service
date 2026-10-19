package com.thinklab.application.dto.request;

import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** DTO for {@code approval/capture}: which approver decides, and the decision. Nothing in words travels with it. */
@Serdeable
public record CaptureApprovalDecisionRequest(
        @NotNull(message = "The approver is required") UUID approverId,
        @NotNull(message = "The decision is required") DecisionOutcome outcome
) {}
