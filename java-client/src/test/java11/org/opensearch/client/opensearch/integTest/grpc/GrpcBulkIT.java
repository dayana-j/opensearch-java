/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.opensearch.integTest.grpc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.InfoResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;

/**
 * End-to-end integration tests for gRPC Bulk transport.
 * <p>
 * Verifies the complete pipeline:
 * <ol>
 *   <li>Client builds BulkRequest (Java API types)</li>
 *   <li>GrpcTransport converts to protobuf</li>
 *   <li>Sent over gRPC channel to OpenSearch on port 9400</li>
 *   <li>Server processes and returns protobuf BulkResponse</li>
 *   <li>GrpcTransport converts response back to Java API types</li>
 *   <li>Client receives standard BulkResponse</li>
 * </ol>
 * <p>
 * Skips automatically on OpenSearch versions below 3.5.0.
 * <p>
 * Run:
 * <pre>
 * ./gradlew integrationTest --tests "org.opensearch.client.opensearch.integTest.grpc.GrpcBulkIT" \
 *   -Dtests.opensearch.version=3.5.0
 * </pre>
 */
public class GrpcBulkIT extends AbstractGrpcIT implements GrpcTransportSupport {

    private static final String INDEX_NAME = "grpc-bulk-it";

    // ─── Test Document ───────────────────────────────────────────────────────────

    public static class Product {
        private String name;
        private double price;
        private int quantity;

        public Product() {}

