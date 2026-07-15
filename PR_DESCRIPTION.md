## Transparent gRPC Transport for opensearch-java

This PR adds a transparent gRPC transport layer to the opensearch-java client. Bulk operations are automatically routed over gRPC for improved performance — lower latency, higher throughput, and smaller payloads via HTTP/2 and Protocol Buffers — while all other operations fall back to REST seamlessly. Users configure the transport once and use the standard `OpenSearchClient` API without any protobuf imports, channel management, or code changes.

OpenSearch 3.5+ exposes a gRPC endpoint (default port 9400) alongside REST for Bulk and k-NN operations. This implementation bridges the gap so that existing client code benefits from gRPC performance without migration effort.

### How It Works

The `HybridTransport` wraps both a `GrpcTransport` (for bulk) and a standard REST transport (for everything else). When `client.bulk()` is called, the request is converted from the client's Java types to protobuf, sent over gRPC, and the response is converted back — all transparently. If gRPC is unavailable or the conversion fails, the request automatically retries via REST.

```java
// REST transport (search, index management, etc.)
var restTransport = ApacheHttpClient5TransportBuilder.builder(new HttpHost("http", "localhost", 9200)).build();

// gRPC transport (bulk operations)
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .basicAuth("admin", "admin")
    .build();

// Combine into HybridTransport
var client = new OpenSearchClient(new HybridTransport(grpcTransport, restTransport));

// Use normally — routing is automatic
client.bulk(bulkRequest);   // → gRPC (fast path)
client.search(searchReq);   // → REST (fallback)
client.info();              // → REST (fallback)
```

### Authentication

All three authentication methods supported by OpenSearch are implemented as gRPC `ClientInterceptor`s that attach credentials to every outgoing call's metadata.

**TLS + Basic Auth** works the same way as the REST client — provide credentials and a trust configuration:

```java
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder()
        .trustCertificatePath("/path/to/ca.pem")
        .build())
    .basicAuth("admin", "admin")
    .build();
```

**AWS SigV4** signs each gRPC call using the same AWS SDK v2 credential providers that `AwsSdk2Transport` uses for REST. The interceptor constructs a synthetic URL from the gRPC method path (e.g., `https://host/opensearch.DocumentService/Bulk`), signs it with `AwsV4HttpSigner`, and attaches the Authorization headers as gRPC metadata. TLS is enforced when SigV4 is configured.

```java
var grpcTransport = GrpcTransport.builder("domain.us-east-1.es.amazonaws.com", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder().build())
    .sigV4(GrpcSigV4Config.builder()
        .region(Region.US_EAST_1)
        .service("es")
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build())
    .build();
```

**JWT** uses a token supplier that is called on every request to support automatic token rotation:

```java
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder().trustCertificatePath("/ca.pem").build())
    .jwtAuth(() -> myOidcProvider.getAccessToken())
    .build();
```

### Connection Management

The gRPC `ManagedChannel` provides built-in connection health management that's more capable than REST's passive dead-node tracking. The channel automatically reconnects on failure with exponential backoff, and the `GrpcChannelHealthMonitor` exposes the connectivity state machine (`IDLE → CONNECTING → READY → TRANSIENT_FAILURE`) so callers can check readiness or register state change callbacks.

```java
// Warm up the connection at startup
transport.healthMonitor().waitForReady(5, TimeUnit.SECONDS);

// Check before sending
if (transport.healthMonitor().isReady()) { /* connected */ }
```

Retry logic is configurable via `GrpcTransportOptions` — max retries, backoff interval, keepalive pings, idle timeout, and max inbound message size all have sensible defaults that match the OpenSearch server's gRPC settings.

### Translation Layer

The translation layer converts between the client's Java API types and protobuf types mechanically. `BulkRequestConverter` walks the typed `BulkOperation` list (index, create, update, delete), serializes document bodies to JSON bytes via `JsonpMapper`, and builds the protobuf `BulkRequest`. `BulkResponseConverter` does the reverse — converting each protobuf `ResponseItem` back to the client's `BulkResponseItem` with all fields (status, result, _version, _seq_no, _primary_term, _shards, error).

Status code mapping follows the OpenSearch server's `RestToGrpcStatusConverter` — gRPC UNAVAILABLE becomes `TransportException`, NOT_FOUND becomes `OpenSearchException(404)`, and so on, so existing catch blocks work unchanged.

### Tests

The implementation includes 112 unit tests across 6 test files covering all conversion logic, transport routing, authentication interceptors, channel management, and TLS configuration. Three condensed integration tests validate end-to-end behavior against a real OpenSearch 3.5+ cluster using the project's existing test framework (`OpenSearchJavaClientTestCase` + Testcontainers). Tests are version-gated via `assumeTrue` and skip automatically on older clusters.

### CI Configuration

The Docker configuration in `.ci/opensearch/` has been updated to enable gRPC transport (port 9400) in the test container. The `Dockerfile` now copies `opensearch.yml` with `aux.transport.types: [transport-grpc]` into the image, and `docker-compose.yml` exposes port 9400. A standalone `validate-grpc-integration.sh` script is included for local validation.

### Dependencies

gRPC support is configured as an optional feature (`grpcSupport`) with zero impact on users who don't enable it. Dependencies are `io.grpc` (1.68.0), `com.google.protobuf` (3.25.5), and `org.opensearch:protobufs` (1.2.0).
