package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateApprovalBandException;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ApprovalBandDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ApprovalBandDocument.ApprovalBandPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ApprovalBandMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/po_test";
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private final UUID tenant = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<ApprovalBandDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("po_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_procurement_db")).thenReturn(database);
        when(database.getCollection("approval_bands", ApprovalBandDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private ApprovalBand band() {
        return ApprovalBand.createNew(UUID.randomUUID(), tenant, Currency.BRL, 1_000_00, UUID.randomUUID(), "admin", NOW);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<ApprovalBandDocument> finds(ApprovalBand... found) {
        FindPublisher<ApprovalBandDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<ApprovalBandDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(ApprovalBandPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("a band survives the round trip, retired or not")
    void roundTrip() {
        ApprovalBand band = band();
        ApprovalBandDocument document = ApprovalBandPersistenceMapper.toDocument(band);
        ApprovalBand back = ApprovalBandPersistenceMapper.toDomain(document);

        assertEquals("BRL", document.getCurrency());
        assertEquals(1_000_00, document.getFromMinor());
        assertEquals("ACTIVE", document.getStatus());
        assertEquals(band.getPolicyId(), document.getPolicyId());
        assertEquals(tenant, document.getOrganisationId());
        assertEquals("admin", document.getCreatedBy());
        assertEquals(NOW, document.getCreatedAt());
        assertNull(document.getRetiredAt());
        assertNull(document.getRetiredBy());
        assertEquals(band.getId(), back.getId());
        assertEquals(band.getFromMinor(), back.getFromMinor());
        band.retire("admin2", NOW.plusSeconds(1));
        ApprovalBand retired = ApprovalBandPersistenceMapper.toDomain(ApprovalBandPersistenceMapper.toDocument(band));
        assertEquals("admin2", retired.getRetiredBy());
        assertEquals(NOW.plusSeconds(1), retired.getRetiredAt());
        assertEquals(ApprovalBand.Status.RETIRED, retired.getStatus());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<ApprovalBandPersistenceMapper> constructor = ApprovalBandPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the band; a duplicate key is a DuplicateApprovalBandException and any other error passes through; the URI may omit the database and must not be null")
    void create() {
        ApprovalBand band = band();
        when(collection.insertOne(any(ApprovalBandDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))),
                Mono.error(writeError(11000)), Mono.error(writeError(1)));
        ApprovalBandMongoRepositoryAdapter adapter = new ApprovalBandMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(band)).expectNext(band).verifyComplete();
        StepVerifier.create(adapter.create(band)).expectError(DuplicateApprovalBandException.class).verify();
        StepVerifier.create(adapter.create(band)).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new ApprovalBandMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("one band is read by id scoped to the organisation")
    void findById() {
        ApprovalBand band = band();
        finds(band);

        StepVerifier.create(new ApprovalBandMongoRepositoryAdapter(client, URI).findById(band.getId(), tenant)).assertNext(found -> assertEquals(band.getId(), found.getId())).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filter.capture());
        assertTrue(filter.getValue().toString().contains("organisationId"));
    }

    @Test
    @DisplayName("the active bands are scoped to the organisation and to ACTIVE, narrowed to a currency when one is given, lowest bound first")
    void findActive() {
        ApprovalBand band = band();
        FindPublisher<ApprovalBandDocument> publisher = finds(band);
        ApprovalBandMongoRepositoryAdapter adapter = new ApprovalBandMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findActive(tenant, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findActive(tenant, Currency.BRL)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String bare = filter.getAllValues().get(0).toString();
        assertTrue(bare.contains("organisationId") && bare.contains("ACTIVE") && !bare.contains("currency"));
        assertTrue(filter.getAllValues().get(1).toString().contains("currency"));
        verify(publisher, times(2)).sort(any(Bson.class));
    }

    @Test
    @DisplayName("a retirement is one update guarded on the id, the organisation and the band still being active; it answers whether it was applied")
    void retire() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)), Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        ApprovalBandMongoRepositoryAdapter adapter = new ApprovalBandMongoRepositoryAdapter(client, URI);
        UUID id = UUID.randomUUID();

        StepVerifier.create(adapter.retire(id, tenant, "admin", NOW)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.retire(id, tenant, "admin", NOW)).expectNext(false).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getValue().toString().contains("ACTIVE") && guard.getValue().toString().contains("organisationId"));
        assertTrue(update.getValue().toString().contains("RETIRED") && update.getValue().toString().contains("retiredBy"));
    }
}
