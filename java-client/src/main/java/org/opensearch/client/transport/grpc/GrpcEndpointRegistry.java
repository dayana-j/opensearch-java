/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.transport.Endpoint;

/**
 * Registry of which API endpoints support gRPC transport.
 * <p>
 * Only endpoints registered here will be routed through gRPC.
 * All others will be handled by the REST transport (via HybridTransport).
 * <p>
 * Currently supported endpoints:
 * <ul>
 *   <li>{@code bulk} — BulkRequest (GA since OpenSearch 3.2)</li>
 * </ul>
 * <p>
 * Future additions: Search, k-NN search
 */
public final class GrpcEndpointRegistry {

    private static final Set<Endpoint<?, ?, ?>> SUPPORTED_ENDPOINTS;

    static {
        Set<Endpoint<?, ?, ?>> endpoints = new HashSet<>();
        endpoints.add(BulkRequest._ENDPOINT);
        // Future: SearchRequest._ENDPOINT, KnnSearchRequest._ENDPOINT
        SUPPORTED_ENDPOINTS = Collections.unmodifiableSet(endpoints);
    }

    private GrpcEndpointRegistry() {
        // utility class
    }

    /**
     * Returns true if the given endpoint is supported by gRPC transport.
     *
     * @param endpoint the endpoint to check
     * @return true if gRPC can handle this endpoint
     */
    public static boolean isSupported(Endpoint<?, ?, ?> endpoint) {
        return SUPPORTED_ENDPOINTS.contains(endpoint);
    }

    /**
     * Returns the set of all supported gRPC endpoints.
     */
    public static Set<Endpoint<?, ?, ?>> supportedEndpoints() {
        return SUPPORTED_ENDPOINTS;
    }
}
