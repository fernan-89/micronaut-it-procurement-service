package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateApprovalBandRequest;
import com.thinklab.application.dto.response.ApprovalBandResponse;
import com.thinklab.application.mapper.ApprovalBandMapper;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.ApprovalBandRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/** Use Case for making an approval band (BIAN Behavior Qualifier: {@code band/initiate}, ADR-036). Staff only. */
@Singleton
public class InitiateApprovalBandUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateApprovalBandUseCase.class);

    private final HashServicePort hashServicePort;
    private final ApprovalBandRepository repository;
    private final Clock clock;

    public InitiateApprovalBandUseCase(HashServicePort hashServicePort, ApprovalBandRepository repository, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<ApprovalBandResponse> execute(UUID organisationId, InitiateApprovalBandRequest request, String executor, String role) {
        log.info("[USE CASE] Making an approval band for organisation: {}", organisationId);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "make an approval band"))
                .then(Mono.fromCallable(() -> Money.toMinor(request.fromAmount())))
                .flatMap(fromMinor -> hashServicePort.generateSovereignId("approval-band-creation")
                        .map(id -> ApprovalBand.createNew(id, organisationId, request.currency(), fromMinor, request.policyId(), executor, clock.instant())))
                .flatMap(repository::create)
                .map(ApprovalBandMapper::toResponse);
    }
}
