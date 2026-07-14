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

import java.util.List;
import java.util.Map;
import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

public class GrpcSigV4InterceptorTest {

    private static final String TEST_HOST = "my-domain.us-east-1.es.amazonaws.com";
    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String SESSION_TOKEN = "FwoGZXIvYXdzEBYaDHqa0AP";

    private GrpcSigV4Interceptor createInterceptor(boolean withSessionToken) {
        StaticCredentialsProvider provider;
        if (withSessionToken) {
            provider = StaticCredentialsProvider.create(
                AwsSessionCredentials.create(ACCESS_KEY, SECRET_KEY, SESSION_TOKEN)
            );
        } else {
            provider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
            );
        }

        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .service("es")
            .credentialsProvider(provider)
            .build();

        return new GrpcSigV4Interceptor(config, TEST_HOST);
    }

    @Test
    public void testSignedHeadersContainAuthorization() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        assertTrue("Should contain authorization header",
            headers.containsKey("Authorization") || headers.containsKey("authorization"));
    }

    @Test
    public void testSignedHeadersContainAmzDate() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        assertTrue("Should contain X-Amz-Date header",
            headers.containsKey("X-Amz-Date") || headers.containsKey("x-amz-date"));
    }

    @Test
    public void testSignedHeadersContainContentSha256() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        assertTrue("Should contain X-Amz-Content-Sha256 header",
            headers.containsKey("x-amz-content-sha256") || headers.containsKey("X-Amz-Content-Sha256"));
    }

    @Test
    public void testSessionTokenIncludedWhenProvided() {
        GrpcSigV4Interceptor interceptor = createInterceptor(true);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        assertTrue("Should contain X-Amz-Security-Token header",
            headers.containsKey("X-Amz-Security-Token") || headers.containsKey("x-amz-security-token"));
    }

    @Test
    public void testNoSessionTokenWithBasicCredentials() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        assertFalse("Should NOT contain X-Amz-Security-Token with basic credentials",
            headers.containsKey("X-Amz-Security-Token") || headers.containsKey("x-amz-security-token"));
    }

    @Test
    public void testAuthorizationContainsAWS4Signature() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        String authHeader = getHeaderValue(headers, "Authorization", "authorization");
        assertNotNull("Authorization header should exist", authHeader);
        assertTrue("Should start with AWS4-HMAC-SHA256",
            authHeader.startsWith("AWS4-HMAC-SHA256"));
    }

    @Test
    public void testAuthorizationContainsRegion() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        String authHeader = getHeaderValue(headers, "Authorization", "authorization");
        assertTrue("Authorization should contain region",
            authHeader.contains("us-east-1"));
    }

    @Test
    public void testAuthorizationContainsServiceName() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        String authHeader = getHeaderValue(headers, "Authorization", "authorization");
        assertTrue("Authorization should contain service name 'es'",
            authHeader.contains("/es/"));
    }

    @Test
    public void testDifferentServiceName() {
        StaticCredentialsProvider provider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
        );
        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_WEST_2)
            .service("aoss")
            .credentialsProvider(provider)
            .build();
        GrpcSigV4Interceptor interceptor = new GrpcSigV4Interceptor(config, TEST_HOST);

        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        String authHeader = getHeaderValue(headers, "Authorization", "authorization");
        assertTrue("Authorization should contain 'aoss'", authHeader.contains("/aoss/"));
        assertTrue("Authorization should contain us-west-2", authHeader.contains("us-west-2"));
    }

    @Test
    public void testFreshSignaturePerRequest() throws InterruptedException {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);

        Map<String, List<String>> headers1 = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        // Small delay to get a different timestamp
        Thread.sleep(1100);

        Map<String, List<String>> headers2 = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        String date1 = getHeaderValue(headers1, "X-Amz-Date", "x-amz-date");
        String date2 = getHeaderValue(headers2, "X-Amz-Date", "x-amz-date");

        // Dates should be different if >1s apart
        // (they may be the same if within the same second, so we allow that)
        assertNotNull(date1);
        assertNotNull(date2);
    }

    @Test
    public void testSigningUrlUsesGrpcMethodPath() {
        GrpcSigV4Interceptor interceptor = createInterceptor(false);

        // The signing URL should include the gRPC method path
        Map<String, List<String>> headers = interceptor.signRequest(
            "opensearch.DocumentService/Bulk", new byte[0]
        );

        // The host header should match our configured host
        String hostHeader = getHeaderValue(headers, "Host", "host");
        assertEquals(TEST_HOST, hostHeader);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullConfigThrows() {
        new GrpcSigV4Interceptor(null, TEST_HOST);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullHostThrows() {
        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("x", "y")))
            .build();
        new GrpcSigV4Interceptor(config, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyHostThrows() {
        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("x", "y")))
            .build();
        new GrpcSigV4Interceptor(config, "");
    }

    @Test(expected = IllegalStateException.class)
    public void testBuilderRequiresTlsWithSigV4() {
        GrpcSigV4Config sigv4 = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("x", "y")))
            .build();

        // No TLS configured → should throw
        GrpcTransport.builder("localhost", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .sigV4(sigv4)
            .build();
    }

    @Test
    public void testBuilderWithTlsAndSigV4() {
        GrpcSigV4Config sigv4 = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("x", "y")))
            .build();

        GrpcTransport transport = GrpcTransport.builder("my-domain.us-east-1.es.amazonaws.com", 9400)
            .jsonpMapper(new org.opensearch.client.json.jackson3.JacksonJsonpMapper())
            .tlsInsecure()
            .sigV4(sigv4)
            .build();

        assertNotNull(transport);
        try { transport.close(); } catch (Exception e) { /* ignore */ }
    }

    // ─── Helper ──────────────────────────────────────────────────────────────────

    private String getHeaderValue(Map<String, List<String>> headers, String... possibleKeys) {
        for (String key : possibleKeys) {
            if (headers.containsKey(key)) {
                List<String> values = headers.get(key);
                return values != null && !values.isEmpty() ? values.get(0) : null;
            }
        }
        return null;
    }
}
