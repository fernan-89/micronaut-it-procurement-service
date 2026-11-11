package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ApprovalBandResponse;
import com.thinklab.application.mapper.ApprovalBandMapper;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.repository.ApprovalBandRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** Use Case for the tenant's active approval bands, lowest bound first, optionally of one currency (BIAN Behavior Qualifier: {@code band/retrieve}, ADR-036). */
@Singleton
public class RetrieveApprovalBandsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveApprovalBandsUseCase.class);

    private final ApprovalBandRepository repository;

    public RetrieveApprovalBandsUseCase(ApprovalBandRepository repository) {
        this.repository = repository;
    }

    public Flux<ApprovalBandResponse> execute(UUID organisationId, Currency currency, String role) {
        log.info("[USE CASE] Retrieving the approval bands of organisation: {}", organisationId);
        return Flux.defer(() -> {
            ProcurementAccess.requireStaff(role, "read the approval bands");
            return repository.findActive(organisationId, currency);
        }).map(ApprovalBandMapper::toResponse);
    }
}
