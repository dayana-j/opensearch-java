/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * gRPC {@link ClientInterceptor} that signs every outgoing request with AWS SigV4.
 * <p>
 * For each gRPC call, this interceptor:
 * <ol>
 *   <li>Constructs a synthetic HTTP request using the gRPC method path as the URL
 *       (e.g., {@code https://host/opensearch.DocumentService/Bulk})</li>
 *   <li>Signs it using {@link AwsV4HttpSigner} with the configured credentials and region</li>
 *   <li>Extracts the signed headers (Authorization, X-Amz-Date, X-Amz-Security-Token,
 *       X-Amz-Content-SHA256) and attaches them as gRPC metadata</li>
 * </ol>
 * <p>
 * The signing URL uses the gRPC method path because that's the HTTP/2 path the server sees:
 * <ul>
 *   <li>Bulk: {@code POST /opensearch.DocumentService/Bulk}</li>
 *   <li>Search: {@code POST /opensearch.SearchService/Search}</li>
 * </ul>
 * <p>
 * Credentials are resolved on every call (not cached) to handle temporary credential rotation.
 * <p>
 * Usage:
 * <pre>{@code
 * GrpcSigV4Config sigv4Config = GrpcSigV4Config.builder()
 *     .region(Region.US_EAST_1)
 *     .service("es")
 *     .credentialsProvider(DefaultCredentialsProvider.create())
 *     .build();
 *
 * GrpcSigV4Interceptor interceptor = new GrpcSigV4Interceptor(sigv4Config, "my-domain.us-east-1.es.amazonaws.com");
 * }</pre>
 *
 * @see <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html">AWS SigV4 Signing</a>
 */
public class GrpcSigV4Interceptor implements ClientInterceptor {

    private static final Logger logger = Logger.getLogger(GrpcSigV4Interceptor.class.getName());

    private final GrpcSigV4Config config;
    private final String host;

    /**
     * Creates a SigV4 interceptor.
     *
     * @param config the SigV4 configuration (region, service, credentials)
     * @param host   the OpenSearch endpoint hostname (used in the signing URL and Host header)
     */
    public GrpcSigV4Interceptor(GrpcSigV4Config config, String host) {
        if (config == null) {
            throw new IllegalArgumentException("SigV4 config cannot be null");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host cannot be null or empty");
        }
        this.config = config;
        this.host = host;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next
    ) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                try {
                    // Sign using the gRPC method path
                    Map<String, List<String>> signedHeaders = signRequest(
                        method.getFullMethodName(),
                        new byte[0] // body signing uses empty for unary pre-send
                    );

                    // Attach signed headers as gRPC metadata
                    for (Map.Entry<String, List<String>> entry : signedHeaders.entrySet()) {
                        String key = entry.getKey().toLowerCase();
                        // Only forward AWS signing headers
                        if (isSigningHeader(key)) {
                            Metadata.Key<String> metadataKey =
                                Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
                            for (String value : entry.getValue()) {
                                headers.put(metadataKey, value);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to sign gRPC request: " + e.getMessage(), e);
                    // Continue without signing — server will reject with UNAUTHENTICATED
                }

                super.start(responseListener, headers);
            }

            @Override
            public void sendMessage(ReqT message) {
                // For more accurate body signing, we could re-sign here with the actual
                // serialized message bytes. For now, we sign with empty body in start()
                // which works because OpenSearch's gRPC SigV4 validation uses unsigned-payload
                // for the content hash when the body isn't available at signing time.
                super.sendMessage(message);
            }
        };
    }

    /**
     * Signs a synthetic HTTP request representing the gRPC call.
     *
     * @param grpcMethodPath the full gRPC method name (e.g., "opensearch.DocumentService/Bulk")
     * @param body          the request body bytes (may be empty for pre-send signing)
     * @return map of signed header names to their values
     */
    Map<String, List<String>> signRequest(String grpcMethodPath, byte[] body) {
        // gRPC always uses POST over HTTP/2
        // The URL path is the gRPC service method path with leading /
        String path = "/" + grpcMethodPath;
        String url = "https://" + host + path;

        // Build the synthetic HTTP request for signing
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.POST)
            .uri(URI.create(url))
            .putHeader("host", host);

        // Body content for signing
        ContentStreamProvider bodyProvider = body != null && body.length > 0
            ? ContentStreamProvider.fromByteArrayUnsafe(body)
            : ContentStreamProvider.fromByteArrayUnsafe(new byte[0]);

        // Sign the request
        SignedRequest signedRequest = AwsV4HttpSigner.create()
            .sign(b -> b
                .identity(config.credentialsProvider().resolveCredentials())
                .request(requestBuilder.build())
                .payload(bodyProvider)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, config.service())
                .putProperty(AwsV4HttpSigner.REGION_NAME, config.region().id())
            );

        return signedRequest.request().headers();
    }

    /**
     * Returns true if this is an AWS signing header that should be forwarded as gRPC metadata.
     */
    private static boolean isSigningHeader(String headerName) {
        return headerName.equals("authorization")
            || headerName.equals("x-amz-date")
            || headerName.equals("x-amz-security-token")
            || headerName.equals("x-amz-content-sha256")
            || headerName.equals("host");
    }

    /**
     * Returns the configured host (for testing).
     */
    String getHost() {
        return host;
    }

    /**
     * Returns the SigV4 config (for testing).
     */
    GrpcSigV4Config getConfig() {
        return config;
    }
}
