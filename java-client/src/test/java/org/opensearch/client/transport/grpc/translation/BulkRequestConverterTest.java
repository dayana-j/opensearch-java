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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.json.jackson3.JacksonJsonpMapper;
import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.VersionType;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.core.bulk.CreateOperation;
import org.opensearch.client.opensearch.core.bulk.DeleteOperation;
import org.opensearch.client.opensearch.core.bulk.UpdateOperation;
import org.opensearch.protobufs.BulkRequestBody;
import org.opensearch.protobufs.OperationContainer;
import org.junit.Before;
import org.junit.Test;

public class BulkRequestConverterTest {

    private JsonpMapper mapper;

    @Before
    public void setUp() {
        mapper = new JacksonJsonpMapper();
    }

    // ─── Index Operation Tests ───────────────────────────────────────────────────

    @Test
    public void testIndexOperationBasic() {
        BulkRequest request = new BulkRequest.Builder()
            .index("test-index")
            .operations(op -> op.index(idx -> idx
                .id("1")
                .document(new TestDocument("hello", 42))
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        assertEquals("test-index", proto.getIndex());
        assertEquals(1, proto.getBulkRequestBodyCount());

        BulkRequestBody body = proto.getBulkRequestBody(0);
        assertTrue(body.getOperationContainer().hasIndex());
        assertEquals("1", body.getOperationContainer().getIndex().getXId());
        assertTrue(body.hasObject());
    }

    @Test
    public void testIndexOperationAllMetaFields() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.index(idx -> idx
                .id("doc-1")
                .index("my-index")
                .routing("shard-1")
                .pipeline("my-pipeline")
                .ifPrimaryTerm(2L)
                .ifSeqNo(5L)
                .version(3L)
                .versionType(VersionType.External)
                .requireAlias(true)
                .document(new TestDocument("test", 1))
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);
        org.opensearch.protobufs.IndexOperation indexOp = proto.getBulkRequestBody(0)
            .getOperationContainer().getIndex();

        assertEquals("doc-1", indexOp.getXId());
        assertEquals("my-index", indexOp.getXIndex());
        assertEquals("shard-1", indexOp.getRouting());
        assertEquals("my-pipeline", indexOp.getPipeline());
        assertEquals(2L, indexOp.getIfPrimaryTerm());
        assertEquals(5L, indexOp.getIfSeqNo());
        assertEquals(3L, indexOp.getVersion());
        assertEquals(org.opensearch.protobufs.VersionType.VERSION_TYPE_EXTERNAL, indexOp.getVersionType());
        assertTrue(indexOp.getRequireAlias());
    }

    // ─── Create Operation Tests ──────────────────────────────────────────────────

    @Test
    public void testCreateOperation() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.create(c -> c
                .id("2")
                .index("my-index")
                .routing("r1")
                .document(new TestDocument("world", 99))
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        BulkRequestBody body = proto.getBulkRequestBody(0);
        assertTrue(body.getOperationContainer().hasCreate());
        assertEquals("2", body.getOperationContainer().getCreate().getXId());
        assertEquals("my-index", body.getOperationContainer().getCreate().getXIndex());
        assertEquals("r1", body.getOperationContainer().getCreate().getRouting());
        assertTrue(body.hasObject());
    }

    // ─── Delete Operation Tests ──────────────────────────────────────────────────

    @Test
    public void testDeleteOperation() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.delete(d -> d
                .id("3")
                .index("my-index")
                .routing("r1")
                .ifPrimaryTerm(1L)
                .ifSeqNo(10L)
                .version(5L)
                .versionType(VersionType.ExternalGte)
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        BulkRequestBody body = proto.getBulkRequestBody(0);
        assertTrue(body.getOperationContainer().hasDelete());

        org.opensearch.protobufs.DeleteOperation deleteOp = body.getOperationContainer().getDelete();
        assertEquals("3", deleteOp.getXId());
        assertEquals("my-index", deleteOp.getXIndex());
        assertEquals("r1", deleteOp.getRouting());
        assertEquals(1L, deleteOp.getIfPrimaryTerm());
        assertEquals(10L, deleteOp.getIfSeqNo());
        assertEquals(5L, deleteOp.getVersion());
        assertEquals(org.opensearch.protobufs.VersionType.VERSION_TYPE_EXTERNAL_GTE, deleteOp.getVersionType());
        assertFalse(body.hasObject());
    }

    // ─── Update Operation Tests ──────────────────────────────────────────────────

    @Test
    public void testUpdateOperationWithDoc() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.update(u -> u
                .id("4")
                .index("my-index")
                .retryOnConflict(3)
                .document(new TestDocument("updated", 100))
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        BulkRequestBody body = proto.getBulkRequestBody(0);
        assertTrue(body.getOperationContainer().hasUpdate());
        assertEquals("4", body.getOperationContainer().getUpdate().getXId());
        assertEquals(3, body.getOperationContainer().getUpdate().getRetryOnConflict());
        assertTrue(body.hasUpdateAction());
        assertTrue(body.getUpdateAction().hasDoc());
    }

    @Test
    public void testUpdateOperationWithDocAsUpsert() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.update(u -> u
                .id("5")
                .index("my-index")
                .document(new TestDocument("upserted", 50))
                .docAsUpsert(true)
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        BulkRequestBody body = proto.getBulkRequestBody(0);
        assertTrue(body.hasUpdateAction());
        // The update action contains the serialized JSON with doc_as_upsert field
        assertTrue(body.getUpdateAction().hasDoc());
        String updateJson = body.getUpdateAction().getDoc().toStringUtf8();
        assertTrue(updateJson.contains("doc_as_upsert"));
    }

    // ─── Top-Level Parameters Tests ──────────────────────────────────────────────

    @Test
    public void testTopLevelRefresh() {
        BulkRequest request = new BulkRequest.Builder()
            .refresh(Refresh.WaitFor)
            .operations(op -> op.delete(d -> d.id("1").index("x")))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);
        assertEquals(org.opensearch.protobufs.Refresh.REFRESH_WAIT_FOR, proto.getRefresh());
    }

