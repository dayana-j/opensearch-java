# OpenSearch Java gRPC Implementation — Detailed Breakdown

**Repo:** https://github.com/dayana-j/opensearch-java.git  
**Clone:** `/Users/jeadayao/projs/java_grpc_client`  
**Deadline:** Monday, July 21 2026  
**Reference:** Python implementation in `/Users/jeadayao/projs/opensearch/python-client-grpc/opensearch-py/opensearch_grpc/`

---

## Architecture Overview

The Java client's `Transport` interface provides a clean seam:

```java
public interface Transport extends Closeable {
    <RequestT, ResponseT, ErrorT> ResponseT performRequest(
        RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, TransportOptions options
    ) throws IOException;
    // + async, jsonpMapper(), options()
}
```

We extend this with:
- **`GrpcTransport`** — pure gRPC. Throws on unsupported endpoints.
- **`HybridTransport`** — composes GrpcTransport + HTTP transport, routes per-call, falls back to HTTP.

The existing `ApacheHttpClient5TransportBuilder` creates an `OpenSearchTransport` for REST. Our `GrpcTransportBuilder` adds gRPC parameters and creates a `HybridTransport` that wraps both.

---

## Exception Classes (for error handling)

| Exception | Package | Use Case |
|-----------|---------|----------|
| `TransportException` | `o.o.client.transport` | extends IOException; connection failures, timeouts, auth errors |
| `OpenSearchException` | `o.o.client.opensearch._types` | wraps `ErrorResponse` (type + reason + status); server-side errors |
| `OpenSearchClientException` | `o.o.client.opensearch.generic` | wraps raw Response; status code errors |

**gRPC Status Code → Exception Mapping:**

| gRPC Status | HTTP Equiv | Java Exception |
|-------------|-----------|----------------|
| UNAVAILABLE (14) | 503 | `TransportException("gRPC unavailable: ...")` |
| DEADLINE_EXCEEDED (4) | 408 | `TransportException("gRPC deadline exceeded: ...")` |
| UNAUTHENTICATED (16) | 401 | `TransportException("Authentication failed: ...")` |
| PERMISSION_DENIED (7) | 403 | `TransportException("Forbidden: ...")` |
| NOT_FOUND (5) | 404 | `OpenSearchException(ErrorResponse{status=404, ...})` |
| INVALID_ARGUMENT (3) | 400 | `OpenSearchException(ErrorResponse{status=400, ...})` |
| ALREADY_EXISTS (6) | 409 | `OpenSearchException(ErrorResponse{status=409, ...})` |
| RESOURCE_EXHAUSTED (8) | 429 | `TransportException("Rate limited: ...")` |
| INTERNAL (13) | 500 | `TransportException("Internal gRPC error: ...")` |
| UNIMPLEMENTED (12) | 501 | `UnsupportedOperationException("gRPC endpoint not supported")` |

---

## Branch 1: `translation`

### Purpose
Convert opensearch-java BulkRequest types ↔ protobuf BulkRequest types.

### New Files

```
java-client/src/main/java/org/opensearch/client/transport/grpc/translation/
├── BulkRequestConverter.java          # BulkRequest → proto BulkRequest
├── BulkResponseConverter.java         # proto BulkResponse → BulkResponse
├── GrpcStatusConverter.java           # gRPC status code → HTTP status / exceptions
└── FieldMappingUtil.java              # Enum mappers (Refresh, VersionType, OpType, etc.)
```

### Test Files

```
java-client/src/test/java/org/opensearch/client/transport/grpc/translation/
├── BulkRequestConverterTest.java      # Unit tests for request conversion
├── BulkResponseConverterTest.java     # Unit tests for response conversion
├── GrpcStatusConverterTest.java       # Unit tests for status mapping
└── FieldMappingUtilTest.java          # Unit tests for enum mapping
```

### Affected Existing Files
- `java-client/build.gradle.kts` — add protobuf/gRPC dependencies

### Unit Tests Plan

