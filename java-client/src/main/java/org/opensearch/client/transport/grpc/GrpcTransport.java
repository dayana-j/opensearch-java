/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportException;
import org.opensearch.client.transport.TransportOptions;
import org.opensearch.client.transport.grpc.translation.BulkRequestConverter;
import org.opensearch.client.transport.grpc.translation.BulkResponseConverter;
import org.opensearch.client.transport.grpc.translation.GrpcStatusConverter;
import org.opensearch.protobufs.services.DocumentServiceGrpc;

/**
 * Pure gRPC transport for OpenSearch. Implements {@link OpenSearchTransport} and routes
 * supported operations (Bulk) through gRPC stubs.
 * <p>
 * For unsupported endpoints, this transport throws {@link UnsupportedOperationException}.
 * Use {@link HybridTransport} for automatic REST fallback.
 * <p>
 * Usage:
 * <pre>{@code
 * GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
 *     .jsonpMapper(mapper)
 *     .build();
 *
 * OpenSearchClient client = new OpenSearchClient(transport);
 * client.bulk(bulkRequest); // goes over gRPC
 * }</pre>
 */
public class GrpcTransport implements OpenSearchTransport {

    private final ManagedChannel channel;
    private final DocumentServiceGrpc.DocumentServiceBlockingStub documentStub;
    private final JsonpMapper jsonpMapper;
    private final GrpcTransportOptions grpcOptions;
    private final TransportOptions transportOptions;
    private final GrpcChannelHealthMonitor healthMonitor;

    GrpcTransport(ManagedChannel channel, JsonpMapper jsonpMapper,
                  GrpcTransportOptions grpcOptions, @Nullable TransportOptions transportOptions) {
        this.channel = channel;
        this.documentStub = channel != null ? DocumentServiceGrpc.newBlockingStub(channel) : null;
        this.jsonpMapper = jsonpMapper;
        this.grpcOptions = grpcOptions;
        this.transportOptions = transportOptions;
        this.healthMonitor = channel != null ? new GrpcChannelHealthMonitor(channel) : null;

        // Start monitoring channel health and warm up the connection
        if (this.healthMonitor != null) {
            this.healthMonitor.startMonitoring();
            this.healthMonitor.connectIfIdle();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
        RequestT request,
        Endpoint<RequestT, ResponseT, ErrorT> endpoint,
        @Nullable TransportOptions options
    ) throws IOException {

        if (!GrpcEndpointRegistry.isSupported(endpoint)) {
            throw new UnsupportedOperationException(
                "Endpoint not supported by gRPC transport: " + endpoint.requestUrl(request)
                    + ". Use HybridTransport for automatic REST fallback."
            );
        }

        // Route to the appropriate gRPC handler
        if (endpoint == BulkRequest._ENDPOINT) {
            return (ResponseT) performBulk((BulkRequest) request);
        }

        throw new UnsupportedOperationException("Endpoint registered but no handler: " + endpoint.requestUrl(request));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
        RequestT request,
        Endpoint<RequestT, ResponseT, ErrorT> endpoint,
        @Nullable TransportOptions options
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performRequest(request, endpoint, options);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public JsonpMapper jsonpMapper() {
        return jsonpMapper;
    }

    @Override
    public TransportOptions options() {
        return transportOptions;
    }

    @Override
    public void close() throws IOException {
        if (channel == null) {
            return;
        }
        if (healthMonitor != null) {
            healthMonitor.stopMonitoring();
        }
        try {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the underlying ManagedChannel for advanced use cases.
     */
    public ManagedChannel channel() {
        return channel;
    }

    /**
     * Returns the channel health monitor for checking connectivity state.
     * <p>
     * Usage:
     * <pre>{@code
     * if (transport.healthMonitor().isReady()) {
     *     // channel is connected
     * }
     *
     * // Wait for connection (useful at startup)
     * transport.healthMonitor().waitForReady(5, TimeUnit.SECONDS);
     * }</pre>
     */
    public GrpcChannelHealthMonitor healthMonitor() {
        return healthMonitor;
    }

    /**
     * Returns the gRPC transport options.
     */
    public GrpcTransportOptions grpcOptions() {
        return grpcOptions;
    }

    // ─── Internal gRPC Handlers ──────────────────────────────────────────────────

    private BulkResponse performBulk(BulkRequest request) throws TransportException {
        // Convert client request to protobuf
        org.opensearch.protobufs.BulkRequest protoRequest = BulkRequestConverter.toProto(request, jsonpMapper);

        // Execute with retry logic
        int attempt = 0;
        long backoffMs = grpcOptions.retryBackoffMs();

        while (true) {
            try {
                org.opensearch.protobufs.BulkResponse protoResponse = documentStub.bulk(protoRequest);
                return BulkResponseConverter.fromProto(protoResponse);
            } catch (StatusRuntimeException e) {
                if (attempt < grpcOptions.maxRetries()
                    && GrpcStatusConverter.isRetryable(e.getStatus().getCode())) {
                    attempt++;
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TransportException("gRPC request interrupted", ie);
                    }
                    backoffMs *= 2; // exponential backoff
                } else {
                    GrpcStatusConverter.throwConverted(e);
                    // unreachable, but compiler needs it
                    throw new TransportException("gRPC error: " + e.getMessage(), e);
                }
            }
        }
    }

    // ─── Builder ─────────────────────────────────────────────────────────────────

    /**
     * Creates a builder for GrpcTransport.
     *
     * @param host the gRPC server hostname
     * @param port the gRPC server port (default: 9400)
     */
    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    public static final class Builder {
        private final String host;
        private final int port;
        private JsonpMapper jsonpMapper;
        private GrpcTransportOptions grpcOptions = GrpcTransportOptions.defaults();
        private TransportOptions transportOptions;
        private ManagedChannel channel; // allow injecting channel for testing

        Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public Builder jsonpMapper(JsonpMapper mapper) {
            this.jsonpMapper = mapper;
            return this;
        }

        public Builder grpcOptions(GrpcTransportOptions options) {
            this.grpcOptions = options;
            return this;
        }

        public Builder transportOptions(TransportOptions options) {
            this.transportOptions = options;
            return this;
        }

        /**
         * Inject a pre-built channel (primarily for testing).
         */
        public Builder channel(ManagedChannel channel) {
            this.channel = channel;
            return this;
        }

        public GrpcTransport build() {
            if (jsonpMapper == null) {
                throw new IllegalArgumentException("jsonpMapper is required");
            }

            ManagedChannel ch = this.channel;
            if (ch == null) {
                ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext(); // TLS configured in tls-basic-auth branch

                if (grpcOptions.maxInboundMessageSize() > 0) {
                    channelBuilder.maxInboundMessageSize(grpcOptions.maxInboundMessageSize());
                }
                if (grpcOptions.keepAliveTimeMs() > 0) {
                    channelBuilder.keepAliveTime(grpcOptions.keepAliveTimeMs(), TimeUnit.MILLISECONDS);
                }
                if (grpcOptions.keepAliveTimeoutMs() > 0) {
                    channelBuilder.keepAliveTimeout(grpcOptions.keepAliveTimeoutMs(), TimeUnit.MILLISECONDS);
                }
                channelBuilder.keepAliveWithoutCalls(grpcOptions.keepAliveWithoutCalls());
                if (grpcOptions.idleTimeoutMs() > 0) {
                    channelBuilder.idleTimeout(grpcOptions.idleTimeoutMs(), TimeUnit.MILLISECONDS);
                }

                ch = channelBuilder.build();
            }

            return new GrpcTransport(ch, jsonpMapper, grpcOptions, transportOptions);
        }
    }
}
