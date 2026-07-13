/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.opensearch.integTest.grpc;

import java.io.IOException;
import java.util.Optional;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.integTest.OpenSearchTransportSupport;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.grpc.GrpcTransport;
import org.opensearch.client.transport.grpc.GrpcTransportOptions;
import org.opensearch.client.transport.grpc.HybridTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.common.settings.Settings;

/**
 * Transport support for gRPC integration tests.
 * <p>
 * Creates a {@link HybridTransport} that routes Bulk over gRPC (port 9400)
 * and everything else over REST (port 9200).
 * <p>
 * Assumes the test cluster has gRPC enabled with:
 * <pre>
 * aux.transport.types: [transport-grpc]
 * aux.transport.transport-grpc.port: '9400'
 * </pre>
 */
interface GrpcTransportSupport extends OpenSearchTransportSupport {

    /**
     * Default gRPC port for the test cluster.
     */
    int GRPC_PORT = 9400;

    @Override
    default OpenSearchTransport buildTransport(Settings settings, HttpHost[] hosts) throws IOException {
        // Build the REST transport for fallback
        final ApacheHttpClient5TransportBuilder restBuilder = ApacheHttpClient5TransportBuilder.builder(hosts);
        final OpenSearchTransport restTransport = restBuilder.build();

        // Build the gRPC transport pointing to the same host but on port 9400
        String grpcHost = hosts[0].getHostName();
        int grpcPort = Integer.parseInt(
            Optional.ofNullable(System.getProperty("tests.grpc.port")).orElse(String.valueOf(GRPC_PORT))
        );

        GrpcTransportOptions grpcOptions = GrpcTransportOptions.builder()
            .maxRetries(1) // Don't retry too much in tests
            .build();

        GrpcTransport grpcTransport = GrpcTransport.builder(grpcHost, grpcPort)
            .jsonpMapper(new JacksonJsonpMapper())
            .grpcOptions(grpcOptions)
            .build();

        // Combine into HybridTransport: bulk → gRPC, everything else → REST
        return new HybridTransport(grpcTransport, restTransport);
    }
}
