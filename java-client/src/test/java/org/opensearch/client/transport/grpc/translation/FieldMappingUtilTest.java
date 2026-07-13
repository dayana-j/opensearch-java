/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc.translation;

import static org.junit.Assert.assertEquals;

import io.grpc.Status;
import org.junit.Test;

public class FieldMappingUtilTest {

    @Test
    public void testMapRefreshTrue() {
        assertEquals(
            org.opensearch.protobufs.Refresh.REFRESH_TRUE,
            FieldMappingUtil.mapRefresh(org.opensearch.client.opensearch._types.Refresh.True)
        );
    }

    @Test
    public void testMapRefreshFalse() {
        assertEquals(
            org.opensearch.protobufs.Refresh.REFRESH_FALSE,
            FieldMappingUtil.mapRefresh(org.opensearch.client.opensearch._types.Refresh.False)
        );
    }

    @Test
    public void testMapRefreshWaitFor() {
        assertEquals(
            org.opensearch.protobufs.Refresh.REFRESH_WAIT_FOR,
            FieldMappingUtil.mapRefresh(org.opensearch.client.opensearch._types.Refresh.WaitFor)
        );
    }

    @Test
    public void testMapRefreshNull() {
        assertEquals(
            org.opensearch.protobufs.Refresh.REFRESH_UNSPECIFIED,
            FieldMappingUtil.mapRefresh(null)
        );
    }

    @Test
    public void testMapVersionTypeExternal() {
        assertEquals(
            org.opensearch.protobufs.VersionType.VERSION_TYPE_EXTERNAL,
            FieldMappingUtil.mapVersionType(org.opensearch.client.opensearch._types.VersionType.External)
        );
    }

    @Test
    public void testMapVersionTypeExternalGte() {
        assertEquals(
            org.opensearch.protobufs.VersionType.VERSION_TYPE_EXTERNAL_GTE,
            FieldMappingUtil.mapVersionType(org.opensearch.client.opensearch._types.VersionType.ExternalGte)
        );
    }

    @Test
    public void testMapVersionTypeInternal() {
        // Internal maps to UNSPECIFIED since it's the default
        assertEquals(
            org.opensearch.protobufs.VersionType.VERSION_TYPE_UNSPECIFIED,
            FieldMappingUtil.mapVersionType(org.opensearch.client.opensearch._types.VersionType.Internal)
        );
    }

    @Test
    public void testMapVersionTypeNull() {
        assertEquals(
            org.opensearch.protobufs.VersionType.VERSION_TYPE_UNSPECIFIED,
            FieldMappingUtil.mapVersionType(null)
        );
    }

    @Test
    public void testMapOpTypeIndex() {
        assertEquals(
            org.opensearch.protobufs.OpType.OP_TYPE_INDEX,
            FieldMappingUtil.mapOpType(org.opensearch.client.opensearch._types.OpType.Index)
        );
    }

    @Test
    public void testMapOpTypeCreate() {
        assertEquals(
            org.opensearch.protobufs.OpType.OP_TYPE_CREATE,
            FieldMappingUtil.mapOpType(org.opensearch.client.opensearch._types.OpType.Create)
        );
    }

    @Test
    public void testMapOpTypeNull() {
        assertEquals(
            org.opensearch.protobufs.OpType.OP_TYPE_UNSPECIFIED,
            FieldMappingUtil.mapOpType(null)
        );
    }

    @Test
    public void testGrpcStatusToHttpStatusOkCreated() {
        assertEquals(201, FieldMappingUtil.grpcStatusToHttpStatus(0, "created"));
    }

    @Test
    public void testGrpcStatusToHttpStatusOkUpdated() {
        assertEquals(200, FieldMappingUtil.grpcStatusToHttpStatus(0, "updated"));
    }

    @Test
    public void testGrpcStatusToHttpStatusOkDeleted() {
        assertEquals(200, FieldMappingUtil.grpcStatusToHttpStatus(0, "deleted"));
    }

    @Test
    public void testGrpcStatusToHttpStatusOkNull() {
        assertEquals(200, FieldMappingUtil.grpcStatusToHttpStatus(0, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusInvalidArgument() {
        assertEquals(400, FieldMappingUtil.grpcStatusToHttpStatus(3, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusNotFound() {
        assertEquals(404, FieldMappingUtil.grpcStatusToHttpStatus(5, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusAlreadyExists() {
        assertEquals(409, FieldMappingUtil.grpcStatusToHttpStatus(6, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusPermissionDenied() {
        assertEquals(403, FieldMappingUtil.grpcStatusToHttpStatus(7, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusResourceExhausted() {
        assertEquals(429, FieldMappingUtil.grpcStatusToHttpStatus(8, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusUnavailable() {
        assertEquals(503, FieldMappingUtil.grpcStatusToHttpStatus(14, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusUnauthenticated() {
        assertEquals(401, FieldMappingUtil.grpcStatusToHttpStatus(16, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusDeadlineExceeded() {
        assertEquals(408, FieldMappingUtil.grpcStatusToHttpStatus(4, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusUnimplemented() {
        assertEquals(501, FieldMappingUtil.grpcStatusToHttpStatus(12, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusInternal() {
        assertEquals(500, FieldMappingUtil.grpcStatusToHttpStatus(13, null));
    }

    @Test
    public void testGrpcStatusToHttpStatusUnknownCode() {
        assertEquals(500, FieldMappingUtil.grpcStatusToHttpStatus(99, null));
    }
}
