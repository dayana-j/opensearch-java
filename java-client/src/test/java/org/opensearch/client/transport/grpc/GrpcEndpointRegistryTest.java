/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.TimeUnit;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.transport.Endpoint;
import org.junit.Test;

public class GrpcEndpointRegistryTest {

    @Test
    public void testBulkEndpointIsSupported() {
        assertTrue(GrpcEndpointRegistry.isSupported(BulkRequest._ENDPOINT));
    }

    @Test
    public void testUnsupportedEndpointReturnsFalse() {
        // Create a mock endpoint that's not in the registry
        Endpoint<Object, Object, Object> fakeEndpoint = new Endpoint<Object, Object, Object>() {
            @Override public String method(Object request) { return "GET"; }
            @Override public String requestUrl(Object request) { return "/_search"; }
            @Override public boolean hasRequestBody() { return true; }
            @Override public boolean isError(int statusCode) { return statusCode >= 400; }
            @Override public org.opensearch.client.json.JsonpDeserializer<Object> errorDeserializer(int statusCode) { return null; }
        };
        assertFalse(GrpcEndpointRegistry.isSupported(fakeEndpoint));
    }

    @Test
    public void testSupportedEndpointsNotEmpty() {
        assertFalse(GrpcEndpointRegistry.supportedEndpoints().isEmpty());
    }

    @Test
    public void testSupportedEndpointsContainsBulk() {
        assertTrue(GrpcEndpointRegistry.supportedEndpoints().contains(BulkRequest._ENDPOINT));
    }
}
