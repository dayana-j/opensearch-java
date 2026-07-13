/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.json.JsonpMapper;
import org.junit.Test;

public class GrpcTransportTest {

    private final JsonpMapper mapper = new JacksonJsonpMapper();

    @Test(expected = IllegalArgumentException.class)
    public void testBuilderRequiresMapper() {
        GrpcTransport.builder("localhost", 9400).build();
    }

    @Test
    public void testBuilderCreatesTransport() {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(mapper)
            .build();
        assertNotNull(transport);
        assertNotNull(transport.jsonpMapper());
        assertNotNull(transport.channel());
        assertNotNull(transport.grpcOptions());

        // Clean up
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }

    @Test
    public void testUnsupportedEndpointThrows() throws Exception {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(mapper)
            .build();

        try {
            // Create a fake endpoint that's not in the registry
            org.opensearch.client.transport.Endpoint<Object, Object, Object> fakeEndpoint =
                new org.opensearch.client.transport.Endpoint<Object, Object, Object>() {
                    @Override public String method(Object r) { return "GET"; }
                    @Override public String requestUrl(Object r) { return "/_cluster/health"; }
                    @Override public boolean hasRequestBody() { return false; }
                    @Override public boolean isError(int s) { return s >= 400; }
                    @Override public org.opensearch.client.json.JsonpDeserializer<Object> errorDeserializer(int s) { return null; }
                };

            transport.performRequest("dummy", fakeEndpoint, null);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        } finally {
            transport.close();
        }
    }

    @Test
    public void testGrpcOptionsPassedThrough() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .maxInboundMessageSize(20 * 1024 * 1024)
            .maxRetries(5)
            .build();

        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(mapper)
            .grpcOptions(opts)
            .build();

        assertEquals(20 * 1024 * 1024, transport.grpcOptions().maxInboundMessageSize());
        assertEquals(5, transport.grpcOptions().maxRetries());

        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
