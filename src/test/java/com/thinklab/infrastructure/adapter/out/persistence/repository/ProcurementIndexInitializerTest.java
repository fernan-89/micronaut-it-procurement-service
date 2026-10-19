package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ProcurementIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique reference per organisation and the list index")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> orders = mock(MongoCollection.class);
        when(client.getDatabase("tenant_po")).thenReturn(database);
        when(database.getCollection("purchase_orders")).thenReturn(orders);
        when(orders.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new ProcurementIndexInitializer(client, "mongodb://mongo:27017/tenant_po").onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(orders, times(2)).createIndex(any(), options.capture());
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_reference_1", options.getAllValues().get(0).getName());
        assertFalse(options.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_status_1_createdAt_-1", options.getAllValues().get(1).getName());
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_procurement_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")), Mono.error(new IllegalStateException("rejected")));
        var initializer = new ProcurementIndexInitializer(client, "mongodb://mongo:27017");

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(2)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new ProcurementIndexInitializer(null, "mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> new ProcurementIndexInitializer(client, null));
    }
}
