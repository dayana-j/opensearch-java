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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GrpcTlsConfigTest {

    @Test
    public void testDefaultBuilder() {
        GrpcTlsConfig config = GrpcTlsConfig.builder().build();
        assertTrue(config.isEnabled());
        assertFalse(config.isInsecure());
        assertNull(config.trustCertificatePath());
        assertNull(config.clientCertificatePath());
        assertNull(config.clientKeyPath());
        assertNull(config.trustStorePath());
        assertEquals("JKS", config.trustStoreType());
    }

    @Test
    public void testInsecureConfig() {
        GrpcTlsConfig config = GrpcTlsConfig.insecure();
        assertTrue(config.isEnabled());
        assertTrue(config.isInsecure());
    }

    @Test
    public void testInsecureViaBuilder() {
        GrpcTlsConfig config = GrpcTlsConfig.builder().insecure(true).build();
        assertTrue(config.isInsecure());
    }

    @Test
    public void testTrustCertificatePath() {
        GrpcTlsConfig config = GrpcTlsConfig.builder()
            .trustCertificatePath("/path/to/ca.pem")
            .build();
        assertEquals("/path/to/ca.pem", config.trustCertificatePath());
        assertFalse(config.isInsecure());
    }

    @Test
    public void testTrustStorePath() {
        GrpcTlsConfig config = GrpcTlsConfig.builder()
            .trustStorePath("/path/to/truststore.jks")
            .trustStorePassword("changeit")
            .trustStoreType("PKCS12")
            .build();
        assertEquals("/path/to/truststore.jks", config.trustStorePath());
        assertEquals("changeit", config.trustStorePassword());
        assertEquals("PKCS12", config.trustStoreType());
    }

    @Test
    public void testClientCertForMtls() {
        GrpcTlsConfig config = GrpcTlsConfig.builder()
            .trustCertificatePath("/path/to/ca.pem")
            .clientCertificatePath("/path/to/client.pem")
            .clientKeyPath("/path/to/client-key.pem")
            .clientKeyPassword("keypass")
            .build();
        assertEquals("/path/to/client.pem", config.clientCertificatePath());
        assertEquals("/path/to/client-key.pem", config.clientKeyPath());
        assertEquals("keypass", config.clientKeyPassword());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testClientCertWithoutKeyThrows() {
        GrpcTlsConfig.builder()
            .clientCertificatePath("/path/to/client.pem")
            // Missing clientKeyPath
            .build();
    }

    @Test
    public void testDisabledTls() {
        GrpcTlsConfig config = GrpcTlsConfig.builder().enabled(false).build();
        assertFalse(config.isEnabled());
    }
}
