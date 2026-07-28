#!/usr/bin/env python3
"""
OpenSearch Bulk Demo — gRPC Transport
======================================

Sends the SAME bulk requests as demo_rest.py using the SAME API.
The only difference: the transport converts to protobuf under the hood.

Run:
  python demo_grpc_only.py
"""

import time
import json
from opensearchpy import OpenSearchGrpc


# ─── Configuration ────────────────────────────────────────────────────────────

REST_HOST = "localhost"
REST_PORT = 9200
GRPC_HOST = "localhost"
GRPC_PORT = 9400
INDEX = "demo-benchmark"
NUM_DOCS = 100000
BATCH_SIZE = 5000


# ─── Helpers ──────────────────────────────────────────────────────────────────

def build_bulk_body(batch_num):
    """Same exact body as REST demo — the API is identical."""
    body = []
    start_id = batch_num * BATCH_SIZE
    for i in range(BATCH_SIZE):
        doc_id = start_id + i
        body.append({"index": {"_index": INDEX, "_id": str(doc_id)}})
        body.append({
            "name": f"Product {doc_id}",
            "price": round(9.99 + doc_id * 0.1, 2),
            "category": ["electronics", "clothing", "home", "sports"][doc_id % 4],
            "in_stock": doc_id % 3 != 0,
            "description": f"This is a sample product description for item {doc_id}."
        })
    return body


def format_size(num_bytes):
    if num_bytes < 1024:
        return f"{num_bytes} B"
    elif num_bytes < 1024 * 1024:
        return f"{num_bytes / 1024:.1f} KB"
    else:
        return f"{num_bytes / (1024 * 1024):.1f} MB"


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    print("\n" + "=" * 60)
    print("  OpenSearch Bulk — gRPC Transport")
    print("=" * 60)
    print(f"  Host:       {GRPC_HOST}:{GRPC_PORT} (gRPC)")
    print(f"  Protocol:   HTTP/2 + Protobuf (under the hood)")
    print(f"  Documents:  {NUM_DOCS}")
    print(f"  Batches:    {NUM_DOCS // BATCH_SIZE} x {BATCH_SIZE} docs")
    print("=" * 60)

    # Only difference from REST: OpenSearchGrpc + grpc_hosts
    client = OpenSearchGrpc(
        hosts=[{"host": REST_HOST, "port": REST_PORT}],
        grpc_hosts=[{"host": GRPC_HOST, "port": GRPC_PORT}],
    )

    info = client.info()
    print(f"\n  ✅ Connected to OpenSearch {info['version']['number']}")

    client.indices.delete(index=INDEX, ignore=[404])
    client.indices.create(index=INDEX)

    # ─── Show what the user writes (SAME as REST) ─────────────────────────────

    print("\n" + "-" * 60)
    print("  📤 REQUEST — What your code sends (identical to REST)")
    print("-" * 60)
    print()
    print("    client.bulk(body=[")
    print(f'      {{"index": {{"_index": "{INDEX}", "_id": "0"}}}},')
    print(f'      {{"name": "Product 0", "price": 9.99, "category": "electronics", ...}},')
    print(f'      {{"index": {{"_index": "{INDEX}", "_id": "1"}}}},')
    print(f'      {{"name": "Product 1", "price": 10.09, "category": "clothing", ...}},')
    print(f"      ... ({BATCH_SIZE - 2} more operations)")
    print("    ])")
    print()
    print("    ⚙️  Under the hood: dict → protobuf → gRPC (HTTP/2, binary)")
    print()

    # ─── Send all batches ─────────────────────────────────────────────────────

    print("-" * 60)
    print("  ⏱  Sending all batches...")
    print("-" * 60)

    total_bytes_sent = 0
    total_server_took_ms = 0
    batch_times = []
    last_response = None

    overall_start = time.time()

    for batch in range(NUM_DOCS // BATCH_SIZE):
        body = build_bulk_body(batch)
        payload = "\n".join(json.dumps(line) for line in body) + "\n"
        total_bytes_sent += len(payload.encode("utf-8"))

        start = time.time()
        response = client.bulk(body=body)  # Same call as REST
        elapsed = time.time() - start

        batch_times.append(elapsed)
        total_server_took_ms += response["took"]
        last_response = response

        print(f"    Batch {batch + 1}: {elapsed * 1000:.0f}ms | "
              f"server took: {response['took']}ms | errors: {response['errors']}")

    overall_elapsed = time.time() - overall_start

    # ─── Show what the user receives (SAME as REST) ───────────────────────────

    print("\n" + "-" * 60)
    print("  📥 RESPONSE — What your code receives (identical to REST)")
    print("-" * 60)
    print()
    print("    ⚙️  Under the hood: gRPC response → protobuf → dict")
    print()
    print("    {")
    print(f'      "took": {last_response["took"]},')
    print(f'      "errors": {str(last_response["errors"]).lower()},')
    print(f'      "items": [')
    for i, item in enumerate(last_response["items"][:2]):
        op_type = list(item.keys())[0]
        data = item[op_type]
        print(f'        {{"{op_type}": {{')
        print(f'          "_index": "{data.get("_index", "")}",')
        print(f'          "_id": "{data.get("_id", "")}",')
        print(f'          "result": "{data.get("result", "")}",')
        print(f'          "status": {data.get("status", 0)},')
        print(f'          "_version": {data.get("_version", 0)},')
        print(f'          "_seq_no": {data.get("_seq_no", 0)},')
        print(f'          "_primary_term": {data.get("_primary_term", 0)}')
        comma = "," if i < 1 else ""
        print(f'        }}}}{comma}')
    print(f'        ... ({BATCH_SIZE - 2} more items)')
    print(f'      ]')
    print(f'    }}')
    print()
    print("    ☝️  Same exact response as REST — your code doesn't change!")

    # ─── Measurements ─────────────────────────────────────────────────────────

    avg_batch_time = sum(batch_times) / len(batch_times)
    docs_per_sec = NUM_DOCS / overall_elapsed

    print("\n" + "=" * 60)
    print("  MEASUREMENTS (gRPC)")
    print("=" * 60)
    print(f"  Total documents indexed:    {NUM_DOCS}")
    print(f"  Equivalent JSON size:       {format_size(total_bytes_sent)}")
    print(f"  Wire format:                Protobuf (binary, ~40% smaller)")
    print(f"  Protocol:                   HTTP/2 (multiplexed)")
    print()
    print(f"  Total wall-clock time:      {overall_elapsed:.3f}s")
    print(f"  Avg batch round-trip:       {avg_batch_time * 1000:.0f}ms")
    print(f"  Total server processing:    {total_server_took_ms}ms")
    print(f"  Throughput:                 {docs_per_sec:.0f} docs/sec")
    print("=" * 60)

    client.indices.refresh(index=INDEX)
    search = client.search(index=INDEX, body={"query": {"match_all": {}}})
    print(f"\n  🔍 Verification: {search['hits']['total']['value']} docs indexed")

    client.indices.delete(index=INDEX, ignore=[404])
    print("  🧹 Cleaned up\n")


if __name__ == "__main__":
    main()
