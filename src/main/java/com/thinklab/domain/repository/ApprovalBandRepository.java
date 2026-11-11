package com.thinklab.domain.repository;

import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.Money.Currency;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Outbound Port for approval band persistence (ADR-036). Every lookup is tenant-scoped. */
public interface ApprovalBandRepository {

    /** Inserts the band. Another active band of the currency with the same lower bound is {@link com.thinklab.domain.exception.DuplicateApprovalBandException}: a unique partial index is the atomic backstop. */
    Mono<ApprovalBand> create(ApprovalBand band);

    Mono<ApprovalBand> findById(UUID id, UUID organisationId);

    /** The tenant's active bands, lowest bound first; {@code currency} narrows them to one currency when it is not null. */
    Flux<ApprovalBand> findActive(UUID organisationId, Currency currency);

    /** Retires the band in one atomic update guarded on it still being active; answers whether it was applied. */
    Mono<Boolean> retire(UUID id, UUID organisationId, String executor, Instant at);
}
