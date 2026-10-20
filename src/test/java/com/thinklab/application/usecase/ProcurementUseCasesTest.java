package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.CaptureApprovalDecisionRequest;
import com.thinklab.application.dto.request.InitiatePurchaseOrderRequest;
import com.thinklab.application.dto.request.LineRequest;
import com.thinklab.application.dto.request.ReceiveRequest;
import com.thinklab.application.dto.request.UpdatePurchaseOrderRequest;
import com.thinklab.application.mapper.ProcurementMapper;
import com.thinklab.domain.exception.ApprovalUnavailableException;
import com.thinklab.domain.exception.DuplicatePurchaseOrderException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.exception.ProcurementAccessDeniedException;
import com.thinklab.domain.exception.PurchaseOrderNotFoundException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.Fulfilment;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.port.ApprovalServicePort;
import com.thinklab.domain.port.ApprovalServicePort.ApprovalOutcome;
import com.thinklab.domain.port.ApprovalServicePort.DecisionOutcome;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcurementUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final UUID POLICY = UUID.randomUUID();

    private final UUID org = UUID.randomUUID();
    private final UUID vendor = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HashServicePort hash;
    private PurchaseOrderRepository repository;
    private ApprovalServicePort approval;
    private PurchaseOrderWorkflow workflow;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        repository = mock(PurchaseOrderRepository.class);
        approval = mock(ApprovalServicePort.class);
        when(hash.generateSovereignId(any())).thenAnswer(call -> Mono.just(UUID.randomUUID()));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        workflow = new PurchaseOrderWorkflow(repository);
    }

    private List<NewLine> lines() {
        return List.of(new NewLine(Category.HARDWARE, "LAP-14", 10, 150_000), new NewLine(Category.SOFTWARE, "OFFICE-E3", 5, 2_000));
    }

    private PurchaseOrder draft() {
        PurchaseOrder order = PurchaseOrder.createNew(UUID.randomUUID(), org, "PO-1", vendor, requester, Currency.BRL, lines(), TODAY.plusDays(30), TODAY, "buyer");
        when(repository.findById(order.getId(), org)).thenReturn(Mono.just(order));
        return order;
    }

    private PurchaseOrder placed() {
        PurchaseOrder order = draft();
        order.submit(UUID.randomUUID(), "b");
        order.approve("cab");
        order.place("b", NOW);
        return order;
    }

    /** A fresh copy of a placed order, as the store would give it each time it is read. */
    private PurchaseOrder placedWithId(UUID id) {
        PurchaseOrder order = PurchaseOrder.createNew(id, org, "PO-1", vendor, requester, Currency.BRL, lines(), TODAY.plusDays(30), TODAY, "buyer");
        order.submit(UUID.randomUUID(), "b");
        order.approve("cab");
        order.place("b", NOW);
        return order;
    }

    private InitiatePurchaseOrderRequest initiateRequest() {
        return new InitiatePurchaseOrderRequest("PO-1", vendor, requester, Currency.BRL, TODAY.plusDays(30),
                List.of(new LineRequest(Category.HARDWARE, "LAP-14", 10, new BigDecimal("1500.00")), new LineRequest(Category.SOFTWARE, "OFFICE-E3", 5, new BigDecimal("20"))));
    }

    @Test
    @DisplayName("an order is drafted with a sovereign id, the lines in minor units, the total and nothing received; a requester is refused and a bad price is refused before an id is spent")
    void initiate() {
        InitiatePurchaseOrderUseCase initiate = new InitiatePurchaseOrderUseCase(hash, repository, clock);

        StepVerifier.create(initiate.execute(org, initiateRequest(), "buyer", STAFF)).assertNext(response -> {
            assertEquals("DRAFT", response.status());
            assertEquals("PO-1", response.reference());
            assertEquals("BRL", response.currency());
            assertEquals(new BigDecimal("15100.00"), response.total());
            assertEquals(2, response.lines().size());
            assertEquals(new BigDecimal("1500.00"), response.lines().get(0).unitPrice());
            assertEquals("NOT_ORDERED", response.fulfilment());
            assertFalse(response.late());
        }).verifyComplete();
        verify(hash).generateSovereignId("purchase-order-creation");
        assertThrows(ProcurementAccessDeniedException.class, () -> initiate.execute(org, initiateRequest(), "buyer", REQUESTER).block());
        InitiatePurchaseOrderRequest bad = new InitiatePurchaseOrderRequest("PO-2", vendor, requester, Currency.BRL, null, List.of(new LineRequest(Category.OTHER, "X", 1, new BigDecimal("1.005"))));
        assertThrows(IllegalArgumentException.class, () -> initiate.execute(org, bad, "buyer", STAFF).block());
        verify(hash, times(1)).generateSovereignId(any());
    }

    @Test
    @DisplayName("a duplicate reference is the repository's conflict")
    void duplicate() {
        doReturn(Mono.error(new DuplicatePurchaseOrderException("dup"))).when(repository).create(any());

        StepVerifier.create(new InitiatePurchaseOrderUseCase(hash, repository, clock).execute(org, initiateRequest(), "buyer", STAFF)).expectError(DuplicatePurchaseOrderException.class).verify();
    }

    @Test
    @DisplayName("a draft is updated through the guarded workflow; another tenant's order is a 404 and a requester is refused")
    void update() {
        PurchaseOrder order = draft();
        UpdatePurchaseOrderUseCase update = new UpdatePurchaseOrderUseCase(workflow, clock);
        UpdatePurchaseOrderRequest request = new UpdatePurchaseOrderRequest(vendor, null, List.of(new LineRequest(Category.CLOUD, "RI-1Y", 2, new BigDecimal("500"))));
        when(repository.findById(any(UUID.class), eq(UUID.randomUUID()))).thenReturn(Mono.empty());

        StepVerifier.create(update.execute(order.getId(), org, request, "buyer", STAFF)).verifyComplete();

        verify(repository).save(eq(order), eq(Status.DRAFT), any());
        assertEquals(1, order.getLines().size());
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(update.execute(UUID.randomUUID(), org, request, "buyer", STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> update.execute(order.getId(), org, request, "buyer", REQUESTER).block());
    }

    @Test
    @DisplayName("placing and cancelling go through the workflow with the status loaded")
    void control() {
        PurchaseOrder order = draft();
        order.submit(UUID.randomUUID(), "b");
        order.approve("cab");
        ControlPurchaseOrderUseCase control = new ControlPurchaseOrderUseCase(workflow, clock);

        StepVerifier.create(control.execute(order.getId(), org, ControlPurchaseOrderUseCase.Action.PLACE, "buyer", STAFF)).verifyComplete();
        verify(repository).save(eq(order), eq(Status.APPROVED), any());
        assertEquals(NOW, order.getOrderedAt());
        StepVerifier.create(control.execute(order.getId(), org, ControlPurchaseOrderUseCase.Action.CANCEL, "buyer", STAFF)).verifyComplete();
        verify(repository).save(eq(order), eq(Status.ORDERED), any());
        assertEquals(Status.CANCELLED, order.getStatus());
        assertThrows(ProcurementAccessDeniedException.class, () -> control.execute(order.getId(), org, ControlPurchaseOrderUseCase.Action.CANCEL, "buyer", REQUESTER).block());
    }

    @Test
    @DisplayName("sending for approval files the request with the policy, keeps its id and moves the order; nothing is filed for an order that cannot go, or when no policy is set")
    void submit() {
        PurchaseOrder order = draft();
        UUID filed = UUID.randomUUID();
        when(approval.initiateApprovalRequest(org, order.getId(), requester, POLICY, "buyer")).thenReturn(Mono.just(filed));
        SubmitPurchaseOrderUseCase submit = new SubmitPurchaseOrderUseCase(repository, approval, POLICY.toString());

        StepVerifier.create(submit.execute(order.getId(), org, "buyer", STAFF)).verifyComplete();

        assertEquals(Status.PENDING_APPROVAL, order.getStatus());
        assertEquals(filed, order.getApprovalRequestId());
        verify(repository).save(eq(order), eq(Status.DRAFT), any());
        verify(approval, times(1)).initiateApprovalRequest(any(), any(), any(), any(), any());
        StepVerifier.create(submit.execute(order.getId(), org, "buyer", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        verify(approval, times(1)).initiateApprovalRequest(any(), any(), any(), any(), any());
        PurchaseOrder another = draft();
        StepVerifier.create(new SubmitPurchaseOrderUseCase(repository, approval, " ").execute(another.getId(), org, "buyer", STAFF)).expectError(ApprovalUnavailableException.class).verify();
        StepVerifier.create(new SubmitPurchaseOrderUseCase(repository, approval, null).execute(another.getId(), org, "buyer", STAFF)).expectError(ApprovalUnavailableException.class).verify();
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(submit.execute(UUID.randomUUID(), org, "buyer", STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> submit.execute(order.getId(), org, "buyer", REQUESTER).block());
    }

    @Test
    @DisplayName("an approval service that is down leaves the order a draft")
    void approvalDown() {
        PurchaseOrder order = draft();
        when(approval.initiateApprovalRequest(any(), any(), any(), any(), any())).thenReturn(Mono.error(new ApprovalUnavailableException("down")));

        StepVerifier.create(new SubmitPurchaseOrderUseCase(repository, approval, POLICY.toString()).execute(order.getId(), org, "buyer", STAFF)).expectError(ApprovalUnavailableException.class).verify();

        assertEquals(Status.DRAFT, order.getStatus());
        verify(repository, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("a decision is forwarded as the approver; approved or rejected moves the order, pending leaves it waiting, and only an order waiting takes a decision")
    void capture() {
        CaptureApprovalDecisionUseCase capture = new CaptureApprovalDecisionUseCase(repository, approval, clock);
        UUID approver = UUID.randomUUID();
        PurchaseOrder order = draft();
        UUID request = UUID.randomUUID();
        order.submit(request, "b");
        CaptureApprovalDecisionRequest approve = new CaptureApprovalDecisionRequest(approver, DecisionOutcome.APPROVE);
        when(approval.captureDecision(request, DecisionOutcome.APPROVE, approver.toString())).thenReturn(Mono.just(ApprovalOutcome.PENDING), Mono.just(ApprovalOutcome.APPROVED));

        StepVerifier.create(capture.execute(order.getId(), org, approve, "buyer", STAFF)).assertNext(response -> assertEquals("PENDING_APPROVAL", response.status())).verifyComplete();
        verify(repository, never()).save(any(), any(), any());
        StepVerifier.create(capture.execute(order.getId(), org, approve, "buyer", STAFF)).assertNext(response -> assertEquals("APPROVED", response.status())).verifyComplete();
        verify(repository).save(eq(order), eq(Status.PENDING_APPROVAL), any());

        PurchaseOrder other = draft();
        UUID otherRequest = UUID.randomUUID();
        other.submit(otherRequest, "b");
        when(approval.captureDecision(otherRequest, DecisionOutcome.REJECT, approver.toString())).thenReturn(Mono.just(ApprovalOutcome.REJECTED));
        StepVerifier.create(capture.execute(other.getId(), org, new CaptureApprovalDecisionRequest(approver, DecisionOutcome.REJECT), "buyer", STAFF))
                .assertNext(response -> assertEquals("REJECTED", response.status())).verifyComplete();

        UUID strayId = UUID.randomUUID();
        when(repository.findById(strayId, org)).thenReturn(Mono.just(PurchaseOrder.reconstitute(strayId, org, "PO-S", vendor, requester, Currency.BRL, List.of(), null, Status.PENDING_APPROVAL, null, null, null, null, null, null)));
        StepVerifier.create(capture.execute(strayId, org, approve, "buyer", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        PurchaseOrder notWaiting = draft();
        StepVerifier.create(capture.execute(notWaiting.getId(), org, approve, "buyer", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(capture.execute(UUID.randomUUID(), org, approve, "buyer", STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> capture.execute(order.getId(), org, approve, "buyer", REQUESTER).block());
    }

    @Test
    @DisplayName("goods are received through the store's atomic update; the same receipt again comes back as it was without touching the store")
    void receive() {
        PurchaseOrder order = placed();
        when(repository.receive(eq(order.getId()), eq(org), any(), any())).thenReturn(Mono.just(true));
        ReportReceiptUseCase receive = new ReportReceiptUseCase(repository, clock);
        ReceiveRequest request = new ReceiveRequest(1, "DN-1", 4);

        StepVerifier.create(receive.execute(order.getId(), org, request, "dock", STAFF)).assertNext(reported -> {
            assertTrue(reported.created());
            assertEquals(4, reported.receipt().quantity());
            assertEquals("DN-1", reported.receipt().deliveryRef());
        }).verifyComplete();
        verify(repository, times(1)).receive(any(), any(), any(), any());
        order.receive(1, "DN-1", 4, "dock", NOW);
        StepVerifier.create(receive.execute(order.getId(), org, request, "dock", STAFF)).assertNext(reported -> assertFalse(reported.created())).verifyComplete();
        verify(repository, times(1)).receive(any(), any(), any(), any());
    }

    @Test
    @DisplayName("when the store refuses because someone got there first, the order is read again once: the other's receipt is found, or the exact refusal is given; a second refusal asks to retry")
    void receiveRace() {
        PurchaseOrder order = placed();
        ReceiveRequest request = new ReceiveRequest(1, "DN-1", 6);
        ReportReceiptUseCase receive = new ReportReceiptUseCase(repository, clock);
        when(repository.findById(order.getId(), org)).thenReturn(Mono.just(order), Mono.defer(() -> {
            order.receive(1, "DN-1", 6, "other", NOW);
            return Mono.just(order);
        }));
        when(repository.receive(any(), any(), any(), any())).thenReturn(Mono.just(false));

        StepVerifier.create(receive.execute(order.getId(), org, request, "dock", STAFF)).assertNext(reported -> assertFalse(reported.created())).verifyComplete();

        UUID busyId = UUID.randomUUID();
        when(repository.findById(busyId, org)).thenAnswer(call -> Mono.just(placedWithId(busyId)));
        when(repository.receive(eq(busyId), any(), any(), any())).thenReturn(Mono.just(false));
        StepVerifier.create(receive.execute(busyId, org, request, "dock", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        verify(repository, times(2)).receive(eq(busyId), any(), any(), any());
    }

    @Test
    @DisplayName("a receipt for an order that is not placed, more than is left, a requester, or another tenant's order is refused")
    void receiveRefusals() {
        ReportReceiptUseCase receive = new ReportReceiptUseCase(repository, clock);
        PurchaseOrder notPlaced = draft();
        StepVerifier.create(receive.execute(notPlaced.getId(), org, new ReceiveRequest(1, "DN-1", 1), "dock", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        PurchaseOrder order = placed();
        StepVerifier.create(receive.execute(order.getId(), org, new ReceiveRequest(1, "DN-1", 11), "dock", STAFF)).expectError(InvalidPurchaseOrderStatusException.class).verify();
        verify(repository, never()).receive(any(), any(), any(), any());
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(receive.execute(UUID.randomUUID(), org, new ReceiveRequest(1, "DN-1", 1), "dock", STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> receive.execute(order.getId(), org, new ReceiveRequest(1, "DN-1", 1), "dock", REQUESTER).block());
    }

    @Test
    @DisplayName("one order is read judged now; another tenant's is a 404 and a requester is refused")
    void retrieveOne() {
        PurchaseOrder order = placed();
        RetrievePurchaseOrderUseCase retrieve = new RetrievePurchaseOrderUseCase(repository, clock);

        StepVerifier.create(retrieve.execute(order.getId(), org, STAFF)).assertNext(response -> {
            assertEquals("ORDERED", response.status());
            assertEquals("NONE", response.fulfilment());
            assertEquals(NOW, response.orderedAt());
        }).verifyComplete();
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(retrieve.execute(UUID.randomUUID(), org, STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> retrieve.execute(order.getId(), org, REQUESTER).block());
    }

    @Test
    @DisplayName("the list is limited (100 unless asked, 500 at most, 1 at least); a fulfilment or lateness filter asks the store for placed orders and judges them; a status that cannot be placed finds none")
    void list() {
        PurchaseOrder late = PurchaseOrder.createNew(UUID.randomUUID(), org, "PO-L", vendor, requester, Currency.BRL, lines(), TODAY.minusDays(5), TODAY.minusDays(30), "b");
        late.submit(UUID.randomUUID(), "b");
        late.approve("cab");
        late.place("b", NOW.minusSeconds(86400L * 30));
        PurchaseOrder fresh = placed();
        PurchaseOrder complete = placed();
        complete.receive(1, "DN-1", 10, "d", NOW);
        complete.receive(2, "DN-2", 5, "d", NOW);
        when(repository.findAll(eq(org), any(), any(), any(), any(), any(), anyInt())).thenReturn(Flux.just(late, fresh, complete));
        RetrievePurchaseOrdersUseCase list = new RetrievePurchaseOrdersUseCase(repository, clock);

        StepVerifier.create(list.execute(org, null, null, null, null, null, null, null, STAFF)).expectNextCount(3).verifyComplete();
        verify(repository).findAll(org, null, null, null, null, null, 100);
        StepVerifier.create(list.execute(org, Status.DRAFT, vendor, Currency.BRL, null, null, null, 9999, STAFF)).expectNextCount(3).verifyComplete();
        verify(repository).findAll(org, Status.DRAFT, vendor, Currency.BRL, null, null, 500);
        StepVerifier.create(list.execute(org, null, null, null, null, null, null, 0, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, Fulfilment.COMPLETE, null, null, 10, STAFF)).assertNext(response -> assertEquals("COMPLETE", response.fulfilment())).verifyComplete();
        verify(repository).findAll(org, Status.ORDERED, null, null, null, null, RetrievePurchaseOrdersUseCase.JUDGED);
        StepVerifier.create(list.execute(org, null, null, null, null, true, null, 10, STAFF)).assertNext(response -> assertTrue(response.late())).verifyComplete();
        verify(repository).findAll(org, Status.ORDERED, null, null, null, null, RetrievePurchaseOrdersUseCase.JUDGED);
        StepVerifier.create(list.execute(org, null, null, null, null, false, null, 10, STAFF)).expectNextCount(2).verifyComplete();
        StepVerifier.create(list.execute(org, Status.ORDERED, null, null, Fulfilment.NONE, null, null, 10, STAFF)).expectNextCount(2).verifyComplete();
        StepVerifier.create(list.execute(org, Status.DRAFT, null, null, Fulfilment.NONE, null, null, 10, STAFF)).verifyComplete();
        assertThrows(ProcurementAccessDeniedException.class, () -> list.execute(org, null, null, null, null, null, null, null, REQUESTER).blockLast());
    }

    @Test
    @DisplayName("what changed within some days is asked of the store as a moment (1 to 365 days); another number is refused")
    void changedWithin() {
        when(repository.findAll(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(Flux.empty());
        RetrievePurchaseOrdersUseCase list = new RetrievePurchaseOrdersUseCase(repository, clock);

        StepVerifier.create(list.execute(org, null, null, null, null, null, 7, 50, STAFF)).verifyComplete();

        verify(repository).findAll(org, null, null, null, null, NOW.minusSeconds(7 * 86400L), 50);
        assertThrows(IllegalArgumentException.class, () -> list.execute(org, null, null, null, null, null, 0, null, STAFF).blockLast());
        assertThrows(IllegalArgumentException.class, () -> list.execute(org, null, null, null, null, null, 366, null, STAFF).blockLast());
    }

    @Test
    @DisplayName("late orders are asked of the store as expected before today")
    void lateAsksTheStore() {
        when(repository.findAll(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(Flux.empty());

        StepVerifier.create(new RetrievePurchaseOrdersUseCase(repository, clock).execute(org, null, null, null, null, true, null, null, STAFF)).verifyComplete();

        verify(repository).findAll(org, Status.ORDERED, null, null, TODAY, null, RetrievePurchaseOrdersUseCase.JUDGED);
    }

    @Test
    @DisplayName("the receipts and the audit trail are read in order; another tenant's order is a 404 and a requester is refused")
    void trails() {
        PurchaseOrder order = placed();
        order.receive(1, "DN-1", 2, "dock", NOW);
        RetrievePurchaseOrderTrailsUseCase trails = new RetrievePurchaseOrderTrailsUseCase(repository);

        StepVerifier.create(trails.receipts(order.getId(), org, STAFF)).assertNext(list -> {
            assertEquals(1, list.size());
            assertEquals("dock", list.get(0).reportedBy());
        }).verifyComplete();
        StepVerifier.create(trails.auditLog(order.getId(), org, STAFF)).assertNext(list -> assertEquals("INITIATED,SUBMITTED,APPROVED,PLACED,RECEIVED",
                String.join(",", list.stream().map(entry -> entry.action()).toList()))).verifyComplete();
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        StepVerifier.create(trails.receipts(UUID.randomUUID(), org, STAFF)).expectError(PurchaseOrderNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> trails.auditLog(order.getId(), org, REQUESTER).block());
    }

    @Test
    @DisplayName("the mapper answers every field, and it and the access check are utility classes")
    void mapperAndUtilities() throws ReflectiveOperationException {
        PurchaseOrder order = placed();
        order.receive(2, "DN-2", 5, "dock", NOW);
        var response = ProcurementMapper.toResponse(order, TODAY);

        assertEquals(new BigDecimal("15100.00"), response.total());
        assertEquals(5, response.lines().get(1).received());
        assertEquals(0, response.lines().get(1).remaining());
        assertEquals("PARTIAL", response.fulfilment());
        assertNotNull(response.approvalRequestId());
        assertEquals(ProcurementMapper.toResponse(order.getAuditTrail().get(0)).toStatus(), "DRAFT");
        assertNull(ProcurementMapper.toResponse(order.getAuditTrail().get(0)).fromStatus());
        assertEquals("DRAFT", ProcurementMapper.toResponse(order.getAuditTrail().get(1)).fromStatus());
        for (Class<?> utility : new Class<?>[]{ProcurementMapper.class, ProcurementAccess.class}) {
            Constructor<?> constructor = utility.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThrows(InvocationTargetException.class, constructor::newInstance);
        }
    }
}
