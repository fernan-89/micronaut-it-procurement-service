package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateApprovalBandException;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.ApprovalBand.Status;
import com.thinklab.domain.model.Money;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.repository.ApprovalBandRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ApprovalBandDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.ApprovalBandDocument.ApprovalBandPersistenceMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the ApprovalBand aggregate, raw reactive-streams driver (ADR-036). A unique partial index (organisation, currency, lower
 * bound, only for active bands) is the atomic backstop of two bands starting at the same amount; a retirement is one atomic update guarded on the band still being
 * active.
 */
@Singleton
public class ApprovalBandMongoRepositoryAdapter implements ApprovalBandRepository {

    private static final Logger log = LoggerFactory.getLogger(ApprovalBandMongoRepositoryAdapter.class);
    static final String COLLECTION_NAME = "approval_bands";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public ApprovalBandMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : PurchaseOrderMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<ApprovalBandDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, ApprovalBandDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<ApprovalBand> create(ApprovalBand band) {
        log.debug("[PERSISTENCE] Create for ApprovalBand Aggregate: {}", band.getId());
        return Mono.from(getCollection().insertOne(ApprovalBandPersistenceMapper.toDocument(band)))
                .map(result -> band)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == PurchaseOrderMongoRepositoryAdapter.DUPLICATE_KEY
                        ? new DuplicateApprovalBandException("An active " + band.getCurrency() + " approval band that starts at " + Money.toMajor(band.getFromMinor()) + " already exists in this organisation.")
                        : error);
    }

    @Override
    public Mono<ApprovalBand> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(ApprovalBandPersistenceMapper::toDomain);
    }

    @Override
    public Flux<ApprovalBand> findActive(UUID organisationId, Currency currency) {
        List<Bson> filters = new ArrayList<>(List.of(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_STATUS, Status.ACTIVE.name())));
        if (currency != null) {
            filters.add(Filters.eq("currency", currency.name()));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.ascending("currency", "fromMinor"))).map(ApprovalBandPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Boolean> retire(UUID id, UUID organisationId, String executor, Instant at) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_STATUS, Status.ACTIVE.name()));
        Bson update = Updates.combine(Updates.set(FIELD_STATUS, Status.RETIRED.name()), Updates.set("retiredAt", at), Updates.set("retiredBy", executor));
        return Mono.from(getCollection().updateOne(guard, update)).map(result -> result.getMatchedCount() > 0);
    }
}
