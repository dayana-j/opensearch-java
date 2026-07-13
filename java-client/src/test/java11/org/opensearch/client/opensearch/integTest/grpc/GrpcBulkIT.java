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
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.integTest.OpenSearchJavaClientTestCase;

/**
 * End-to-end integration tests for gRPC Bulk transport.
 * <p>
 * Tests verify that bulk indexing works correctly over gRPC (port 9400)
 * against an unsecured OpenSearch cluster with gRPC transport enabled.
 * <p>
 * Requires OpenSearch 3.5+ with the following configuration:
 * <pre>
 * aux.transport.types: [transport-grpc]
 * aux.transport.transport-grpc.port: '9400'
 * plugins.security.disabled: true
 * </pre>
 * <p>
 * CI runs these tests via:
 * <pre>
 * ./gradlew integrationTest -Dtests.opensearch.version=3.5.0
 * </pre>
 */
public class GrpcBulkIT extends OpenSearchJavaClientTestCase implements GrpcTransportSupport {

    private static final String INDEX_NAME = "grpc-bulk-test";

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

    // ─── Integration Tests ───────────────────────────────────────────────────────

    /**
     * Tests basic bulk indexing via gRPC:
     * 1. Create an index
     * 2. Bulk index multiple documents (goes over gRPC)
     * 3. Search to verify documents exist (goes over REST)
     * 4. Clean up
     */
    @Test
    public void testBulkIndexViaGrpc() throws IOException {
        // Create index
        javaClient().indices().create(new CreateIndexRequest.Builder().index(INDEX_NAME).build());

        try {
            // Build bulk request with multiple index operations
            List<BulkOperation> operations = new ArrayList<>();
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(INDEX_NAME).id("1")
                    .document(new Product("Laptop", 999.99, 10))
                    .build()
            ).build());
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(INDEX_NAME).id("2")
                    .document(new Product("Mouse", 29.99, 200))
                    .build()
            ).build());
            operations.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(INDEX_NAME).id("3")
                    .document(new Product("Keyboard", 79.99, 75))
                    .build()
            ).build());

            BulkRequest bulkRequest = new BulkRequest.Builder()
                .index(INDEX_NAME)
                .operations(operations)
                .refresh(Refresh.True) // wait for searchable
                .build();

            // Execute bulk — this goes over gRPC via HybridTransport
            BulkResponse response = javaClient().bulk(bulkRequest);

            // Verify bulk response
            assertFalse("Bulk should not have errors", response.errors());
            assertEquals("Should have 3 items", 3, response.items().size());
            for (BulkResponseItem item : response.items()) {
                assertEquals(INDEX_NAME, item.index());
                assertNotNull(item.id());
                assertEquals("created", item.result());
            }

            // Search to verify — this goes over REST via HybridTransport
            SearchResponse<Product> searchResponse = javaClient().search(
                s -> s.index(INDEX_NAME), Product.class
            );
            assertEquals("Should find 3 documents", 3, searchResponse.hits().total().value());

        } finally {
            // Cleanup
            javaClient().indices().delete(new DeleteIndexRequest.Builder().index(INDEX_NAME).build());
        }
    }

    /**
     * Tests bulk with mixed operations (index + delete) via gRPC.
     */
    @Test
    public void testBulkMixedOpsViaGrpc() throws IOException {
        javaClient().indices().create(new CreateIndexRequest.Builder().index(INDEX_NAME).build());

        try {
            // First: index a document
            List<BulkOperation> indexOps = new ArrayList<>();
            indexOps.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(INDEX_NAME).id("to-delete")
                    .document(new Product("Temp", 1.0, 1))
                    .build()
            ).build());

            BulkRequest indexRequest = new BulkRequest.Builder()
                .index(INDEX_NAME)
                .operations(indexOps)
                .refresh(Refresh.True)
                .build();

            BulkResponse indexResponse = javaClient().bulk(indexRequest);
            assertFalse(indexResponse.errors());

            // Second: delete it via bulk
            List<BulkOperation> deleteOps = new ArrayList<>();
            deleteOps.add(new BulkOperation.Builder().delete(
                d -> d.index(INDEX_NAME).id("to-delete")
            ).build());

            BulkRequest deleteRequest = new BulkRequest.Builder()
                .index(INDEX_NAME)
                .operations(deleteOps)
                .refresh(Refresh.True)
                .build();

            BulkResponse deleteResponse = javaClient().bulk(deleteRequest);
            assertFalse(deleteResponse.errors());
            assertEquals("deleted", deleteResponse.items().get(0).result());

            // Verify it's gone
            SearchResponse<Product> searchResponse = javaClient().search(
                s -> s.index(INDEX_NAME), Product.class
            );
            assertEquals("Should find 0 documents", 0, searchResponse.hits().total().value());

        } finally {
            javaClient().indices().delete(new DeleteIndexRequest.Builder().index(INDEX_NAME).build());
        }
    }

    /**
     * Tests that the HybridTransport correctly falls back to REST for non-bulk operations.
     * Search, index creation, and deletion all go through REST.
     */
    @Test
    public void testRestFallbackForNonBulkOps() throws IOException {
        // These all go over REST (not gRPC) via HybridTransport
        javaClient().indices().create(new CreateIndexRequest.Builder().index(INDEX_NAME).build());

        try {
            // Info request — REST
            assertNotNull(javaClient().info());

            // Search — REST
            SearchResponse<Product> response = javaClient().search(
                s -> s.index(INDEX_NAME), Product.class
            );
            assertNotNull(response);
            assertEquals(0, response.hits().total().value());
        } finally {
            javaClient().indices().delete(new DeleteIndexRequest.Builder().index(INDEX_NAME).build());
        }
    }
}
