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

import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.opensearch.protobufs.ErrorCause;
import org.opensearch.protobufs.Item;
import org.opensearch.protobufs.ResponseItem;
import org.opensearch.protobufs.ShardInfo;
import org.junit.Test;

public class BulkResponseConverterTest {

    // ─── Basic Response Tests ────────────────────────────────────────────────────

    @Test
    public void testBasicSuccessResponse() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(50)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("test-index")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("created")
                    .setXVersion(1)
                    .setXSeqNo(0)
                    .setXPrimaryTerm(1)
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);

        assertEquals(50, response.took());
        assertFalse(response.errors());
        assertEquals(1, response.items().size());

        BulkResponseItem item = response.items().get(0);
        assertEquals(OperationType.Index, item.operationType());
        assertEquals("test-index", item.index());
        assertEquals("1", item.id());
        assertEquals("created", item.result());
        assertEquals(Long.valueOf(1L), item.version());
        assertEquals(Long.valueOf(0L), item.seqNo());
        assertEquals(Long.valueOf(1L), item.primaryTerm());
    }

    @Test
    public void testResponseWithErrors() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(30)
            .setErrors(true)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("test-index")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("created")
                    .build())
                .build())
            .addItems(Item.newBuilder()
                .setUpdate(ResponseItem.newBuilder()
                    .setXIndex("test-index")
                    .setXId("2")
                    .setStatus(3) // INVALID_ARGUMENT
                    .setError(ErrorCause.newBuilder()
                        .setType("mapper_parsing_exception")
                        .setReason("failed to parse field [age]")
                        .build())
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);

        assertTrue(response.errors());
        assertEquals(2, response.items().size());

        // First item: success
        BulkResponseItem item1 = response.items().get(0);
        assertEquals(OperationType.Index, item1.operationType());
        assertEquals("created", item1.result());
        assertNull(item1.error());

        // Second item: error
        BulkResponseItem item2 = response.items().get(1);
        assertEquals(OperationType.Update, item2.operationType());
        assertNotNull(item2.error());
        assertEquals("mapper_parsing_exception", item2.error().type());
        assertEquals("failed to parse field [age]", item2.error().reason());
    }

    // ─── Operation Type Tests ────────────────────────────────────────────────────

    @Test
    public void testCreateOperationType() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(10)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setCreate(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("created")
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertEquals(OperationType.Create, response.items().get(0).operationType());
    }

    @Test
    public void testDeleteOperationType() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(10)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setDelete(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("deleted")
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertEquals(OperationType.Delete, response.items().get(0).operationType());
        assertEquals("deleted", response.items().get(0).result());
    }

    @Test
    public void testUpdateOperationType() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(10)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setUpdate(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("updated")
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertEquals(OperationType.Update, response.items().get(0).operationType());
        assertEquals("updated", response.items().get(0).result());
    }

    // ─── Shard Statistics Tests ──────────────────────────────────────────────────

    @Test
    public void testResponseWithShardInfo() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(20)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("created")
                    .setXShards(ShardInfo.newBuilder()
                        .setTotal(2)
                        .setSuccessful(2)
                        .setFailed(0)
                        .build())
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        BulkResponseItem item = response.items().get(0);
        assertNotNull(item.shards());
        assertEquals(2, item.shards().total());
        assertEquals(2, item.shards().successful());
        assertEquals(0, item.shards().failed());
    }

    // ─── Forced Refresh Tests ────────────────────────────────────────────────────

    @Test
    public void testForcedRefresh() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(10)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .setResult("created")
                    .setForcedRefresh(true)
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertTrue(response.items().get(0).forcedRefresh());
    }

    // ─── Ingest Took Tests ───────────────────────────────────────────────────────

    @Test
    public void testIngestTook() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(100)
            .setErrors(false)
            .setIngestTook(25)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertEquals(Long.valueOf(25L), response.ingestTook());
    }

    @Test
    public void testNoIngestTook() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(100)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder()
                    .setXIndex("idx")
                    .setXId("1")
                    .setStatus(0)
                    .build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertNull(response.ingestTook());
    }

    // ─── Multiple Items Tests ────────────────────────────────────────────────────

    @Test
    public void testMultipleMixedItems() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(150)
            .setErrors(false)
            .addItems(Item.newBuilder()
                .setIndex(ResponseItem.newBuilder().setXIndex("idx").setXId("1").setStatus(0).setResult("created").build())
                .build())
            .addItems(Item.newBuilder()
                .setCreate(ResponseItem.newBuilder().setXIndex("idx").setXId("2").setStatus(0).setResult("created").build())
                .build())
            .addItems(Item.newBuilder()
                .setUpdate(ResponseItem.newBuilder().setXIndex("idx").setXId("3").setStatus(0).setResult("updated").build())
                .build())
            .addItems(Item.newBuilder()
                .setDelete(ResponseItem.newBuilder().setXIndex("idx").setXId("4").setStatus(0).setResult("deleted").build())
                .build())
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);

        assertEquals(4, response.items().size());
        assertEquals(OperationType.Index, response.items().get(0).operationType());
        assertEquals(OperationType.Create, response.items().get(1).operationType());
        assertEquals(OperationType.Update, response.items().get(2).operationType());
        assertEquals(OperationType.Delete, response.items().get(3).operationType());
    }

    // ─── Empty Response Tests ────────────────────────────────────────────────────

    @Test
    public void testEmptyResponse() {
        org.opensearch.protobufs.BulkResponse protoResponse = org.opensearch.protobufs.BulkResponse.newBuilder()
            .setTook(0)
            .setErrors(false)
            .build();

        BulkResponse response = BulkResponseConverter.fromProto(protoResponse);
        assertEquals(0, response.took());
        assertFalse(response.errors());
        assertTrue(response.items().isEmpty());
    }
}
