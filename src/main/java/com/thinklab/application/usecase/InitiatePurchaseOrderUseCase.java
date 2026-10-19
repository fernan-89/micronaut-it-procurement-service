package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiatePurchaseOrderRequest;
import com.thinklab.application.dto.request.LineRequest;
import com.thinklab.application.dto.response.PurchaseOrderResponse;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Use Case for drafting a purchase order (BIAN Behavior Qualifier: {@code initiate}). Staff only. */
@Singleton
public class InitiatePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiatePurchaseOrderUseCase.class);

    private final HashServicePort hashServicePort;
    private final PurchaseOrderRepository repository;
    private final Clock clock;

    public InitiatePurchaseOrderUseCase(HashServicePort hashServicePort, PurchaseOrderRepository repository, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.clock = clock;
    }

    /** The lines as the aggregate wants them: prices in minor units. */
    static List<NewLine> toNewLines(List<LineRequest> lines) {
        return lines.stream().map(line -> new NewLine(line.category(), line.item(), line.quantity(), Money.toMinor(line.unitPrice()))).toList();
    }

    static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public Mono<PurchaseOrderResponse> execute(UUID organisationId, InitiatePurchaseOrderRequest request, String executor, String role) {
        log.info("[USE CASE] Drafting the PurchaseOrder [{}] for organisation: {}", request.reference(), organisationId);
        return Mono.fromRunnable(() -> ProcurementAccess.requireStaff(role, "draft a purchase order"))
                .then(Mono.fromCallable(() -> toNewLines(request.lines())))
                .flatMap(lines -> hashServicePort.generateSovereignId("purchase-order-creation")
                        .map(id -> PurchaseOrder.createNew(id, organisationId, request.reference(), request.vendorId(), request.requesterId(), request.currency(), lines,
                                request.expectedOn(), today(clock), executor)))
                .flatMap(repository::create)
                .map(order -> ProcurementMapper.toResponse(order, today(clock)));
    }
}
