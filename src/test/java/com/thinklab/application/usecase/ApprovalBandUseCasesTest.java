package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateApprovalBandRequest;
import com.thinklab.application.mapper.ApprovalBandMapper;
import com.thinklab.domain.exception.ApprovalBandNotFoundException;
import com.thinklab.domain.exception.DuplicateApprovalBandException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.exception.ProcurementAccessDeniedException;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.ApprovalBandRepository;
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
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalBandUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HashServicePort hash;
    private ApprovalBandRepository repository;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        repository = mock(ApprovalBandRepository.class);
        when(hash.generateSovereignId(any())).thenAnswer(call -> Mono.just(UUID.randomUUID()));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    private ApprovalBand band() {
        return ApprovalBand.createNew(UUID.randomUUID(), org, Currency.BRL, 1_000_00, policy, "admin", NOW);
    }

    @Test
    @DisplayName("a band is made with its lower bound in minor units, answered with it in major units; a requester is refused and an amount with three decimals is refused")
    void initiate() {
        InitiateApprovalBandUseCase initiate = new InitiateApprovalBandUseCase(hash, repository, clock);

        StepVerifier.create(initiate.execute(org, new InitiateApprovalBandRequest(Currency.EUR, new BigDecimal("2500.50"), policy), "admin", "ENGINEER"))
                .assertNext(band -> {
                    assertEquals("EUR", band.currency());
                    assertEquals(new BigDecimal("2500.50"), band.fromAmount());
                    assertEquals(policy, band.policyId());
                    assertEquals("ACTIVE", band.status());
                    assertEquals("admin", band.createdBy());
                    assertEquals(NOW, band.createdAt());
                }).verifyComplete();
        assertThrows(ProcurementAccessDeniedException.class,
                () -> initiate.execute(org, new InitiateApprovalBandRequest(Currency.BRL, BigDecimal.ONE, policy), "admin", "REQUESTER").block());
        StepVerifier.create(initiate.execute(org, new InitiateApprovalBandRequest(Currency.BRL, new BigDecimal("1.001"), policy), "admin", "ENGINEER"))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("a second active band at the same lower bound is the conflict the store reports")
    void initiateDuplicate() {
        doReturn(Mono.error(new DuplicateApprovalBandException("already"))).when(repository).create(any());

        StepVerifier.create(new InitiateApprovalBandUseCase(hash, repository, clock)
                .execute(org, new InitiateApprovalBandRequest(Currency.BRL, BigDecimal.ZERO, policy), "admin", null)).expectError(DuplicateApprovalBandException.class).verify();
    }

    @Test
    @DisplayName("the active bands are read for the tenant, optionally of one currency; a requester is refused")
    void retrieve() {
        when(repository.findActive(org, Currency.BRL)).thenReturn(Flux.just(band()));
        when(repository.findActive(org, null)).thenReturn(Flux.just(band(), band()));
        RetrieveApprovalBandsUseCase retrieve = new RetrieveApprovalBandsUseCase(repository);

        StepVerifier.create(retrieve.execute(org, Currency.BRL, "ENGINEER")).expectNextCount(1).verifyComplete();
        StepVerifier.create(retrieve.execute(org, null, null)).expectNextCount(2).verifyComplete();
        StepVerifier.create(retrieve.execute(org, null, "REQUESTER")).expectError(ProcurementAccessDeniedException.class).verify();
        verify(repository, never()).findActive(org, Currency.USD);
    }

    @Test
    @DisplayName("a band is retired once; one that is not there is a 404, one already retired is a conflict, and losing the race to another person is a conflict")
    void retire() {
        ApprovalBand band = band();
        when(repository.findById(band.getId(), org)).thenReturn(Mono.just(band));
        when(repository.retire(band.getId(), org, "admin", NOW)).thenReturn(Mono.just(true));
        RetireApprovalBandUseCase retire = new RetireApprovalBandUseCase(repository, clock);

        StepVerifier.create(retire.execute(band.getId(), org, "admin", "ENGINEER")).verifyComplete();

        assertEquals(ApprovalBand.Status.RETIRED, band.getStatus());
        StepVerifier.create(retire.execute(band.getId(), org, "admin", "ENGINEER")).expectError(InvalidPurchaseOrderStatusException.class).verify();
        ApprovalBand racing = band();
        when(repository.findById(racing.getId(), org)).thenReturn(Mono.just(racing));
        when(repository.retire(racing.getId(), org, "admin", NOW)).thenReturn(Mono.just(false));
        StepVerifier.create(retire.execute(racing.getId(), org, "admin", "ENGINEER")).expectError(InvalidPurchaseOrderStatusException.class).verify();
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(retire.execute(missing, org, "admin", "ENGINEER")).expectError(ApprovalBandNotFoundException.class).verify();
        assertThrows(ProcurementAccessDeniedException.class, () -> retire.execute(band.getId(), org, "admin", "REQUESTER").block());
    }

    @Test
    @DisplayName("the mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<ApprovalBandMapper> constructor = ApprovalBandMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
