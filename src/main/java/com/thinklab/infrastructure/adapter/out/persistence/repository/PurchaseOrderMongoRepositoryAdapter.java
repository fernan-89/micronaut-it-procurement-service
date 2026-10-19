package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicatePurchaseOrderException;
import com.thinklab.domain.exception.InvalidPurchaseOrderStatusException;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.PurchaseOrder;
import com.thinklab.domain.model.PurchaseOrder.AuditEntry;
import com.thinklab.domain.model.PurchaseOrder.Receipt;
import com.thinklab.domain.model.PurchaseOrder.Status;
import com.thinklab.domain.repository.PurchaseOrderRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument.LineDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument.PurchaseOrderPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PurchaseOrderDocument.ReceiptDocument;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MongoDB Reactive Repository Adapter for the PurchaseOrder aggregate, raw reactive-streams driver. Every change is one atomic update guarded by the status loaded
 * (ADR-033) that also appends the audit entry; a reference unique per organisation is the atomic backstop of a duplicate. A receipt is one atomic update too: it
 * checks, in the store, that the order is placed, that the line still has that much to arrive, that the delivery reference was not used for the line and that the
 * receipts are not full, so two people receiving at once can never receive more than was ordered (ADR-033).
 */
@Singleton
public class PurchaseOrderMongoRepositoryAdapter implements PurchaseOrderRepository {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_procurement_db";
    static final String COLLECTION_NAME = "purchase_orders";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public PurchaseOrderMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<PurchaseOrderDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, PurchaseOrderDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<PurchaseOrder> create(PurchaseOrder order) {
        log.debug("[PERSISTENCE] Create for PurchaseOrder Aggregate: {}", order.getId());
        return Mono.from(getCollection().insertOne(PurchaseOrderPersistenceMapper.toDocument(order)))
                .map(result -> order)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicatePurchaseOrderException("A purchase order with the reference '" + order.getReference() + "' already exists in this organisation.") : error);
    }

    @Override
    public Mono<PurchaseOrder> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(PurchaseOrderPersistenceMapper::toDomain);
    }

    @Override
    public Flux<PurchaseOrder> findAll(UUID organisationId, Status status, UUID vendorId, Currency currency, LocalDate expectedBefore, Instant changedSince, int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (status != null) {
            filters.add(Filters.eq(FIELD_STATUS, status.name()));
        }
        if (vendorId != null) {
            filters.add(Filters.eq("vendorId", vendorId));
        }
        if (currency != null) {
            filters.add(Filters.eq("currency", currency.name()));
        }
        if (expectedBefore != null) {
            filters.add(Filters.lt("expectedOn", expectedBefore.toString()));
        }
        if (changedSince != null) {
            filters.add(Filters.gte("updatedAt", changedSince));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.descending("createdAt")).limit(limit)).map(PurchaseOrderPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(PurchaseOrder order, Status expectedStatus, AuditEntry auditEntry) {
        List<Bson> guard = new ArrayList<>(List.of(Filters.eq(FIELD_ID, order.getId()), Filters.eq(FIELD_ORGANISATION, order.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name())));
        // The only change of a placed order that goes through save is cancelling it, and that needs that nothing was received.
        if (expectedStatus == Status.ORDERED) {
            guard.add(Filters.size("receipts", 0));
        }
        List<Bson> changes = new ArrayList<>(List.of(
                Updates.set("vendorId", order.getVendorId()),
                Updates.set("expectedOn", order.getExpectedOn() != null ? order.getExpectedOn().toString() : null),
                Updates.set(FIELD_STATUS, order.getStatus().name()),
                Updates.set("approvalRequestId", order.getApprovalRequestId()),
                Updates.set("orderedAt", order.getOrderedAt()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        ));
        if (expectedStatus == Status.DRAFT) {
            changes.add(Updates.set("lines", order.getLines().stream().map(LineDocument::fromDomain).collect(Collectors.toList())));
        }
        return Mono.from(getCollection().updateOne(Filters.and(guard), Updates.combine(changes)))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidPurchaseOrderStatusException("PurchaseOrder was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }

    @Override
    public Mono<Boolean> receive(UUID id, UUID organisationId, Receipt receipt, AuditEntry auditEntry) {
        Bson guard = Filters.and(
                Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_STATUS, Status.ORDERED.name()),
                Filters.elemMatch("lines", Filters.and(Filters.eq("lineNo", receipt.lineNo()), Filters.gte("remaining", receipt.quantity()))),
                Filters.not(Filters.elemMatch("receipts", Filters.and(Filters.eq("lineNo", receipt.lineNo()), Filters.eq("deliveryRef", receipt.deliveryRef())))),
                Filters.exists("receipts." + (PurchaseOrder.MAX_RECEIPTS - 1), false));
        Bson update = Updates.combine(
                Updates.inc("lines.$[line].remaining", -receipt.quantity()),
                Updates.push("receipts", ReceiptDocument.fromDomain(receipt)),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry)),
                Updates.set("updatedAt", Instant.now()));
        UpdateOptions options = new UpdateOptions().arrayFilters(List.of(Filters.eq("line.lineNo", receipt.lineNo())));
        return Mono.from(getCollection().updateOne(guard, update, options)).map(result -> result.getMatchedCount() > 0);
    }
}
