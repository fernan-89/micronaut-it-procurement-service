package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicatePurchaseOrderException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Category;
import com.thinklab.domain.model.PurchaseOrder.NewLine;
import com.thinklab.domain.model.PurchaseOrder.Receipt;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument.PurchaseOrderPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.bson.codecs.configuration.CodecRegistries;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PurchaseOrderMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/po_test";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");

    private final UUID tenant = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<PurchaseOrderDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("po_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_procurement_db")).thenReturn(database);
        when(database.getCollection("purchase_orders", PurchaseOrderDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
    }

    private PurchaseOrder draft() {
        return PurchaseOrder.createNew(UUID.randomUUID(), tenant, "PO-1", UUID.randomUUID(), UUID.randomUUID(), Currency.BRL,
                List.of(new NewLine(Category.HARDWARE, "LAP-14", 10, 150_000)), TODAY.plusDays(30), TODAY, "buyer");
    }

    /** The filter as the database would read it (an $elemMatch is opaque in its toString). */
    private static String json(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, CodecRegistries.withUuidRepresentation(MongoClientSettings.getDefaultCodecRegistry(), UuidRepresentation.STANDARD)).toJson();
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<PurchaseOrderDocument> finds(PurchaseOrder... found) {
        FindPublisher<PurchaseOrderDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<PurchaseOrderDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(PurchaseOrderPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("an order survives the round trip: lines in minor units, the expected day as text, receipts, the approval and the trail")
    void roundTrip() {
        PurchaseOrder order = draft();
        UUID request = UUID.randomUUID();
        order.submit(request, "buyer");
        order.approve("cab");
        order.place("buyer", NOW);
        order.receive(1, "DN-1", 3, "dock", NOW);
        PurchaseOrderDocument document = PurchaseOrderPersistenceMapper.toDocument(order);
        PurchaseOrder back = PurchaseOrderPersistenceMapper.toDomain(document);

        assertEquals("PO-1", document.getReference());
        assertEquals("BRL", document.getCurrency());
        assertEquals(TODAY.plusDays(30).toString(), document.getExpectedOn());
        assertEquals("ORDERED", document.getStatus());
        assertEquals(7, document.getLines().get(0).remaining());
        assertEquals(150_000, document.getLines().get(0).unitPriceMinor());
        assertEquals(1, document.getReceipts().size());
        assertEquals(5, document.getAuditTrail().size());
        assertEquals(request, document.getApprovalRequestId());
        assertEquals(NOW, document.getOrderedAt());
        assertEquals(order.getVendorId(), document.getVendorId());
        assertEquals(order.getRequesterId(), document.getRequesterId());
        assertEquals(tenant, document.getOrganisationId());
        assertEquals(order.getId(), document.getId());
        assertEquals(order.getCreatedAt(), document.getCreatedAt());
        assertEquals(order.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(order.totalMinor(), back.totalMinor());
        assertEquals(TODAY.plusDays(30), back.getExpectedOn());
        assertEquals(Status.ORDERED, back.getStatus());
        assertEquals(7, back.getLines().get(0).remaining());
        assertEquals("DN-1", back.getReceipts().get(0).deliveryRef());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(Status.DRAFT, back.getAuditTrail().get(1).fromStatus());
        PurchaseOrder noDay = PurchaseOrder.createNew(UUID.randomUUID(), tenant, "PO-2", UUID.randomUUID(), UUID.randomUUID(), Currency.USD, List.of(), null, TODAY, "b");
        PurchaseOrderDocument noDayDocument = PurchaseOrderPersistenceMapper.toDocument(noDay);
        assertNull(noDayDocument.getExpectedOn());
        assertNull(PurchaseOrderPersistenceMapper.toDomain(noDayDocument).getExpectedOn());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<PurchaseOrderPersistenceMapper> constructor = PurchaseOrderPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the order; a duplicate key is a DuplicatePurchaseOrderException and any other error passes through; the URI may omit the database and must not be null")
    void create() {
        PurchaseOrder order = draft();
        when(collection.insertOne(any(PurchaseOrderDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))),
                Mono.error(writeError(11000)), Mono.error(writeError(1)));
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(order)).expectNext(order).verifyComplete();
        StepVerifier.create(adapter.create(order)).expectError(DuplicatePurchaseOrderException.class).verify();
        StepVerifier.create(adapter.create(order)).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new PurchaseOrderMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("one order is read by id scoped to the organisation")
    void findById() {
        PurchaseOrder order = draft();
        finds(order);
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findById(order.getId(), tenant)).assertNext(found -> assertEquals(order.getId(), found.getId())).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filter.capture());
        assertTrue(filter.getValue().toString().contains("organisationId"));
    }

    @Test
    @DisplayName("the list is scoped to the organisation, narrowed by each filter given (and the day expected before), newest first and limited")
    void list() {
        PurchaseOrder order = draft();
        FindPublisher<PurchaseOrderDocument> publisher = finds(order);
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findAll(tenant, null, null, null, null, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, Status.ORDERED, order.getVendorId(), Currency.BRL, TODAY, 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String bare = filter.getAllValues().get(0).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("status") && !bare.contains("vendorId") && !bare.contains("currency") && !bare.contains("expectedOn"));
        String full = filter.getAllValues().get(1).toString();
        assertTrue(full.contains("status") && full.contains("vendorId") && full.contains("currency") && full.contains("$lt") && full.contains(TODAY.toString()));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }

    @Test
    @DisplayName("save is guarded on the status loaded and the organisation, writes the lines only for a draft and pushes the audit entry; a lost guard is a conflict")
    void save() {
        PurchaseOrder order = draft();
        AuditEntry entry = order.update(order.getVendorId(), List.of(new NewLine(Category.CLOUD, "RI", 1, 1)), null, TODAY, "buyer");
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)), Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(order, Status.DRAFT, entry)).verifyComplete();
        StepVerifier.create(adapter.save(order, Status.DRAFT, entry)).expectError(InvalidPurchaseOrderStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("organisationId") && guard.getAllValues().get(0).toString().contains("DRAFT"));
        String text = update.getAllValues().get(0).toString();
        assertTrue(text.contains("lines") && text.contains("auditTrail") && text.contains("approvalRequestId"));
    }

    @Test
    @DisplayName("once placed, a save never writes the lines (receipts change them at the same time), and cancelling a placed order also needs that nothing was received")
    void saveAfterPlacing() {
        PurchaseOrder order = draft();
        order.submit(UUID.randomUUID(), "b");
        order.approve("cab");
        order.place("b", NOW);
        AuditEntry cancelled = order.cancel("buyer");
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(order, Status.ORDERED, cancelled)).verifyComplete();
        PurchaseOrder other = draft();
        AuditEntry submitted = other.submit(UUID.randomUUID(), "b");
        StepVerifier.create(adapter.save(other, Status.DRAFT, submitted)).verifyComplete();
        PurchaseOrder approvable = draft();
        approvable.submit(UUID.randomUUID(), "b");
        AuditEntry approved = approvable.approve("cab");
        StepVerifier.create(adapter.save(approvable, Status.PENDING_APPROVAL, approved)).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(3)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("$size"));
        assertTrue(!update.getAllValues().get(0).toString().contains("\"lines\"") && !update.getAllValues().get(0).toString().contains("lines="));
        assertTrue(!guard.getAllValues().get(1).toString().contains("$size"));
        assertTrue(!guard.getAllValues().get(2).toString().contains("$size"));
    }

    @Test
    @DisplayName("a receipt is one atomic update: placed, the line still has that much to arrive, the delivery reference not used for the line, the receipts not full; answers whether it applied")
    void receive() {
        UUID id = UUID.randomUUID();
        Receipt receipt = new Receipt(2, "DN-9", 3, NOW, "dock");
        AuditEntry entry = new AuditEntry(NOW, "RECEIVED", "dock", Status.ORDERED, Status.ORDERED, "3 of line 2");
        when(collection.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)), Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        PurchaseOrderMongoRepositoryAdapter adapter = new PurchaseOrderMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.receive(id, tenant, receipt, entry)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.receive(id, tenant, receipt, entry)).expectNext(false).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture(), options.capture());
        String text = json(guard.getAllValues().get(0));
        assertTrue(text.contains("ORDERED") && text.contains("$elemMatch") && text.contains("remaining") && text.contains("$gte") && text.contains("DN-9") && text.contains("$not") && text.contains("receipts.199") && text.contains("$exists"));
        String change = update.getAllValues().get(0).toString();
        assertTrue(change.contains("$inc") && change.contains("lines.$[line].remaining") && change.contains("-3") && change.contains("$push") && change.contains("receipts"));
        assertEquals(1, options.getAllValues().get(0).getArrayFilters().size());
    }
}
