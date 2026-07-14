# gRPC Transport Guide

The OpenSearch Java client supports gRPC transport for high-performance bulk operations. gRPC uses HTTP/2 and Protocol Buffers for lower latency and higher throughput compared to REST.

## Requirements

- OpenSearch 3.5+ with gRPC transport enabled
- `opensearch-java` client 3.x+
- gRPC dependencies (optional feature)

## OpenSearch Configuration

Enable gRPC transport in `opensearch.yml`:

```yaml
aux.transport.types: [transport-grpc]
aux.transport.transport-grpc.port: '9400'
```

For secure gRPC:
```yaml
aux.transport.types: [secure-transport-grpc]
aux.transport.transport-grpc.port: '9400'
```

## Dependencies

Add the gRPC feature to your `build.gradle`:

```groovy
dependencies {
    implementation 'org.opensearch.client:opensearch-java:3.9.0'
    // gRPC support (optional)
    implementation 'io.grpc:grpc-netty-shaded:1.68.0'
    implementation 'io.grpc:grpc-protobuf:1.68.0'
    implementation 'io.grpc:grpc-stub:1.68.0'
    implementation 'org.opensearch:protobufs:1.2.0'
}
```

Or Maven:
```xml
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.68.0</version>
</dependency>
<dependency>
    <groupId>org.opensearch</groupId>
    <artifactId>protobufs</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Quick Start (Plaintext)

```java
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.grpc.GrpcTransport;
import org.opensearch.client.transport.grpc.HybridTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.apache.hc.core5.http.HttpHost;

// REST transport (for search, index management, etc.)
var restTransport = ApacheHttpClient5TransportBuilder
    .builder(new HttpHost("http", "localhost", 9200))
    .build();

// gRPC transport (for bulk)
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .build();

// Combine: bulk → gRPC, everything else → REST
var transport = new HybridTransport(grpcTransport, restTransport);
var client = new OpenSearchClient(transport);

// Use normally — routing is transparent
client.bulk(bulkRequest);   // → gRPC (fast)
client.search(searchReq);   // → REST (automatic fallback)
client.info();              // → REST

// Clean up
transport.close();
```

## TLS + Basic Auth

```java
import org.opensearch.client.transport.grpc.GrpcTlsConfig;

var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder()
        .trustCertificatePath("/path/to/ca.pem")
        .build())
    .basicAuth("admin", "admin")
    .build();
```

### TLS Options

| Method | Description |
|--------|-------------|
| `.trustCertificatePath(path)` | Trust a specific CA certificate (PEM) |
| `.trustStorePath(path)` | Use a Java KeyStore (JKS/PKCS12) |
| `.insecure(true)` | Trust all certificates (dev/test only) |
| `.clientCertificatePath(cert)` + `.clientKeyPath(key)` | Mutual TLS (mTLS) |
| `.hostnameOverride(hostname)` | Verify cert against a different hostname |

Shorthand for development:
```java
.tlsInsecure()  // equivalent to .tls(GrpcTlsConfig.insecure())
```

## AWS SigV4 (Amazon OpenSearch Service)

```java
import org.opensearch.client.transport.grpc.GrpcSigV4Config;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

var grpcTransport = GrpcTransport.builder("search-domain.us-east-1.es.amazonaws.com", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder().build())  // system trust store
    .sigV4(GrpcSigV4Config.builder()
        .region(Region.US_EAST_1)
        .service("es")                      // "es" for managed, "aoss" for serverless
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build())
    .build();
```

TLS is required when using SigV4. The builder will throw `IllegalStateException` if `.sigV4()` is used without `.tls()`.

## JWT Authentication

```java
var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .tls(GrpcTlsConfig.builder().trustCertificatePath("/ca.pem").build())
    .jwtAuth(() -> myTokenProvider.getAccessToken())  // called per request
    .build();
```

The token supplier is invoked on every gRPC call to support automatic token refresh.

## Supported Operations

Currently, the following operations are routed over gRPC:

| Operation | gRPC Status |
|-----------|-------------|
| Bulk (index, create, update, delete) | ✅ GA (OpenSearch 3.2+) |
| Search | 🔄 Experimental |
| k-NN search | ✅ GA (OpenSearch 3.2+) |

All other operations automatically fall back to REST through the `HybridTransport`.

## Transport Options

Configure gRPC channel behavior:

```java
import org.opensearch.client.transport.grpc.GrpcTransportOptions;
import java.util.concurrent.TimeUnit;

var options = GrpcTransportOptions.builder()
    .maxInboundMessageSize(20 * 1024 * 1024)  // 20MB (default: 10MB)
    .keepAliveTime(30, TimeUnit.SECONDS)       // ping interval
    .keepAliveTimeout(10, TimeUnit.SECONDS)    // ping timeout
    .idleTimeout(5, TimeUnit.MINUTES)          // close idle connections
    .maxRetries(3)                             // retry on transient errors
    .retryBackoff(100, TimeUnit.MILLISECONDS)  // initial backoff (doubles each retry)
    .build();

var grpcTransport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(new JacksonJsonpMapper())
    .grpcOptions(options)
    .build();
```

## Connection Health Monitoring

Check gRPC channel connectivity state:

```java
var transport = GrpcTransport.builder("localhost", 9400)
    .jsonpMapper(mapper).build();

// Check if ready
if (transport.healthMonitor().isReady()) {
    // channel is connected
}

// Wait for connection at startup
boolean connected = transport.healthMonitor().waitForReady(5, TimeUnit.SECONDS);

// Register state change callback
transport.healthMonitor().setStateChangeListener((prev, next) -> {
    System.out.println("Channel: " + prev + " → " + next);
});
```

Channel states: `IDLE` → `CONNECTING` → `READY` → `TRANSIENT_FAILURE` → `SHUTDOWN`

## Fallback Behavior

The `HybridTransport` handles failures transparently:

| Scenario | Behavior |
|----------|----------|
| Endpoint not supported by gRPC | Routes directly to REST |
| gRPC connection fails | Catches error, retries via REST |
| Protobuf conversion fails | Falls back to REST |
| REST also fails | Throws exception to caller |

Disable fallback if you want strict gRPC-only for supported operations:
```java
var transport = new HybridTransport(grpcTransport, restTransport, false);  // fallbackOnError=false
```

## Performance Tips

- Use bulk operations for maximum gRPC benefit (amortizes connection overhead)
- Keep batch sizes between 1,000–10,000 documents for optimal throughput
- Enable keepalive for long-lived connections to prevent idle disconnects
- The gRPC channel automatically reconnects on transient failures
