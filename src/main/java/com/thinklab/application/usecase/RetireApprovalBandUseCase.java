package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ApprovalBandNotFoundException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.repository.ApprovalBandRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Use Case for retiring an approval band (BIAN Behavior Qualifier: {@code band/{id}/control/retire}, ADR-036). The aggregate says whether it can be retired; the write
 * is guarded on the band still being active, so two people retiring it at once get one winner and one 409.
 */
@Singleton
public class RetireApprovalBandUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetireApprovalBandUseCase.class);

    private final ApprovalBandRepository repository;
    private final Clock clock;

    public RetireApprovalBandUseCase(ApprovalBandRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Retiring the ApprovalBand ID: {}", id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "retire an approval band"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ApprovalBandNotFoundException("ApprovalBand " + id + " not found.")))
                .flatMap(band -> {
                    band.retire(executor, clock.instant());
                    return repository.retire(id, organisationId, executor, band.getRetiredAt());
                })
                .flatMap(applied -> applied ? Mono.<Void>empty()
                        : Mono.error(new InvalidPurchaseOrderStatusException("ApprovalBand was changed by someone else while it was being retired; read it again and retry.")));
    }
}
