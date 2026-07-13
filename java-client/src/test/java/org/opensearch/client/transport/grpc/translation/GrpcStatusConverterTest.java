/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.transport.TransportException;
import org.junit.Test;

public class GrpcStatusConverterTest {

    @Test
    public void testUnavailableToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.UNAVAILABLE.withDescription("Connection refused")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("unavailable"));
    }

    @Test
    public void testDeadlineExceededToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.DEADLINE_EXCEEDED.withDescription("Timeout")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("timed out"));
    }

    @Test
    public void testUnauthenticatedToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.UNAUTHENTICATED.withDescription("Invalid credentials")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("401"));
    }

    @Test
    public void testPermissionDeniedToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.PERMISSION_DENIED.withDescription("Access denied")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("403"));
    }

    @Test
    public void testNotFoundToOpenSearchException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("Index not found")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof OpenSearchException);
        OpenSearchException ose = (OpenSearchException) result;
        assertEquals(404, ose.status());
        assertEquals("not_found", ose.error().type());
    }

    @Test
    public void testInvalidArgumentToOpenSearchException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.INVALID_ARGUMENT.withDescription("Bad request body")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof OpenSearchException);
        OpenSearchException ose = (OpenSearchException) result;
        assertEquals(400, ose.status());
    }

    @Test
    public void testAlreadyExistsToOpenSearchException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.ALREADY_EXISTS.withDescription("Document exists")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof OpenSearchException);
        OpenSearchException ose = (OpenSearchException) result;
        assertEquals(409, ose.status());
    }

    @Test
    public void testUnimplementedToUnsupportedOperation() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.UNIMPLEMENTED.withDescription("Method not supported")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof UnsupportedOperationException);
    }

    @Test
    public void testOkReturnsNull() {
        StatusRuntimeException e = new StatusRuntimeException(Status.OK);
        Exception result = GrpcStatusConverter.convert(e);
        assertNull(result);
    }

    @Test
    public void testResourceExhaustedToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.RESOURCE_EXHAUSTED.withDescription("Too many requests")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("429"));
    }

    @Test
    public void testInternalToTransportException() {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.INTERNAL.withDescription("Server error")
        );
        Exception result = GrpcStatusConverter.convert(e);
        assertTrue(result instanceof TransportException);
        assertTrue(result.getMessage().contains("500"));
    }

    // ─── toHttpStatus tests ──────────────────────────────────────────────────────

    @Test
    public void testToHttpStatusOk() {
        assertEquals(200, GrpcStatusConverter.toHttpStatus(Status.Code.OK));
    }

    @Test
    public void testToHttpStatusUnavailable() {
        assertEquals(503, GrpcStatusConverter.toHttpStatus(Status.Code.UNAVAILABLE));
    }

    @Test
    public void testToHttpStatusUnauthenticated() {
        assertEquals(401, GrpcStatusConverter.toHttpStatus(Status.Code.UNAUTHENTICATED));
    }

    @Test
    public void testToHttpStatusNotFound() {
        assertEquals(404, GrpcStatusConverter.toHttpStatus(Status.Code.NOT_FOUND));
    }

    // ─── isRetryable tests ───────────────────────────────────────────────────────

    @Test
    public void testIsRetryableUnavailable() {
        assertTrue(GrpcStatusConverter.isRetryable(Status.Code.UNAVAILABLE));
    }

    @Test
    public void testIsRetryableDeadlineExceeded() {
        assertTrue(GrpcStatusConverter.isRetryable(Status.Code.DEADLINE_EXCEEDED));
    }

    @Test
    public void testIsRetryableResourceExhausted() {
        assertTrue(GrpcStatusConverter.isRetryable(Status.Code.RESOURCE_EXHAUSTED));
    }

    @Test
    public void testIsRetryableAborted() {
        assertTrue(GrpcStatusConverter.isRetryable(Status.Code.ABORTED));
    }

    @Test
    public void testIsNotRetryableNotFound() {
        assertFalse(GrpcStatusConverter.isRetryable(Status.Code.NOT_FOUND));
    }

    @Test
    public void testIsNotRetryableInvalidArgument() {
        assertFalse(GrpcStatusConverter.isRetryable(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    public void testIsNotRetryableUnauthenticated() {
        assertFalse(GrpcStatusConverter.isRetryable(Status.Code.UNAUTHENTICATED));
    }

    // ─── throwConverted tests ────────────────────────────────────────────────────

    @Test(expected = TransportException.class)
    public void testThrowConvertedUnavailable() throws TransportException {
        StatusRuntimeException e = new StatusRuntimeException(Status.UNAVAILABLE);
        GrpcStatusConverter.throwConverted(e);
    }

    @Test(expected = OpenSearchException.class)
    public void testThrowConvertedNotFound() throws TransportException {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("Not found")
        );
        GrpcStatusConverter.throwConverted(e);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testThrowConvertedUnimplemented() throws TransportException {
        StatusRuntimeException e = new StatusRuntimeException(
            Status.UNIMPLEMENTED.withDescription("Not supported")
        );
        GrpcStatusConverter.throwConverted(e);
    }
}
