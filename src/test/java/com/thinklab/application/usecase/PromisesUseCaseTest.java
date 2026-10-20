package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.exception.ProcurementAccessDeniedException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromisesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");
    private final UUID org = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PurchaseOrderRepository repository = mock(PurchaseOrderRepository.class);
    private final RetrievePromisesUseCase useCase = new RetrievePromisesUseCase(repository, clock);

    /** An order in that status, expected on that day, with that much of the 10 received, and last changed at that moment (as the store would give it back). */
    private PurchaseOrder order(String reference, Status status, LocalDate expectedOn, int received, Instant updatedAt) {
        PurchaseOrder built = PurchaseOrder.createNew(UUID.randomUUID(), org, reference, UUID.randomUUID(), UUID.randomUUID(), Currency.BRL, List.of(new NewLine(Category.HARDWARE, "LAP", 10, 100)),
                expectedOn, TODAY.minusDays(60), "b");
        if (status == Status.ORDERED || status == Status.CANCELLED) {
            built.submit(UUID.randomUUID(), "b");
            built.approve("cab");
            built.place("b", NOW.minus(Duration.ofDays(40)));
            if (received > 0) {
                built.receive(1, "DN-1", received, "dock", NOW.minus(Duration.ofDays(30)));
            }
            if (status == Status.CANCELLED) {
                built.cancel("b");
            }
        }
        return PurchaseOrder.reconstitute(built.getId(), org, built.getReference(), built.getVendorId(), built.getRequesterId(), built.getCurrency(), built.getLines(), built.getExpectedOn(),
                built.getStatus(), built.getApprovalRequestId(), built.getCreatedAt(), updatedAt, built.getOrderedAt(), built.getReceipts(), built.getAuditTrail());
    }

    private void reads(List<PurchaseOrder> late, List<PurchaseOrder> changed) {
        when(repository.findAll(eq(org), eq(Status.ORDERED), any(), any(), eq(TODAY), any(), anyInt())).thenReturn(Flux.fromIterable(late));
        when(repository.findAll(eq(org), any(), any(), any(), any(), any(Instant.class), anyInt())).thenReturn(Flux.fromIterable(changed));
    }

    @Test
    @DisplayName("a placed order past its expected day is broken with PURCHASE_ORDER_LATE; one received completely, cancelled or drafted lately is listed too (kept, kept with the status CANCELLED, not judged); one in both reads once; one that did not change within the window is left out")
    void selects() {
        PurchaseOrder late = order("PO-LATE", Status.ORDERED, TODAY.minusDays(3), 0, NOW.minus(Duration.ofDays(40)));
        PurchaseOrder complete = order("PO-DONE", Status.ORDERED, TODAY.minusDays(3), 10, NOW.minus(Duration.ofHours(1)));
        PurchaseOrder stale = order("PO-STALE", Status.ORDERED, TODAY.minusDays(3), 10, NOW.minus(Duration.ofDays(40)));
        PurchaseOrder cancelled = order("PO-CANCELLED", Status.CANCELLED, TODAY.minusDays(3), 0, NOW.minus(Duration.ofHours(2)));
        PurchaseOrder draft = order("PO-DRAFT", Status.DRAFT, null, 0, NOW.minus(Duration.ofHours(3)));
        reads(List.of(late, stale, complete), List.of(draft, cancelled, late, complete));

        StepVerifier.create(useCase.execute(org, null, null, "ENGINEER")).assertNext(promises -> {
            assertEquals(List.of("PO-LATE", "PO-DONE", "PO-DRAFT", "PO-CANCELLED"), promises.stream().map(PromiseResponse::name).toList());
            assertEquals("BROKEN", promises.get(0).verdict());
            assertEquals("PURCHASE_ORDER_LATE", promises.get(0).code());
            assertNull(promises.get(0).assetId());
            assertEquals("ACTIVE", promises.get(0).status());
            assertEquals("KEPT", promises.get(1).verdict());
            assertEquals("NOT_JUDGED", promises.get(2).verdict());
            assertEquals("KEPT", promises.get(3).verdict());
            assertEquals("CANCELLED", promises.get(3).status());
            assertEquals(NOW.minus(Duration.ofHours(2)), promises.get(3).changedAt());
        }).verifyComplete();
        verify(repository).findAll(org, Status.ORDERED, null, null, TODAY, null, RetrievePurchaseOrdersUseCase.JUDGED);
        verify(repository).findAll(org, null, null, null, null, NOW.minus(Duration.ofDays(30)), 500);
    }

    @Test
    @DisplayName("the window and the limit are the ones asked and are given to the store")
    void window() {
        reads(List.of(), List.of());

        StepVerifier.create(useCase.execute(org, 7, 50, "ENGINEER")).expectNext(List.of()).verifyComplete();

        verify(repository).findAll(org, null, null, null, null, NOW.minus(Duration.ofDays(7)), 50);
    }

    @Test
    @DisplayName("the days must be 1 to 365 and the limit 1 to 1000; a requester is refused")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 0, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 366, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 0, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 1001, "ENGINEER").block());
        assertThrows(ProcurementAccessDeniedException.class, () -> useCase.execute(org, null, null, "REQUESTER").block());
    }
}
