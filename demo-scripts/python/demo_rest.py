#!/usr/bin/env python3
"""
OpenSearch Bulk Demo — REST (HTTP)
===================================

Sends bulk requests using REST/HTTP and shows:
  1. What the client sends (JSON request body)
  2. What comes back (JSON response)
  3. Performance measurements

Run:
  python demo_rest.py
"""

import time
import json
from opensearchpy import OpenSearch


# ─── Configuration ────────────────────────────────────────────────────────────

HOST = "localhost"
PORT = 9200
INDEX = "demo-benchmark"
NUM_DOCS = 100000
BATCH_SIZE = 5000


# ─── Helpers ──────────────────────────────────────────────────────────────────

def build_bulk_body(batch_num):
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
    print("  OpenSearch Bulk — REST/HTTP Transport")
    print("=" * 60)
    print(f"  Host:       {HOST}:{PORT}")
    print(f"  Protocol:   HTTP/1.1 (REST + JSON)")
    print(f"  Documents:  {NUM_DOCS}")
    print(f"  Batches:    {NUM_DOCS // BATCH_SIZE} x {BATCH_SIZE} docs")
    print("=" * 60)

    client = OpenSearch(
        hosts=[{"host": HOST, "port": PORT}],
        use_ssl=False,
        verify_certs=False,
    )

    info = client.info()
    print(f"\n  ✅ Connected to OpenSearch {info['version']['number']}")

    client.indices.delete(index=INDEX, ignore=[404])
    client.indices.create(index=INDEX)

    # ─── Show what the client SENDS ───────────────────────────────────────────

    print("\n" + "-" * 60)
    print("  📤 REQUEST — What the client sends over the wire")
    print("-" * 60)
    print(f"  POST http://{HOST}:{PORT}/_bulk")
    print(f"  Content-Type: application/x-ndjson")
    print()
    print("  Body (first 2 operations shown):")
    print()

    sample_body = build_bulk_body(0)
    # Show first 2 action/doc pairs (4 lines)
    for i in range(4):
        line = json.dumps(sample_body[i])
        print(f"    {line}")
    print(f"    ... ({BATCH_SIZE - 2} more operations)")
    print()

    payload = "\n".join(json.dumps(line) for line in sample_body) + "\n"
    print(f"  Total payload size: {format_size(len(payload.encode('utf-8')))}")
    print(f"  Format: NDJSON (newline-delimited JSON text)")

    # ─── Send all batches ─────────────────────────────────────────────────────

    print("\n" + "-" * 60)
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
        response = client.bulk(body=body)
        elapsed = time.time() - start

        batch_times.append(elapsed)
        total_server_took_ms += response["took"]
        last_response = response

        print(f"    Batch {batch + 1}: {elapsed * 1000:.0f}ms | "
              f"server took: {response['took']}ms | errors: {response['errors']}")

    overall_elapsed = time.time() - overall_start

    # ─── Show what the client RECEIVES ────────────────────────────────────────

    print("\n" + "-" * 60)
    print("  📥 RESPONSE — What the client receives back")
    print("-" * 60)
    print(f"  HTTP/1.1 200 OK")
    print(f"  Content-Type: application/json")
    print()
    print("  Body (first 2 items shown):")
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
    print(f"  Response format: JSON text")

    # ─── Measurements ─────────────────────────────────────────────────────────

    avg_batch_time = sum(batch_times) / len(batch_times)
    docs_per_sec = NUM_DOCS / overall_elapsed

    print("\n" + "=" * 60)
    print("  MEASUREMENTS (REST/HTTP)")
    print("=" * 60)
    print(f"  Total documents indexed:    {NUM_DOCS}")
    print(f"  Total payload sent:         {format_size(total_bytes_sent)}")
    print(f"  Wire format:                JSON (text)")
    print(f"  Protocol:                   HTTP/1.1")
    print()
    print(f"  Total wall-clock time:      {overall_elapsed:.3f}s")
    print(f"  Avg batch round-trip:       {avg_batch_time * 1000:.0f}ms")
    print(f"  Total server processing:    {total_server_took_ms}ms")
    print(f"  Throughput:                 {docs_per_sec:.0f} docs/sec")
    print("=" * 60)

    client.indices.delete(index=INDEX, ignore=[404])
    print("\n  🧹 Cleaned up\n")


if __name__ == "__main__":
    main()
