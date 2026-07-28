/*
 * OpenSearch Bulk Demo — REST (HTTP)
 *
 * Sends bulk requests using the standard REST/HTTP transport and prints
 * performance measurements.
 *
 * Prerequisites:
 *   - opensearch-java on classpath
 *   - OpenSearch running on localhost:9200
 *
 * Run:
 *   ./gradlew :samples:run -Dsamples.mainClass=DemoRest
 */

package org.opensearch.client.samples;

import java.util.ArrayList;
import java.util.List;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

public class DemoRest {

    static final String HOST = "localhost";
    static final int PORT = 9200;
    static final String INDEX = "demo-benchmark";
    static final int NUM_DOCS = 10000;
    static final int BATCH_SIZE = 500;

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  OpenSearch Bulk — REST/HTTP Transport");
        System.out.println("=".repeat(60));
        System.out.printf("  Host:       %s:%d%n", HOST, PORT);
        System.out.println("  Protocol:   HTTP/1.1 (REST + JSON)");
        System.out.printf("  Documents:  %d%n", NUM_DOCS);
        System.out.printf("  Batches:    %d x %d docs%n", NUM_DOCS / BATCH_SIZE, BATCH_SIZE);
        System.out.println("=".repeat(60));

        // Standard REST transport
        HttpHost host = new HttpHost("http", HOST, PORT);
        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder.builder(host).build();
        OpenSearchClient client = new OpenSearchClient(transport);

        // Verify connection
        var info = client.info();
        System.out.printf("%n  ✅ Connected to OpenSearch %s%n", info.version().number());

        // Clean up
        client.indices().delete(d -> d.index(INDEX).ignoreUnavailable(true));
        client.indices().create(c -> c.index(INDEX));

        // ─── Show what the client SENDS ──────────────────────────────────────

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  📤 REQUEST — What your code sends");
        System.out.println("-".repeat(60));
        System.out.printf("  POST http://%s:%d/_bulk%n", HOST, PORT);
        System.out.println("  Content-Type: application/x-ndjson");
        System.out.println();
        System.out.println("  Body (first 2 operations):");
        System.out.println();
        System.out.printf("    {\"index\":{\"_index\":\"%s\",\"_id\":\"0\"}}%n", INDEX);
        System.out.println("    {\"name\":\"Product 0\",\"price\":9.99,\"category\":\"electronics\"}");
        System.out.printf("    {\"index\":{\"_index\":\"%s\",\"_id\":\"1\"}}%n", INDEX);
        System.out.println("    {\"name\":\"Product 1\",\"price\":10.09,\"category\":\"clothing\"}");
        System.out.printf("    ... (%d more operations)%n", BATCH_SIZE - 2);
        System.out.println();
        System.out.println("  Format: NDJSON (newline-delimited JSON text)");

        // ─── Send all batches ────────────────────────────────────────────────

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  ⏱  Sending all batches...");
        System.out.println("-".repeat(60));

        long totalServerTookMs = 0;
        List<Long> batchTimes = new ArrayList<>();
        BulkResponse lastResponse = null;

        long overallStart = System.currentTimeMillis();

        for (int batch = 0; batch < NUM_DOCS / BATCH_SIZE; batch++) {
            List<BulkOperation> ops = buildBatch(batch);

            long start = System.currentTimeMillis();
            BulkResponse response = client.bulk(new BulkRequest.Builder()
                .index(INDEX).operations(ops).build());
            long elapsed = System.currentTimeMillis() - start;

            batchTimes.add(elapsed);
            totalServerTookMs += response.took();
            lastResponse = response;

            System.out.printf("    Batch %d: %dms | server took: %dms | errors: %s%n",
                batch + 1, elapsed, response.took(), response.errors());
        }

        long overallElapsed = System.currentTimeMillis() - overallStart;

        // ─── Show what the client RECEIVES ───────────────────────────────────

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  📥 RESPONSE — What your code receives");
        System.out.println("-".repeat(60));
        System.out.println("  HTTP/1.1 200 OK");
        System.out.println("  Content-Type: application/json");
        System.out.println();
        System.out.println("    {");
        System.out.printf("      \"took\": %d,%n", lastResponse.took());
        System.out.printf("      \"errors\": %s,%n", lastResponse.errors());
        System.out.println("      \"items\": [");
        var item = lastResponse.items().get(0);
        System.out.println("        {\"index\": {");
        System.out.printf("          \"_index\": \"%s\",%n", item.index());
        System.out.printf("          \"_id\": \"%s\",%n", item.id());
        System.out.printf("          \"result\": \"%s\",%n", item.result());
        System.out.printf("          \"status\": %d%n", item.status());
        System.out.println("        }},");
        System.out.printf("        ... (%d more items)%n", BATCH_SIZE - 1);
        System.out.println("      ]");
        System.out.println("    }");
        System.out.println();
        System.out.println("  Response format: JSON text");

        // ─── Measurements ────────────────────────────────────────────────────

        long avgBatchTime = batchTimes.stream().mapToLong(l -> l).sum() / batchTimes.size();
        long docsPerSec = NUM_DOCS * 1000L / overallElapsed;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  MEASUREMENTS (REST/HTTP)");
        System.out.println("=".repeat(60));
        System.out.printf("  Total documents indexed:    %d%n", NUM_DOCS);
        System.out.println("  Wire format:                JSON (text)");
        System.out.println("  Protocol:                   HTTP/1.1");
        System.out.println();
        System.out.printf("  Total wall-clock time:      %d ms%n", overallElapsed);
        System.out.printf("  Avg batch round-trip:       %d ms%n", avgBatchTime);
        System.out.printf("  Total server processing:    %d ms%n", totalServerTookMs);
        System.out.printf("  Throughput:                 %d docs/sec%n", docsPerSec);
        System.out.println("=".repeat(60));

        // Cleanup
        client.indices().delete(d -> d.index(INDEX));
        transport.close();
        System.out.println("\n  🧹 Cleaned up\n");
    }

    static List<BulkOperation> buildBatch(int batchNum) {
        List<BulkOperation> ops = new ArrayList<>();
        int startId = batchNum * BATCH_SIZE;
        String[] categories = {"electronics", "clothing", "home", "sports"};

        for (int i = 0; i < BATCH_SIZE; i++) {
            int docId = startId + i;
            Product doc = new Product(
                "Product " + docId,
                9.99 + docId * 0.1,
                categories[docId % 4],
                docId % 3 != 0
            );
            ops.add(new BulkOperation.Builder().index(
                new IndexOperation.Builder<Product>()
                    .index(INDEX).id(String.valueOf(docId)).document(doc).build()
            ).build());
        }
        return ops;
    }

    static class Product {
        public String name;
        public double price;
        public String category;
        public boolean inStock;

        Product(String name, double price, String category, boolean inStock) {
            this.name = name;
            this.price = price;
            this.category = category;
            this.inStock = inStock;
        }
    }
}
