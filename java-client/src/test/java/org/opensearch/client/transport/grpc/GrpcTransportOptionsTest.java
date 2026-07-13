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
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class GrpcTransportOptionsTest {

    @Test
    public void testDefaultValues() {
        GrpcTransportOptions opts = GrpcTransportOptions.defaults();
        assertEquals(10 * 1024 * 1024, opts.maxInboundMessageSize());
        assertEquals(0, opts.keepAliveTimeMs());
        assertEquals(20_000, opts.keepAliveTimeoutMs());
        assertFalse(opts.keepAliveWithoutCalls());
        assertEquals(0, opts.idleTimeoutMs());
        assertEquals(0, opts.deadlineMs());
        assertEquals(3, opts.maxRetries());
        assertEquals(100, opts.retryBackoffMs());
    }

    @Test
    public void testCustomMaxMessageSize() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .maxInboundMessageSize(50 * 1024 * 1024) // 50MB
            .build();
        assertEquals(50 * 1024 * 1024, opts.maxInboundMessageSize());
    }

    @Test
    public void testKeepaliveConfig() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build();
        assertEquals(30_000, opts.keepAliveTimeMs());
        assertEquals(10_000, opts.keepAliveTimeoutMs());
        assertTrue(opts.keepAliveWithoutCalls());
    }

    @Test
    public void testIdleTimeout() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .idleTimeout(5, TimeUnit.MINUTES)
            .build();
        assertEquals(300_000, opts.idleTimeoutMs());
    }

    @Test
    public void testDeadline() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .deadline(60, TimeUnit.SECONDS)
            .build();
        assertEquals(60_000, opts.deadlineMs());
    }

    @Test
    public void testRetryConfig() {
        GrpcTransportOptions opts = GrpcTransportOptions.builder()
            .maxRetries(5)
            .retryBackoff(200, TimeUnit.MILLISECONDS)
            .build();
        assertEquals(5, opts.maxRetries());
        assertEquals(200, opts.retryBackoffMs());
    }
}
