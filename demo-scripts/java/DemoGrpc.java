/*
 * OpenSearch Bulk Demo — gRPC Transport
 *
 * Sends the SAME bulk requests as DemoRest.java using the SAME API.
 * The only difference: the transport converts to protobuf under the hood.
 *
 * Prerequisites:
 *   - opensearch-java + opensearch-java-grpc on classpath
 *   - OpenSearch 3.5+ running with gRPC on port 9400
 *
 * Run:
 *   ./gradlew :samples:run -Dsamples.mainClass=DemoGrpc
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
import org.opensearch.client.transport.grpc.GrpcTransport;
import org.opensearch.client.transport.grpc.HybridTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

public class DemoGrpc {

    static final String REST_HOST = "localhost";
    static final int REST_PORT = 9200;
    static final String GRPC_HOST = "localhost";
    static final int GRPC_PORT = 9400;
    static final String INDEX = "demo-benchmark";
    static final int NUM_DOCS = 10000;
    static final int BATCH_SIZE = 500;

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  OpenSearch Bulk — gRPC Transport");
        System.out.println("=".repeat(60));
        System.out.printf("  Host:       %s:%d (gRPC)%n", GRPC_HOST, GRPC_PORT);
        System.out.println("  Protocol:   HTTP/2 + Protobuf (under the hood)");
        System.out.printf("  Documents:  %d%n", NUM_DOCS);
        System.out.printf("  Batches:    %d x %d docs%n", NUM_DOCS / BATCH_SIZE, BATCH_SIZE);
        System.out.println("=".repeat(60));

        // Only difference from REST: GrpcTransport + HybridTransport
        HttpHost restHost = new HttpHost("http", REST_HOST, REST_PORT);
        OpenSearchTransport restTransport = ApacheHttpClient5TransportBuilder.builder(restHost).build();

        GrpcTransport grpcTransport = GrpcTransport.builder(GRPC_HOST, GRPC_PORT)
            .jsonpMapper(new JacksonJsonpMapper())
            .build();

        HybridTransport hybridTransport = new HybridTransport(grpcTransport, restTransport);
        OpenSearchClient client = new OpenSearchClient(hybridTransport);

        // Verify connection (goes via REST — info() not supported on gRPC)
        var info = client.info();
        System.out.printf("%n  ✅ Connected to OpenSearch %s%n", info.version().number());

        // Clean up (index ops go via REST)
        client.indices().delete(d -> d.index(INDEX).ignoreUnavailable(true));
        client.indices().create(c -> c.index(INDEX));

        // ─── Show what the user writes (SAME as REST) ────────────────────────

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  📤 REQUEST — What your code sends (identical to REST)");
        System.out.println("-".repeat(60));
        System.out.println();
        System.out.println("    client.bulk(new BulkRequest.Builder()");
        System.out.printf("        .index(\"%s\")%n", INDEX);
        System.out.println("        .operations(ops)");
        System.out.println("        .build());");
        System.out.println();
        System.out.println("    // ops = [");
        System.out.printf("    //   IndexOperation { index: \"%s\", id: \"0\",%n", INDEX);
        System.out.println("    //     document: {name: \"Product 0\", price: 9.99, ...} },");
        System.out.printf("    //   ... (%d more operations)%n", BATCH_SIZE - 1);
        System.out.println("    // ]");
        System.out.println();
        System.out.println("    ⚙️  Under the hood: Java object → protobuf → gRPC (HTTP/2, binary)");
        System.out.println();

        // ─── Send all batches ────────────────────────────────────────────────

        System.out.println("-".repeat(60));
        System.out.println("  ⏱  Sending all batches via gRPC...");
        System.out.println("-".repeat(60));

        long totalServerTookMs = 0;
        List<Long> batchTimes = new ArrayList<>();
        BulkResponse lastResponse = null;

        long overallStart = System.currentTimeMillis();

        for (int batch = 0; batch < NUM_DOCS / BATCH_SIZE; batch++) {
            List<BulkOperation> ops = buildBatch(batch);

            long start = System.currentTimeMillis();
            BulkResponse response = client.bulk(new BulkRequest.Builder()
                .index(INDEX).operations(ops).build());  // Same call as REST
            long elapsed = System.currentTimeMillis() - start;

            batchTimes.add(elapsed);
            totalServerTookMs += response.took();
            lastResponse = response;

            System.out.printf("    Batch %d: %dms | server took: %dms | errors: %s%n",
                batch + 1, elapsed, response.took(), response.errors());
        }

        long overallElapsed = System.currentTimeMillis() - overallStart;

        // ─── Show what the user receives (SAME as REST) ──────────────────────

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  📥 RESPONSE — What your code receives (identical to REST)");
        System.out.println("-".repeat(60));
        System.out.println();
        System.out.println("    ⚙️  Under the hood: gRPC response → protobuf → BulkResponse object");
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
        System.out.println("    ☝️  Same exact response as REST — your code doesn't change!");

        // ─── Measurements ────────────────────────────────────────────────────

        long avgBatchTime = batchTimes.stream().mapToLong(l -> l).sum() / batchTimes.size();
        long docsPerSec = NUM_DOCS * 1000L / overallElapsed;

        System.out.println("\n" + "=".repeat(60));
        System.out.println("  MEASUREMENTS (gRPC)");
        System.out.println("=".repeat(60));
        System.out.printf("  Total documents indexed:    %d%n", NUM_DOCS);
        System.out.println("  Wire format:                Protobuf (binary, ~40% smaller)");
        System.out.println("  Protocol:                   HTTP/2 (multiplexed)");
        System.out.println();
        System.out.printf("  Total wall-clock time:      %d ms%n", overallElapsed);
        System.out.printf("  Avg batch round-trip:       %d ms%n", avgBatchTime);
        System.out.printf("  Total server processing:    %d ms%n", totalServerTookMs);
        System.out.printf("  Throughput:                 %d docs/sec%n", docsPerSec);
        System.out.println("=".repeat(60));

        // Verify (search goes via REST)
        client.indices().refresh(r -> r.index(INDEX));
        var search = client.search(s -> s.index(INDEX), Product.class);
        System.out.printf("%n  🔍 Verification (via REST): %d docs indexed%n",
            search.hits().total().value());

        // Cleanup
        client.indices().delete(d -> d.index(INDEX));
        hybridTransport.close();
        System.out.println("  🧹 Cleaned up\n");
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
