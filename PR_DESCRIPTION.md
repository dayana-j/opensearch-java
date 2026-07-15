## Transparent gRPC Transport for opensearch-java

### Summary

Adds a transparent gRPC transport layer that routes bulk operations over gRPC for improved performance while falling back to REST for all other operations. Users configure the transport once — no protobuf imports, no channel management, no code rewrite.

gRPC uses HTTP/2 and Protocol Buffers for lower latency, higher throughput, and smaller payload sizes compared to REST/JSON. OpenSearch 3.5+ supports gRPC for Bulk and k-NN APIs.

### Usage

**Basic (plaintext):**
```java
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .basicAuth("admin", "admin")
    .build();

var client = new OpenSearchClient(new HybridTransport(grpcTransport, restTransport));

client.bulk(bulkRequest);  // → gRPC (transparent)
client.search(searchReq);  // → REST (automatic fallback)
```

**AWS SigV4:**
```java
var grpcTransport = GrpcTransport.builder("domain.us-east-1.es.amazonaws.com", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder().build())
    .sigV4(GrpcSigV4Config.builder()
        .region(Region.US_EAST_1)
        .service("es")
        .build())
    .build();
```

**JWT:**
```java
.jwtAuth(() -> myTokenProvider.getAccessToken())
```

### What's Included

**Transport layer:**
- `GrpcTransport` — implements `OpenSearchTransport`, routes supported ops to gRPC stubs
- `HybridTransport` — composes gRPC + REST, automatic fallback on failure
- `GrpcChannelFactory` — builds plaintext or TLS channels (Netty SSL)
- `GrpcChannelHealthMonitor` — exposes channel connectivity state (READY/TRANSIENT_FAILURE)
- `GrpcTransportOptions` — keepalive, max message size, retry config

**Translation layer:**
- `BulkRequestConverter` — client `BulkRequest` → protobuf `BulkRequest`
- `BulkResponseConverter` — protobuf `BulkResponse` → client `BulkResponse`
- `GrpcStatusConverter` — gRPC status codes → `TransportException`/`OpenSearchException`
- `FieldMappingUtil` — enum mappers (Refresh, VersionType, OpType, WaitForActiveShards)

**Authentication:**
- `BasicAuthInterceptor` — `Authorization: Basic <base64>` in gRPC metadata
- `GrpcSigV4Interceptor` — AWS SigV4 signing with body-aware payload hash
- `JwtAuthInterceptor` — `Authorization: Bearer <token>` with per-request refresh

**TLS:**
- `GrpcTlsConfig` — trust cert (PEM), trust store (JKS/PKCS12), mTLS, insecure mode, hostname override

### Tests

- **160+ unit tests** covering all conversion, transport, auth, and TLS classes
- **3 integration tests** (framework-compliant, version-gated to 3.5.0+):
  - `testBulkMixedOpsAndResponseFormat` — all 4 op types + full response validation
  - `testBulkErrorStatusCodes` — 409 conflict, 404 not found
  - `testRestFallback` — confirms non-bulk ops route to REST
- Integration test infrastructure: `GrpcTestContainerRule`, `AbstractGrpcIT`, `GrpcTransportSupport`

### CI Configuration

- `.ci/opensearch/opensearch.yml` — enables gRPC transport on port 9400
- `.ci/opensearch/Dockerfile` — COPY's config into container
- `.ci/opensearch/docker-compose.yml` — exposes port 9400
- `validate-grpc-integration.sh` — standalone validation script

### Documentation

- `guides/grpc.md` — full setup guide with all auth modes
- `samples/GrpcBulk.java` — basic usage example
- `samples/GrpcAwsSigV4.java` — AWS authentication example

### Dependencies Added (optional feature)

```
io.grpc:grpc-api:1.68.0
io.grpc:grpc-stub:1.68.0
io.grpc:grpc-protobuf:1.68.0
io.grpc:grpc-netty-shaded:1.68.0
com.google.protobuf:protobuf-java:3.25.5
org.opensearch:protobufs:1.2.0
```

Configured as `grpcSupport` optional feature — zero impact on users who don't enable gRPC.

### Related

- OpenSearch gRPC APIs: https://docs.opensearch.org/latest/api-reference/grpc-apis/index/
- OpenSearch Protobufs: https://github.com/opensearch-project/opensearch-protobufs
- Python equivalent: opensearch-py gRPC transport (same design pattern)