| Test | What it verifies |
|------|-----------------|
| `testIndexOperationConversion` | Index op with all meta fields (_id, _index, routing, pipeline, version, version_type, if_primary_term, if_seq_no) maps correctly |
| `testCreateOperationConversion` | Create op maps to WriteOperation proto |
| `testUpdateOperationConversion` | Update op with doc, script, upsert, doc_as_upsert maps to UpdateAction proto |
| `testDeleteOperationConversion` | Delete op maps to DeleteOperation proto |
| `testBulkRequestLevelParams` | Top-level params (index, refresh, timeout, pipeline, routing, require_alias, source, wait_for_active_shards) map |
| `testMultipleOperationsBuild` | Multiple mixed ops produce correct BulkRequestBody list |
| `testDocumentSerializationToBytes` | Generic `<TDocument>` serialized via JsonpMapper to bytes |
| `testResponseItemSuccess` | Response item with status=0, result="created" → status 201 |
| `testResponseItemError` | Response item with error field → includes error cause |
| `testBulkResponseWithErrors` | Response with `errors=true` converts correctly |
| `testIngestTookField` | Optional ingest_took maps |
| `testGrpcStatusToHttpStatus` | All 17 gRPC codes map to correct HTTP status |
| `testRefreshEnumMapping` | "true", "false", "wait_for" → proto enum |
| `testVersionTypeMapping` | "internal", "external", "external_gte" → proto enum |

### Integration Tests Plan

```
java-client/src/test/java/org/opensearch/client/opensearch/integTest/grpc/
└── GrpcBulkTranslationIT.java
```

| Test | What it verifies |
|------|-----------------|
| `testBulkIndexViaGrpcRoundTrip` | Build BulkRequest → convert to proto → send to OpenSearch gRPC port → convert response back → verify docs indexed |
| `testBulkMixedOpsRoundTrip` | Index + Update + Delete in one bulk → verify all ops executed |
| `testBulkWithPartialErrors` | Send ops that will partially fail (e.g. update nonexistent doc) → verify errors in response |

---

## Branch 2: `transport-connection-management`

### Purpose
GrpcTransport and HybridTransport implementation — channel lifecycle, routing, fallback, retry.

### New Files

```
java-client/src/main/java/org/opensearch/client/transport/grpc/
├── GrpcTransport.java                 # Implements OpenSearchTransport, routes to gRPC stubs
├── GrpcTransportBuilder.java          # Builder pattern for GrpcTransport
├── HybridTransport.java               # Composes GrpcTransport + REST transport, fallback
├── HybridTransportBuilder.java        # Builder for HybridTransport
├── GrpcEndpointRegistry.java          # Registry of which endpoints support gRPC
└── GrpcTransportOptions.java          # gRPC-specific transport options (timeout, max msg size)
```

### Test Files

```
java-client/src/test/java/org/opensearch/client/transport/grpc/
├── GrpcTransportTest.java             # Unit tests for GrpcTransport
├── HybridTransportTest.java           # Unit tests for routing and fallback
├── GrpcTransportBuilderTest.java      # Builder validation
├── GrpcEndpointRegistryTest.java      # Registry lookup tests
└── GrpcTransportOptionsTest.java      # Options validation
```

### Affected Existing Files
- `java-client/build.gradle.kts` — add `io.grpc` dependencies
- `java-client/src/main/java/org/opensearch/client/transport/OpenSearchTransport.java` — (no change needed, GrpcTransport implements this)

### Unit Tests Plan

| Test | What it verifies |
|------|-----------------|
| `testBulkRequestRoutedToGrpc` | BulkRequest.endpoint goes through gRPC path |
| `testSearchRequestRoutedToRest` | SearchRequest (unsupported) goes through REST |
| `testUnsupportedEndpointThrows` | GrpcTransport throws for unsupported endpoints |
| `testHybridFallbackOnGrpcFailure` | If gRPC throws, HybridTransport retries on REST |
| `testHybridFallbackOnConversionFailure` | If translation fails, falls back to REST |
| `testChannelCreatedOnConstruction` | Channel is created and connectable |
| `testChannelShutdownOnClose` | close() gracefully shuts down channel |
| `testRetryOnUnavailable` | UNAVAILABLE triggers retry with backoff |
| `testRetryExhaustedThrows` | After max retries, throws TransportException |
| `testAsyncBulkRequest` | performRequestAsync returns CompletableFuture that resolves |
| `testBuilderValidation` | Missing grpcHost throws IllegalArgumentException |
| `testMaxMessageSizeConfig` | Builder option propagates to channel |
| `testKeepaliveConfig` | Keepalive settings apply to channel |

