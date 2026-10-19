package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReceiveRequest;
import com.thinklab.application.dto.response.ReceiptResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.PurchaseOrder.Received;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Use Case for people saying that goods arrived (BIAN Behavior Qualifier: {@code receipt/initiate}), ADR-033. Idempotent: the same line and delivery reference
 * with the same quantity is the same receipt (the stored one comes back, {@code created} false), so a tool or a person may retry; the same reference with
 * another quantity is a 409. The store applies a receipt in one atomic update that checks the order is placed and that the line still has that much to arrive,
 * so two people receiving at once can never receive more than was ordered; when it refuses (someone else got there first) the order is read again and judged
 * again, which either finds the receipt already there or says exactly what is wrong. Only one retry is made: a second refusal is a 409 asking the caller to retry.
 */
@Singleton
public class ReportReceiptUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportReceiptUseCase.class);

    /** The receipt as stored, and whether this call created it (201) or found it already there (200). */
    public record Reported(ReceiptResponse receipt, boolean created) {
    }

    private final PurchaseOrderRepository repository;
    private final Clock clock;

    public ReportReceiptUseCase(PurchaseOrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<Reported> execute(UUID id, UUID organisationId, ReceiveRequest request, String executor, String role) {
        log.info("[USE CASE] Receiving goods on PurchaseOrder ID: {}", id);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "receive goods"))
                .then(Mono.defer(() -> attempt(id, organisationId, request, executor, true)));
    }

    private Mono<Reported> attempt(UUID id, UUID organisationId, ReceiveRequest request, String executor, boolean mayRetry) {
        return repository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new PurchaseOrderNotFoundException("PurchaseOrder " + id + " not found.")))
                .flatMap(order -> {
                    Received received = order.receive(request.lineNo(), request.deliveryRef(), request.quantity(), executor, clock.instant());
                    if (!received.created()) {
                        return Mono.just(new Reported(ProcurementMapper.toResponse(received.receipt()), false));
                    }
                    return repository.receive(id, organisationId, received.receipt(), received.entry())
                            .flatMap(applied -> {
                                if (applied) {
                                    return Mono.just(new Reported(ProcurementMapper.toResponse(received.receipt()), true));
                                }
                                return mayRetry ? attempt(id, organisationId, request, executor, false)
                                        : Mono.error(new InvalidPurchaseOrderStatusException("The purchase order was changed by someone else while the receipt was recorded; read it again and retry."));
                            });
                });
    }
}
