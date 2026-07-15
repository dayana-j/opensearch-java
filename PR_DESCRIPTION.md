## Transparent gRPC Transport for opensearch-java

This PR adds a transparent gRPC transport layer to the opensearch-java client as a separate `java-client-grpc` module. Bulk operations are automatically routed over gRPC for improved performance — lower latency, higher throughput, and smaller payloads via HTTP/2 and Protocol Buffers — while all other operations fall back to REST seamlessly. Users configure the transport once and use the standard `OpenSearchClient` API without any protobuf imports, channel management, or code changes.

OpenSearch 3.5+ exposes a gRPC endpoint (default port 9400) alongside REST for Bulk and k-NN operations. This implementation bridges the gap so that existing client code benefits from gRPC performance without migration effort.

### Module Structure

The gRPC transport lives in its own module to isolate gRPC dependencies (`grpc-netty-shaded`, `protobuf-java`) from the core client, preventing classpath conflicts with the OpenSearch test framework.

```
opensearch-java/
├── java-client/           ← core client (unchanged, zero gRPC dependencies)
├── java-client-grpc/      ← gRPC transport module (this PR)
└── samples/
```

Users add the gRPC module as an optional dependency:
```groovy
dependencies {
    implementation 'org.opensearch.client:opensearch-java:3.x'
    implementation 'org.opensearch.client:opensearch-java-grpc:3.x'  // optional
}
```

### How It Works

The `HybridTransport` wraps both a `GrpcTransport` (for bulk) and a standard REST transport (for everything else). When `client.bulk()` is called, the request is converted from the client's Java types to protobuf, sent over gRPC, and the response is converted back — all transparently. If gRPC is unavailable or the conversion fails, the request automatically retries via REST.

```java
var restTransport = ApacheHttpClient5TransportBuilder.builder(new HttpHost("http", "localhost", 9200)).build();

var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .basicAuth("admin", "admin")
    .build();

var client = new OpenSearchClient(new HybridTransport(grpcTransport, restTransport));

client.bulk(bulkRequest);   // → gRPC (fast path)
client.search(searchReq);   // → REST (automatic fallback)
```

### Authentication

All three authentication methods supported by OpenSearch are implemented as gRPC `ClientInterceptor`s that attach credentials to every outgoing call's metadata.

**TLS + Basic Auth:**

```java
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder()
        .trustCertificatePath("/path/to/ca.pem")
        .build())
    .basicAuth("admin", "admin")
    .build();
```

**AWS SigV4** signs each gRPC call using AWS SDK v2 credential providers. The interceptor constructs a synthetic URL from the gRPC method path, signs it with `AwsV4HttpSigner`, and attaches the Authorization headers as gRPC metadata. Body-aware payload signing is supported for full SigV4 compliance.

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

**JWT** uses a token supplier called on every request for automatic token rotation:

```java
.jwtAuth(() -> myOidcProvider.getAccessToken())
```

### Connection Management

The gRPC `ManagedChannel` provides built-in connection health management. The channel automatically reconnects on failure with exponential backoff, and the `GrpcChannelHealthMonitor` exposes the connectivity state machine so callers can check readiness or register state change callbacks. Retry logic is configurable via `GrpcTransportOptions`.

### Translation Layer

`BulkRequestConverter` walks the typed `BulkOperation` list, serializes document bodies to JSON bytes via `JsonpMapper`, and builds the protobuf `BulkRequest`. `BulkResponseConverter` converts each protobuf `ResponseItem` back to the client's `BulkResponseItem` with all fields. Status code mapping follows the OpenSearch server's `RestToGrpcStatusConverter`.

### Tests

112 unit tests across 6 test files in `java-client-grpc` covering all conversion logic, transport routing, authentication interceptors, channel management, and TLS configuration. Three integration tests validate end-to-end behavior against a real OpenSearch 3.5+ cluster using the project's existing test framework. Tests are version-gated and skip automatically on older clusters.

### CI Configuration

The Docker configuration in `.ci/opensearch/` enables gRPC transport (port 9400) in the test container. A standalone `validate-grpc-integration.sh` script is included for local validation. gRPC integration tests run via `./gradlew :java-client-grpc:test`.

### Dependencies (isolated in java-client-grpc module)

```
io.grpc:grpc-api:1.68.0
io.grpc:grpc-stub:1.68.0
io.grpc:grpc-protobuf:1.68.0
io.grpc:grpc-netty-shaded:1.68.0
com.google.protobuf:protobuf-java:3.25.5
org.opensearch:protobufs:1.2.0
```

Zero impact on `java-client` users who don't enable gRPC — dependencies are fully isolated in the separate module.

### Related

This is the Java counterpart to the opensearch-py gRPC implementation, following the same design pattern:

- [opensearch-py #1058](https://github.com/opensearch-project/opensearch-py/pull/1058) — gRPC translation layer
- [opensearch-py #1078](https://github.com/opensearch-project/opensearch-py/pull/1078) — gRPC transport and connection management
- [opensearch-py #1087](https://github.com/opensearch-project/opensearch-py/pull/1087) — gRPC TLS & Basic auth
- [opensearch-py #1089](https://github.com/opensearch-project/opensearch-py/pull/1089) — gRPC SigV4
- [opensearch-py #1093](https://github.com/opensearch-project/opensearch-py/pull/1093) — gRPC JWT

OpenSearch gRPC API documentation: https://docs.opensearch.org/latest/api-reference/grpc-apis/index/