### Integration Tests Plan

```
java-client/src/test/java/org/opensearch/client/opensearch/integTest/grpc/
└── GrpcTransportIT.java
```

| Test | What it verifies |
|------|-----------------|
| `testBulkViaHybridTransport` | Full e2e: create index → bulk via gRPC → search via REST → verify |
| `testFallbackWhenGrpcUnavailable` | gRPC port unreachable → operations succeed via REST |
| `testChannelPersistsAcrossCalls` | Multiple bulk calls reuse same channel |
| `testGracefulShutdown` | transport.close() completes cleanly |

---

## Branch 3: `tls-basic-auth`

### Purpose
TLS channel configuration and basic auth interceptor for gRPC.

### New Files

```
java-client/src/main/java/org/opensearch/client/transport/grpc/
├── GrpcTlsConfig.java                 # TLS configuration (trust store, client cert, verify mode)
├── BasicAuthInterceptor.java          # gRPC ClientInterceptor for basic auth metadata
└── GrpcChannelFactory.java            # Creates plaintext or TLS channels based on config
```

### Test Files

```
java-client/src/test/java/org/opensearch/client/transport/grpc/
├── GrpcTlsConfigTest.java            # TLS config validation
├── BasicAuthInterceptorTest.java      # Auth header attachment
└── GrpcChannelFactoryTest.java        # Channel creation (plaintext vs TLS)
```

### Affected Existing Files
- `GrpcTransportBuilder.java` — add TLS and basic auth builder methods

### Unit Tests Plan

| Test | What it verifies |
|------|-----------------|
| `testBasicAuthHeaderAttached` | Interceptor adds `Authorization: Basic <base64>` to metadata |
| `testBasicAuthBase64Encoding` | "admin:admin" → correct base64 |
| `testBasicAuthPreservesExistingMetadata` | Existing metadata not overwritten |
| `testTlsConfigWithTrustStore` | Trust store path/password accepted |
| `testTlsConfigWithInsecure` | verify_certs=false creates permissive trust manager |
| `testTlsConfigWithClientCert` | mTLS with client cert + key |
| `testChannelFactoryPlaintext` | No TLS → `ManagedChannelBuilder.forAddress()` |
| `testChannelFactorySecure` | With TLS → `NettyChannelBuilder` with SslContext |
| `testBuilderWithTlsAndBasicAuth` | Combined TLS + basic auth produces valid channel |

### Integration Tests Plan

```
java-client/src/test/java/org/opensearch/client/opensearch/integTest/grpc/
└── GrpcTlsBasicAuthIT.java
```

| Test | What it verifies |
|------|-----------------|
| `testBulkWithTlsAndBasicAuth` | Bulk succeeds against TLS-secured OpenSearch with basic auth |
| `testInvalidCredentialsFails` | Wrong password → UNAUTHENTICATED → TransportException |
| `testTlsWithSelfSignedCert` | Self-signed cert with trust store works |
| `testPlaintextConnectionToSecureClusterFails` | Plaintext to TLS port → connection error |

---

## Branch 4: `grpc_sigv4`

### Purpose
AWS SigV4 signing interceptor for gRPC requests.

### New Files

```
java-client/src/main/java/org/opensearch/client/transport/grpc/
├── GrpcSigV4Interceptor.java          # ClientInterceptor that signs with SigV4
└── GrpcSigV4Config.java               # Region, service name, credential provider config
```

### Test Files

```
java-client/src/test/java/org/opensearch/client/transport/grpc/
├── GrpcSigV4InterceptorTest.java      # Signing logic unit tests
└── GrpcSigV4ConfigTest.java           # Config validation
```

### Affected Existing Files
- `java-client/build.gradle.kts` — ensure `awsSdk2Support` feature includes gRPC path
- `GrpcTransportBuilder.java` — add `.sigV4(region, service, credentialsProvider)` method

### Unit Tests Plan