    @Test
    public void testTopLevelPipeline() {
        BulkRequest request = new BulkRequest.Builder()
            .pipeline("my-pipeline")
            .operations(op -> op.delete(d -> d.id("1").index("x")))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);
        assertEquals("my-pipeline", proto.getPipeline());
    }

    @Test
    public void testTopLevelRouting() {
        BulkRequest request = new BulkRequest.Builder()
            .routing("custom-routing")
            .operations(op -> op.delete(d -> d.id("1").index("x")))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);
        assertEquals("custom-routing", proto.getRouting());
    }

    @Test
    public void testTopLevelRequireAlias() {
        BulkRequest request = new BulkRequest.Builder()
            .requireAlias(true)
            .operations(op -> op.delete(d -> d.id("1").index("x")))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);
        assertTrue(proto.getRequireAlias());
    }

    // ─── Multiple Operations Tests ───────────────────────────────────────────────

    @Test
    public void testMultipleMixedOperations() {
        List<BulkOperation> ops = new ArrayList<>();
        ops.add(new BulkOperation.Builder().index(idx -> idx.id("1").index("bulk-index").document(new TestDocument("a", 1))).build());
        ops.add(new BulkOperation.Builder().create(c -> c.id("2").index("bulk-index").document(new TestDocument("b", 2))).build());
        ops.add(new BulkOperation.Builder().update(u -> u.id("3").index("bulk-index").document(new TestDocument("c", 3))).build());
        ops.add(new BulkOperation.Builder().delete(d -> d.id("4").index("bulk-index")).build());

        BulkRequest request = new BulkRequest.Builder()
            .index("bulk-index")
            .operations(ops)
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        assertEquals("bulk-index", proto.getIndex());
        assertEquals(4, proto.getBulkRequestBodyCount());

        assertTrue(proto.getBulkRequestBody(0).getOperationContainer().hasIndex());
        assertTrue(proto.getBulkRequestBody(1).getOperationContainer().hasCreate());
        assertTrue(proto.getBulkRequestBody(2).getOperationContainer().hasUpdate());
        assertTrue(proto.getBulkRequestBody(3).getOperationContainer().hasDelete());
    }

    @Test
    public void testDocumentSerializationToBytes() {
        BulkRequest request = new BulkRequest.Builder()
            .operations(op -> op.index(idx -> idx
                .id("1")
                .index("test")
                .document(new TestDocument("hello world", 42))
            ))
            .build();

        org.opensearch.protobufs.BulkRequest proto = BulkRequestConverter.toProto(request, mapper);

        byte[] docBytes = proto.getBulkRequestBody(0).getObject().toByteArray();
        String json = new String(docBytes);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("hello world"));
        assertTrue(json.contains("42"));
    }

    // ─── Helper Test Document ────────────────────────────────────────────────────

    /**
     * Simple POJO for test document serialization.
     */
    public static class TestDocument {
        private String name;
        private int value;

        public TestDocument() {}

        public TestDocument(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
