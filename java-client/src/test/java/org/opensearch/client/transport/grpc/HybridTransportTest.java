/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.transport.Endpoint;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.TransportOptions;
import org.junit.Test;

public class HybridTransportTest {

    private final JsonpMapper mapper = new JacksonJsonpMapper();

    /**
     * A mock REST transport that records calls and returns a fixed response.
     */
    static class MockRestTransport implements OpenSearchTransport {
        int callCount = 0;
        String lastEndpointUrl = null;

        @Override
        @SuppressWarnings("unchecked")
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, @Nullable TransportOptions options
        ) throws IOException {
            callCount++;
            lastEndpointUrl = endpoint.requestUrl(request);
            // Return a minimal BulkResponse for bulk requests
            if (endpoint == BulkRequest._ENDPOINT) {
                return (ResponseT) new BulkResponse.Builder().took(1).errors(false).items(java.util.Collections.emptyList()).build();
            }
            return null;
        }

        @Override
        public <RequestT, ResponseT, ErrorT> CompletableFuture<ResponseT> performRequestAsync(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, @Nullable TransportOptions options
        ) {
            return CompletableFuture.supplyAsync(() -> {
                try { return performRequest(request, endpoint, options); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
        }

        @Override public JsonpMapper jsonpMapper() { return new JacksonJsonpMapper(); }
        @Override public TransportOptions options() { return null; }
        @Override public void close() {}
    }

    /**
     * A mock GrpcTransport that always fails — simulates gRPC unavailability.
     */
    static class FailingGrpcTransport extends GrpcTransport {
        FailingGrpcTransport(JsonpMapper mapper) {
            super(null, mapper, GrpcTransportOptions.defaults(), null, null);
        }

        @Override
        public <RequestT, ResponseT, ErrorT> ResponseT performRequest(
            RequestT request, Endpoint<RequestT, ResponseT, ErrorT> endpoint, @Nullable TransportOptions options
        ) throws IOException {
            throw new org.opensearch.client.transport.TransportException("gRPC connection unavailable: test");
        }

        @Override public void close() {}
    }

    @Test
    public void testUnsupportedEndpointRoutedToRest() throws IOException {
        MockRestTransport rest = new MockRestTransport();
        FailingGrpcTransport grpc = new FailingGrpcTransport(mapper);
        HybridTransport hybrid = new HybridTransport(grpc, rest);

        // SearchRequest is not supported by gRPC — should go straight to REST
        // We can't easily call search without the full endpoint, so we test via endpoint check
        Endpoint<Object, Object, Object> fakeSearchEndpoint = new Endpoint<Object, Object, Object>() {
            @Override public String method(Object r) { return "POST"; }
            @Override public String requestUrl(Object r) { return "/my-index/_search"; }
            @Override public boolean hasRequestBody() { return true; }
            @Override public boolean isError(int s) { return s >= 400; }
            @Override public org.opensearch.client.json.JsonpDeserializer<Object> errorDeserializer(int s) { return null; }
        };

        hybrid.performRequest("dummy", fakeSearchEndpoint, null);
        assertEquals(1, rest.callCount);
        assertEquals("/my-index/_search", rest.lastEndpointUrl);
    }

    @Test
    public void testFallbackOnGrpcFailure() throws IOException {
        MockRestTransport rest = new MockRestTransport();
        FailingGrpcTransport grpc = new FailingGrpcTransport(mapper);
        HybridTransport hybrid = new HybridTransport(grpc, rest, true);

        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.delete(d -> d.id("1").index("test")))
            .build();

        // gRPC fails → should fallback to REST
        BulkResponse response = (BulkResponse) hybrid.performRequest(request, BulkRequest._ENDPOINT, null);
        assertNotNull(response);
        assertEquals(1, rest.callCount);
    }

    @Test
    public void testNoFallbackWhenDisabled() throws IOException {
        MockRestTransport rest = new MockRestTransport();
        FailingGrpcTransport grpc = new FailingGrpcTransport(mapper);
        HybridTransport hybrid = new HybridTransport(grpc, rest, false); // fallback disabled

        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.delete(d -> d.id("1").index("test")))
            .build();

        try {
            hybrid.performRequest(request, BulkRequest._ENDPOINT, null);
            fail("Should have thrown TransportException");
        } catch (org.opensearch.client.transport.TransportException e) {
            // Expected — no fallback
            assertEquals(0, rest.callCount);
        }
    }

    @Test
    public void testCloseClosesAll() throws IOException {
        MockRestTransport rest = new MockRestTransport();
        FailingGrpcTransport grpc = new FailingGrpcTransport(mapper);
        HybridTransport hybrid = new HybridTransport(grpc, rest);

        // Should not throw
        hybrid.close();
    }

    @Test
    public void testJsonpMapperFromRest() {
        MockRestTransport rest = new MockRestTransport();
        FailingGrpcTransport grpc = new FailingGrpcTransport(mapper);
        HybridTransport hybrid = new HybridTransport(grpc, rest);

        assertNotNull(hybrid.jsonpMapper());
    }
}
