/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;

public class GrpcChannelFactoryTest {

    private ManagedChannel channel;

    @After
    public void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Test
    public void testCreatePlaintextChannel() {
        channel = GrpcChannelFactory.createPlaintextChannel(
            "localhost", 9400,
            GrpcTransportOptions.defaults(),
            Collections.emptyList()
        );
        assertNotNull(channel);
    }

    @Test
    public void testCreatePlaintextChannelWithInterceptors() {
        BasicAuthInterceptor authInterceptor = new BasicAuthInterceptor("admin", "admin");
        channel = GrpcChannelFactory.createPlaintextChannel(
            "localhost", 9400,
            GrpcTransportOptions.defaults(),
            Collections.singletonList(authInterceptor)
        );
        assertNotNull(channel);
    }

    @Test
    public void testCreateTlsChannelInsecure() throws IOException {
        GrpcTlsConfig tlsConfig = GrpcTlsConfig.insecure();
        channel = GrpcChannelFactory.createTlsChannel(
            "localhost", 9400,
            tlsConfig,
            GrpcTransportOptions.defaults(),
            Collections.emptyList()
        );
        assertNotNull(channel);
    }

    @Test
    public void testCreateChannelWithNullTlsCreatesPlaintext() throws IOException {
        channel = GrpcChannelFactory.createChannel(
            "localhost", 9400,
            null, // no TLS
            GrpcTransportOptions.defaults(),
            Collections.emptyList()
        );
        assertNotNull(channel);
    }

    @Test
    public void testCreateChannelWithDisabledTlsCreatesPlaintext() throws IOException {
        GrpcTlsConfig disabledTls = GrpcTlsConfig.builder().enabled(false).build();
        channel = GrpcChannelFactory.createChannel(
            "localhost", 9400,
            disabledTls,
            GrpcTransportOptions.defaults(),
            Collections.emptyList()
        );
        assertNotNull(channel);
    }

    @Test
    public void testCreateChannelWithTlsConfig() throws IOException {
        GrpcTlsConfig tlsConfig = GrpcTlsConfig.insecure();
        channel = GrpcChannelFactory.createChannel(
            "localhost", 9400,
            tlsConfig,
            GrpcTransportOptions.defaults(),
            Collections.emptyList()
        );
        assertNotNull(channel);
    }

    @Test
    public void testBuilderWithTlsAndBasicAuth() {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .tlsInsecure()
            .basicAuth("admin", "admin")
            .build();
        assertNotNull(transport);
        assertNotNull(transport.channel());
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }

    @Test
    public void testBuilderWithPlaintextAndBasicAuth() {
        GrpcTransport transport = GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .basicAuth("admin", "admin")
            .build();
        assertNotNull(transport);
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }
}
