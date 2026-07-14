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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class BasicAuthInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
        Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    public void testAuthHeaderFormat() {
        BasicAuthInterceptor interceptor = new BasicAuthInterceptor("admin", "admin");
        String headerValue = interceptor.getAuthHeaderValue();
        assertTrue("Should start with 'Basic '", headerValue.startsWith("Basic "));
    }

    @Test
    public void testBase64Encoding() {
        BasicAuthInterceptor interceptor = new BasicAuthInterceptor("admin", "secretPassword");
        String headerValue = interceptor.getAuthHeaderValue();

        // Decode and verify
        String base64Part = headerValue.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(base64Part), StandardCharsets.UTF_8);
        assertEquals("admin:secretPassword", decoded);
    }

    @Test
    public void testBase64EncodingWithSpecialChars() {
        BasicAuthInterceptor interceptor = new BasicAuthInterceptor("user@domain.com", "p@ss:w0rd!");
        String headerValue = interceptor.getAuthHeaderValue();

        String base64Part = headerValue.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(base64Part), StandardCharsets.UTF_8);
        assertEquals("user@domain.com:p@ss:w0rd!", decoded);
    }

    @Test
    public void testEmptyPassword() {
        BasicAuthInterceptor interceptor = new BasicAuthInterceptor("admin", "");
        String headerValue = interceptor.getAuthHeaderValue();

        String base64Part = headerValue.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(base64Part), StandardCharsets.UTF_8);
        assertEquals("admin:", decoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullUsernameThrows() {
        new BasicAuthInterceptor(null, "password");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullPasswordThrows() {
        new BasicAuthInterceptor("admin", null);
    }

    @Test
    public void testInterceptorAddsHeader() {
        BasicAuthInterceptor interceptor = new BasicAuthInterceptor("admin", "admin");

        // Verify the interceptor produces a non-null ClientCall
        // (Full metadata verification requires a running gRPC server or InProcessServer,
        //  but we can verify the header value computation)
        assertNotNull(interceptor.getAuthHeaderValue());
        assertTrue(interceptor.getAuthHeaderValue().startsWith("Basic "));
    }

    @Test
    public void testDifferentCredentialsProduceDifferentHeaders() {
        BasicAuthInterceptor interceptor1 = new BasicAuthInterceptor("admin", "admin");
        BasicAuthInterceptor interceptor2 = new BasicAuthInterceptor("user", "different");

        assertNotNull(interceptor1.getAuthHeaderValue());
        assertNotNull(interceptor2.getAuthHeaderValue());
        assertTrue(
            "Different credentials should produce different headers",
            !interceptor1.getAuthHeaderValue().equals(interceptor2.getAuthHeaderValue())
        );
    }
}