        public Product(String name, double price, int quantity) {
            this.name = name;
            this.price = price;
            this.quantity = quantity;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getPrice() { return price; }
        public void setPrice(double price) { this.price = price; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }

    // ─── Tests ───────────────────────────────────────────────────────────────────

    /**
     * Verifies that bulk indexing multiple documents works end-to-end over gRPC.
     * After indexing, confirms documents are searchable via REST.
     */
    @Test
    public void testBulkIndexViaGrpc() throws IOException {
        assumeGrpcSupported();

        String index = INDEX_NAME + "-index";
        grpcClient().indices().create(new CreateIndexRequest.Builder().index(index).build());

        try {
            List<BulkOperation> operations = new ArrayList<>();
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("1")
                    .document(new Product("Laptop", 999.99, 10))
                    .build()
            ).build());
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("2")
                    .document(new Product("Mouse", 29.99, 200))
                    .build()
            ).build());
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("3")
                    .document(new Product("Keyboard", 79.99, 75))
                    .build()
            ).build());

            BulkRequest bulkRequest = new BulkRequest.Builder()
                .index(index)
                .operations(operations)
                .refresh(Refresh.True)
                .build();

            // Bulk goes over gRPC
            BulkResponse response = grpcClient().bulk(bulkRequest);

            // Verify response structure
            assertFalse("Bulk should not have errors", response.errors());
            assertEquals("Should have 3 items", 3, response.items().size());

            for (BulkResponseItem item : response.items()) {
                assertEquals(index, item.index());
                assertNotNull("Item should have an id", item.id());
                assertEquals("created", item.result());
            }

            // Verify docs are searchable (search goes over REST)
            SearchResponse<Product> searchResponse = grpcClient().search(
                s -> s.index(index), Product.class
            );
            assertEquals("Should find 3 documents", 3, searchResponse.hits().total().value());

        } finally {
            grpcClient().indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }

    /**
     * Verifies bulk delete operations work over gRPC.
     */
    @Test
    public void testBulkDeleteViaGrpc() throws IOException {
        assumeGrpcSupported();

        String index = INDEX_NAME + "-delete";
        grpcClient().indices().create(new CreateIndexRequest.Builder().index(index).build());

        try {
            // First: index documents
            List<BulkOperation> indexOps = new ArrayList<>();
            indexOps.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("del-1")
                    .document(new Product("ToDelete", 1.0, 1))
                    .build()
            ).build());
            indexOps.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("del-2")
                    .document(new Product("ToKeep", 2.0, 2))
                    .build()
            ).build());

            BulkResponse indexResponse = grpcClient().bulk(
                new BulkRequest.Builder().index(index).operations(indexOps).refresh(Refresh.True).build()
            );
            assertFalse(indexResponse.errors());

            // Second: delete one via bulk
            List<BulkOperation> deleteOps = new ArrayList<>();
            deleteOps.add(new BulkOperation.Builder().delete(
                d -> d.index(index).id("del-1")
            ).build());

            BulkResponse deleteResponse = grpcClient().bulk(
                new BulkRequest.Builder().index(index).operations(deleteOps).refresh(Refresh.True).build()
            );
            assertFalse(deleteResponse.errors());
            assertEquals("deleted", deleteResponse.items().get(0).result());

            // Verify only one document remains
            SearchResponse<Product> searchResponse = grpcClient().search(
                s -> s.index(index), Product.class
            );
            assertEquals("Should find 1 document", 1, searchResponse.hits().total().value());

        } finally {
            grpcClient().indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }

    /**
     * Verifies that non-bulk operations (search, info, index management) correctly
     * fall back to REST through the HybridTransport.
     */
    @Test
    public void testHybridTransportRestFallback() throws IOException {
        assumeGrpcSupported();

        // info() — goes over REST
        InfoResponse info = grpcClient().info();
        assertNotNull(info);
        assertNotNull(info.version().number());

        // Index create/delete — goes over REST
        String index = INDEX_NAME + "-fallback";
        grpcClient().indices().create(new CreateIndexRequest.Builder().index(index).build());

        try {
            // Search — goes over REST
            SearchResponse<Product> response = grpcClient().search(
                s -> s.index(index), Product.class
            );
            assertNotNull(response);
            assertEquals(0, response.hits().total().value());
        } finally {
            grpcClient().indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }

    /**
     * Verifies that bulk with update operations works over gRPC.
     */
    @Test
    public void testBulkUpdateViaGrpc() throws IOException {
        assumeGrpcSupported();

        String index = INDEX_NAME + "-update";
        grpcClient().indices().create(new CreateIndexRequest.Builder().index(index).build());

        try {
            // Index a document first
            List<BulkOperation> indexOps = new ArrayList<>();
            indexOps.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("upd-1")
                    .document(new Product("Widget", 10.0, 50))
                    .build()
            ).build());

            grpcClient().bulk(
                new BulkRequest.Builder().index(index).operations(indexOps).refresh(Refresh.True).build()
            );

            // Update the document via bulk
            List<BulkOperation> updateOps = new ArrayList<>();
            updateOps.add(new BulkOperation.Builder().update(u -> u
                .index(index)
                .id("upd-1")
                .document(new Product("Widget Pro", 15.0, 100))
            ).build());

            BulkResponse updateResponse = grpcClient().bulk(
                new BulkRequest.Builder().index(index).operations(updateOps).refresh(Refresh.True).build()
            );
            assertFalse(updateResponse.errors());
            assertEquals("updated", updateResponse.items().get(0).result());

            // Verify the update via GET (REST)
            GetResponse<Product> getResponse = grpcClient().get(
                g -> g.index(index).id("upd-1"), Product.class
            );
            assertTrue(getResponse.found());
            assertEquals("Widget Pro", getResponse.source().getName());
            assertEquals(15.0, getResponse.source().getPrice(), 0.01);

        } finally {
            grpcClient().indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }

    /**
     * Verifies that bulk with partial errors returns errors=true and
     * individual item error details.
     */
    @Test
    public void testBulkWithPartialErrors() throws IOException {
        assumeGrpcSupported();

        String index = INDEX_NAME + "-errors";
        grpcClient().indices().create(new CreateIndexRequest.Builder().index(index).build());

        try {
            // Try to update a document that doesn't exist (should fail)
            // and index one that should succeed
            List<BulkOperation> ops = new ArrayList<>();
            ops.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(index).id("ok-1")
                    .document(new Product("Success", 1.0, 1))
                    .build()
            ).build());
            ops.add(new BulkOperation.Builder().update(u -> u
                .index(index)
                .id("nonexistent-doc")
                .document(new Product("Fail", 0.0, 0))
            ).build());

            BulkResponse response = grpcClient().bulk(
                new BulkRequest.Builder().index(index).operations(ops).refresh(Refresh.True).build()
            );

            // Should have errors because the update targets a nonexistent doc
            assertTrue("Should have errors", response.errors());
            assertEquals(2, response.items().size());

            // First item should succeed
            assertEquals("created", response.items().get(0).result());

            // Second item should have an error
            assertNotNull("Error item should have error", response.items().get(1).error());

        } finally {
            grpcClient().indices().delete(new DeleteIndexRequest.Builder().index(index).build());
        }
    }
}
