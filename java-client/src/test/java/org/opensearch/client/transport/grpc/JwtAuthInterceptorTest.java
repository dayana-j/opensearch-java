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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;

public class JwtAuthInterceptorTest {

    @Test
    public void testTokenSupplierStored() {
        Supplier<String> supplier = () -> "my-token";
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(supplier);
        assertEquals(supplier, interceptor.getTokenSupplier());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullSupplierThrows() {
        new JwtAuthInterceptor(null);
    }

    @Test
    public void testSupplierCalledOnGet() {
        AtomicInteger callCount = new AtomicInteger(0);
        Supplier<String> supplier = () -> {
            callCount.incrementAndGet();
            return "token-" + callCount.get();
        };

        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(supplier);

        // Verify supplier works
        String token1 = interceptor.getTokenSupplier().get();
        String token2 = interceptor.getTokenSupplier().get();

        assertEquals("token-1", token1);
        assertEquals("token-2", token2);
        assertEquals(2, callCount.get());
    }

    @Test
    public void testStaticTokenSupplier() {
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(() -> "static-jwt-token");
        String token = interceptor.getTokenSupplier().get();
        assertEquals("static-jwt-token", token);
    }

    @Test
    public void testTokenRefreshSupplier() {
        // Simulate a token that changes (refresh scenario)
        AtomicInteger version = new AtomicInteger(1);
        Supplier<String> refreshingSupplier = () -> "token-v" + version.getAndIncrement();

        JwtAuthInterceptor interceptor = new JwtAuthInterceptor(refreshingSupplier);

        assertEquals("token-v1", interceptor.getTokenSupplier().get());
        assertEquals("token-v2", interceptor.getTokenSupplier().get());
        assertEquals("token-v3", interceptor.getTokenSupplier().get());
    }

    @Test
    public void testBuilderWithJwtAuth() {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .jwtAuth(() -> "my-jwt-token")
            .build();

        assertNotNull(transport);
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }

    @Test
    public void testBuilderWithTlsAndJwt() {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .tlsInsecure()
            .jwtAuth(() -> "secure-jwt-token")
            .build();

        assertNotNull(transport);
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }
}