| Test | What it verifies |
|------|-----------------|
| `testSignedHeadersInMetadata` | Authorization, X-Amz-Date, X-Amz-Security-Token, X-Amz-Content-SHA256 present |
| `testSigningUrlUsesGrpcMethodPath` | URL = `https://host/opensearch.DocumentService/Bulk` |
| `testBodyIncludedInSignature` | Protobuf serialized bytes used for content hash |
| `testRegionRequired` | null region → IllegalArgumentException |
| `testCredentialProviderRequired` | null credentials → IllegalArgumentException |
| `testServiceNameEsVsAoss` | "es" for managed, "aoss" for serverless |
| `testFreshSignaturePerRequest` | Each call produces different X-Amz-Date |
| `testTemporaryCredentialsIncludeToken` | Session token included in metadata |
| `testDefaultCredentialsProviderWorks` | DefaultCredentialsProvider accepted |
| `testTlsRequiredWithSigV4` | SigV4 without TLS → configuration error |

### Integration Tests Plan

```
java-client/src/test/java/org/opensearch/client/opensearch/integTest/grpc/
└── GrpcSigV4IT.java
```

| Test | What it verifies |
|------|-----------------|
| `testBulkWithSigV4AgainstAWS` | Bulk succeeds against AWS OpenSearch with SigV4 (requires real domain) |
| `testInvalidCredentialsFails` | Bad credentials → UNAUTHENTICATED |
| `testRestFallbackAlsoUsesSigV4` | Non-bulk ops (search) use REST with SigV4 (AwsSdk2Transport) |

*Note: SigV4 integration tests require a real AWS OpenSearch domain. They should be skipped in CI unless explicitly enabled.*

---

## Branch 5: `grpc_jwt`

### Purpose
JWT bearer token authentication interceptor for gRPC.

### New Files

```
java-client/src/main/java/org/opensearch/client/transport/grpc/
├── JwtAuthInterceptor.java            # ClientInterceptor for Bearer token
└── JwtTokenSupplier.java              # Interface for token refresh (Supplier<String>)
```

### Test Files

```
java-client/src/test/java/org/opensearch/client/transport/grpc/
├── JwtAuthInterceptorTest.java        # Token attachment and refresh tests
└── JwtTokenSupplierTest.java          # Token supplier interface tests
```

### Affected Existing Files
- `GrpcTransportBuilder.java` — add `.jwtAuth(tokenSupplier)` method

### Unit Tests Plan

| Test | What it verifies |
|------|-----------------|
| `testBearerTokenInMetadata` | `Authorization: Bearer <token>` in gRPC metadata |
| `testTokenRefreshCalledPerRequest` | Supplier invoked on each call (not cached) |
| `testExpiredTokenTriggersRefresh` | New token used after previous expires |
| `testNullTokenThrows` | Supplier returning null → IllegalStateException |
| `testEmptyTokenThrows` | Supplier returning "" → IllegalStateException |
| `testPreservesExistingMetadata` | Other metadata keys not overwritten |
| `testStaticTokenSupplier` | Simple static token works |
| `testCallableTokenSupplier` | Lambda token supplier works |

### Integration Tests Plan

```
java-client/src/test/java/org/opensearch/client/opensearch/integTest/grpc/
└── GrpcJwtIT.java
```

| Test | What it verifies |
|------|-----------------|
| `testBulkWithJwtToken` | Bulk succeeds with valid JWT against OpenSearch configured with JWT auth backend |
| `testInvalidJwtFails` | Invalid token → UNAUTHENTICATED |
| `testExpiredJwtFails` | Expired token → UNAUTHENTICATED |

---

## Dependencies to Add (`build.gradle.kts`)

```kotlin
// gRPC and Protobuf (optional feature, like awsSdk2Support)
java {
    registerFeature("grpcSupport") {
        usingSourceSet(sourceSets.get("main"))
    }
}

dependencies {
    // gRPC
    "grpcSupportCompileOnly"("io.grpc:grpc-api:1.68.0")
    "grpcSupportCompileOnly"("io.grpc:grpc-stub:1.68.0")
    "grpcSupportCompileOnly"("io.grpc:grpc-protobuf:1.68.0")
    "grpcSupportCompileOnly"("io.grpc:grpc-netty-shaded:1.68.0")
    
    // OpenSearch Protobufs (compiled Java classes)
    "grpcSupportCompileOnly"("org.opensearch:protobufs:1.2.0")
    
    // Test dependencies
    testImplementation("io.grpc:grpc-api:1.68.0")
    testImplementation("io.grpc:grpc-stub:1.68.0")
    testImplementation("io.grpc:grpc-protobuf:1.68.0")
    testImplementation("io.grpc:grpc-netty-shaded:1.68.0")
    testImplementation("io.grpc:grpc-testing:1.68.0")
    testImplementation("org.opensearch:protobufs:1.2.0")
}
```

