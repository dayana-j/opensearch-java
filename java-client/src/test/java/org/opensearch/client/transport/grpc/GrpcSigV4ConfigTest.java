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

import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

public class GrpcSigV4ConfigTest {

    @Test
    public void testBasicConfig() {
        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .build();

        assertEquals(Region.US_EAST_1, config.region());
        assertEquals("es", config.service()); // default
        assertNotNull(config.credentialsProvider()); // DefaultCredentialsProvider
    }

    @Test
    public void testCustomService() {
        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.US_WEST_2)
            .service("aoss")
            .build();

        assertEquals("aoss", config.service());
    }

    @Test
    public void testCustomCredentialsProvider() {
        AwsCredentialsProvider provider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKID", "SECRET")
        );

        GrpcSigV4Config config = GrpcSigV4Config.builder()
            .region(Region.EU_WEST_1)
            .credentialsProvider(provider)
            .build();

        assertEquals(provider, config.credentialsProvider());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegionRequired() {
        GrpcSigV4Config.builder()
            .service("es")
            .build(); // no region → exception
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServiceCannotBeEmpty() {
        GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .service("")
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServiceCannotBeNull() {
        GrpcSigV4Config.builder()
            .region(Region.US_EAST_1)
            .service(null)
            .build();
    }
}