---

## User-Facing API (How customers use it)

### Basic (plaintext, no auth)

```java
OpenSearchTransport grpcTransport = GrpcTransportBuilder.builder("localhost", 9400)
    .restTransport(existingHttpTransport)  // for fallback
    .build();

OpenSearchClient client = new OpenSearchClient(grpcTransport);
client.bulk(bulkRequest);  // → goes over gRPC
client.search(searchReq);  // → falls back to REST
```

### With TLS + Basic Auth

```java
OpenSearchTransport transport = GrpcTransportBuilder.builder("localhost", 9400)
    .restTransport(existingHttpTransport)
    .tls(tlsConfig -> tlsConfig
        .trustStorePath("/path/to/truststore.jks")
        .trustStorePassword("changeit"))
    .basicAuth("admin", "admin")
    .build();
```

### With AWS SigV4

```java
OpenSearchTransport transport = GrpcTransportBuilder.builder("domain.us-east-1.es.amazonaws.com", 9400)
    .restTransport(awsSdk2Transport)
    .tls()  // TLS required for SigV4
    .sigV4(sigv4 -> sigv4
        .region(Region.US_EAST_1)
        .service("es")
        .credentialsProvider(DefaultCredentialsProvider.create()))
    .build();
```

### With JWT

```java
OpenSearchTransport transport = GrpcTransportBuilder.builder("localhost", 9400)
    .restTransport(existingHttpTransport)
    .tls()
    .jwtAuth(() -> myJwtTokenProvider.getToken())
    .build();
```

---

## Schedule (Week of Jul 14–18)

| Day | Branch | Focus |
|-----|--------|-------|
| Mon | `translation` | BulkRequestConverter, BulkResponseConverter, enum mappers, unit tests |
| Tue | `translation` + `transport-connection-management` | Finish translation tests, start GrpcTransport + HybridTransport |
| Wed | `transport-connection-management` | Channel management, retry, fallback, GrpcTransportBuilder, unit tests |
| Thu | `tls-basic-auth` + `grpc_sigv4` | TLS config, BasicAuthInterceptor, GrpcSigV4Interceptor |
| Fri | `grpc_jwt` + integration tests | JWT interceptor, integration tests across all branches |

---

## Key Differences from Python Implementation

| Aspect | Python | Java |
|--------|--------|------|
| Transport seam | Extends `Transport`, overrides `perform_request(method, url, body)` | Implements `OpenSearchTransport.performRequest(RequestT, Endpoint, Options)` |
| Routing | Regex on URL pattern (`/_bulk`) | Check `endpoint` class identity or requestUrl() |
| Request conversion | Parse NDJSON strings/dicts → proto | Walk typed `BulkOperation` objects → proto |
| Document serialization | `json.dumps(doc).encode()` | `JsonpMapper.serialize(doc)` → bytes |
| Auth interceptors | `grpc.UnaryUnaryClientInterceptor` | `io.grpc.ClientInterceptor` |
| AWS SigV4 | botocore `AWSV4Signer` | AWS SDK v2 `AwsV4HttpSigner` |
| Async | Separate async impl needed | Built-in via `performRequestAsync` + gRPC async stubs |
| Channel | `grpc.insecure_channel()` / `grpc.secure_channel()` | `ManagedChannelBuilder` / `NettyChannelBuilder` |

---

## Protobuf Types (from `org.opensearch.protobufs`)

Key types we'll use from the Maven jar:
- `BulkRequest`, `BulkRequestBody`, `OperationContainer`
- `IndexOperation`, `WriteOperation` (create), `UpdateOperation`, `DeleteOperation`
- `UpdateAction`, `Script`, `InlineScript`, `StoredScriptId`
- `SourceConfig`, `SourceConfigParam`, `SourceFilter`, `StringArray`
- `WaitForActiveShards`, `Refresh` (enum), `VersionType` (enum), `OpType` (enum)
- `BulkResponse`, `BulkResponseItem`
- `DocumentServiceGrpc` (stub class)
