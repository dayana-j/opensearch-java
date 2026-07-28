#!/usr/bin/env python3
"""
gRPC vs REST Bulk Indexing Benchmark
=====================================

Apples-to-apples comparison: both clients call .bulk(body=...) identically.
The only difference is the client class (OpenSearch vs OpenSearchGrpc).

Usage:
  python grpc_bulk_bench.py \
    --rest-host <endpoint> --grpc-host <endpoint> --grpc-port 9400 \
    --user admin --password <pass> \
    --docs 100000 --batch 5000 --iterations 3 --warmup 1 \
    --csv results.csv
"""

import argparse
import json
import random
import string
import sys
import time
import csv as csv_module
from statistics import median


def generate_corpus(num_docs, text_len):
    """Build a shared corpus of random docs (done once, reused for both clients)."""
    corpus = []
    categories = ["electronics", "clothing", "home", "sports", "books", "food", "toys", "health"]
    for i in range(num_docs):
        doc = {
            "title": f"Product {i}",
            "price": round(random.uniform(1.0, 999.99), 2),
            "category": random.choice(categories),
            "in_stock": random.choice([True, False]),
            "rating": round(random.uniform(1.0, 5.0), 1),
            "description": "".join(random.choices(string.ascii_lowercase + " ", k=text_len)),
        }
        corpus.append(doc)
    return corpus


def build_bulk_body(corpus, index_name, start, end):
    """Build NDJSON bulk body from corpus slice — identical for REST and gRPC."""
    body = []
    for i in range(start, end):
        body.append({"index": {"_index": index_name, "_id": str(i)}})
        body.append(corpus[i])
    return body


def estimate_payload_bytes(corpus, num_docs):
    """Estimate JSON payload size for reporting MB/sec."""
    sample = corpus[:min(100, num_docs)]
    sample_size = sum(len(json.dumps(doc)) + len(json.dumps({"index": {"_index": "x", "_id": "0"}})) + 2 for doc in sample)
    avg_per_doc = sample_size / len(sample)
    return avg_per_doc * num_docs


def run_bulk(client, corpus, index_name, num_docs, batch_size):
    """Run bulk indexing through client.bulk() and return elapsed seconds."""
    start = time.perf_counter()
    for offset in range(0, num_docs, batch_size):
        end = min(offset + batch_size, num_docs)
        body = build_bulk_body(corpus, index_name, offset, end)
        response = client.bulk(body=body)
        if response.get("errors"):
            error_items = [item for item in response["items"] if list(item.values())[0].get("error")]
            if error_items:
                print(f"    ⚠ {len(error_items)} errors in batch at offset {offset}")
    elapsed = time.perf_counter() - start
    return elapsed


def create_fresh_index(client, index_name):
    """Delete and recreate index with perf-optimized settings."""
    client.indices.delete(index=index_name, ignore=[404])
    client.indices.create(
        index=index_name,
        body={
            "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "refresh_interval": "-1",
            }
        },
    )


def main():
    parser = argparse.ArgumentParser(description="gRPC vs REST Bulk Indexing Benchmark")
    parser.add_argument("--rest-host", required=True, help="REST endpoint (host or host:port)")
    parser.add_argument("--rest-port", type=int, default=9200, help="REST port (default: 9200)")
    parser.add_argument("--grpc-host", required=True, help="gRPC endpoint (host)")
    parser.add_argument("--grpc-port", type=int, default=9400, help="gRPC port (default: 9400)")
    parser.add_argument("--user", default=None, help="Username for basic auth")
    parser.add_argument("--password", default=None, help="Password for basic auth")
    parser.add_argument("--docs", type=int, default=100000, help="Number of documents (default: 100000)")
    parser.add_argument("--batch", type=int, default=5000, help="Batch size (default: 5000)")
    parser.add_argument("--text-len", type=int, default=200, help="Description text length (default: 200)")
    parser.add_argument("--iterations", type=int, default=3, help="Measured iterations (default: 3)")
    parser.add_argument("--warmup", type=int, default=1, help="Warmup iterations (default: 1)")
    parser.add_argument("--csv", default=None, help="Path to write CSV results")
    parser.add_argument("--use-ssl", action="store_true", help="Use HTTPS for REST")
    args = parser.parse_args()

    # ─── Setup Clients ────────────────────────────────────────────────────────

    from opensearchpy import OpenSearch
    from opensearchpy.client import OpenSearchGrpc

    auth = (args.user, args.password) if args.user and args.password else None
    scheme = "https" if args.use_ssl else "http"

    # REST client
    rest_client = OpenSearch(
        hosts=[{"host": args.rest_host, "port": args.rest_port}],
        http_auth=auth,
        use_ssl=args.use_ssl,
        verify_certs=False,
        ssl_show_warn=False,
    )

    # gRPC client — same .bulk() API, different transport under the hood
    grpc_client = OpenSearchGrpc(
        hosts=[{"host": args.rest_host, "port": args.rest_port}],
        grpc_hosts=[{"host": args.grpc_host, "port": args.grpc_port}],
        http_auth=auth,
        use_ssl=args.use_ssl,
        verify_certs=False,
        ssl_show_warn=False,
    )

    # ─── Verify Connection ────────────────────────────────────────────────────

    print(f"\nConnecting to {args.rest_host}:{args.rest_port}...")
    info = rest_client.info()
    version = info["version"]["number"]
    print(f"  cluster version: {version}")
    print(f"  gRPC target: {args.grpc_host}:{args.grpc_port}")

    # ─── Build Corpus ─────────────────────────────────────────────────────────

    print(f"\nBuilding corpus of {args.docs:,} docs (text_len={args.text_len})...")
    corpus = generate_corpus(args.docs, args.text_len)
    payload_bytes = estimate_payload_bytes(corpus, args.docs)
    payload_mb = payload_bytes / (1024 * 1024)
    print(f"  estimated JSON payload: {payload_mb:.1f} MB per iteration")

    # ─── Run Benchmark ────────────────────────────────────────────────────────

    index_rest = "bench-rest"
    index_grpc = "bench-grpc"
    rest_results = []
    grpc_results = []

    total_runs = args.warmup + args.iterations

    # --- REST ---
    print(f"\n{'=' * 50}")
    print(f"  REST ({args.warmup} warmup + {args.iterations} measured)")
    print(f"{'=' * 50}")
    for run in range(total_runs):
        is_warmup = run < args.warmup
        label = f"warmup {run + 1}" if is_warmup else f"run {run - args.warmup + 1}"

        create_fresh_index(rest_client, index_rest)
        elapsed = run_bulk(rest_client, corpus, index_rest, args.docs, args.batch)
        docs_per_sec = args.docs / elapsed
        mb_per_sec = payload_mb / elapsed

        if not is_warmup:
            rest_results.append({"elapsed": elapsed, "docs_sec": docs_per_sec, "mb_sec": mb_per_sec})

        print(f"  [{label}] {args.docs:,} docs in {elapsed:.2f}s => {docs_per_sec:,.0f} docs/sec, {mb_per_sec:.1f} MB/sec")

    rest_client.indices.delete(index=index_rest, ignore=[404])

    # --- gRPC ---
    print(f"\n{'=' * 50}")
    print(f"  gRPC ({args.warmup} warmup + {args.iterations} measured)")
    print(f"{'=' * 50}")
    for run in range(total_runs):
        is_warmup = run < args.warmup
        label = f"warmup {run + 1}" if is_warmup else f"run {run - args.warmup + 1}"

        create_fresh_index(grpc_client, index_grpc)
        elapsed = run_bulk(grpc_client, corpus, index_grpc, args.docs, args.batch)
        docs_per_sec = args.docs / elapsed
        mb_per_sec = payload_mb / elapsed

        if not is_warmup:
            grpc_results.append({"elapsed": elapsed, "docs_sec": docs_per_sec, "mb_sec": mb_per_sec})

        print(f"  [{label}] {args.docs:,} docs in {elapsed:.2f}s => {docs_per_sec:,.0f} docs/sec, {mb_per_sec:.1f} MB/sec")

    grpc_client.indices.delete(index=index_grpc, ignore=[404])

    # ─── Results ──────────────────────────────────────────────────────────────

    rest_median_docs = median(r["docs_sec"] for r in rest_results)
    rest_median_mb = median(r["mb_sec"] for r in rest_results)
    grpc_median_docs = median(r["docs_sec"] for r in grpc_results)
    grpc_median_mb = median(r["mb_sec"] for r in grpc_results)
    ratio = grpc_median_docs / rest_median_docs if rest_median_docs > 0 else 0

    print(f"\n{'=' * 50}")
    print(f"  RESULTS (median of {args.iterations} iterations)")
    print(f"{'=' * 50}")
    print(f"  REST:  {rest_median_docs:>10,.0f} docs/sec | {rest_median_mb:>6.1f} MB/sec")
    print(f"  gRPC:  {grpc_median_docs:>10,.0f} docs/sec | {grpc_median_mb:>6.1f} MB/sec")
    print(f"{'=' * 50}")
    print(f"  gRPC is {ratio:.2f}x REST")
    if ratio > 1:
        print(f"  gRPC is {((ratio - 1) * 100):.0f}% FASTER")
    else:
        print(f"  gRPC is {((1 - ratio) * 100):.0f}% slower (Python protobuf overhead)")
    print(f"{'=' * 50}\n")

    # ─── CSV Export ───────────────────────────────────────────────────────────

    if args.csv:
        with open(args.csv, "w", newline="") as f:
            writer = csv_module.writer(f)
            writer.writerow(["transport", "iteration", "docs", "batch", "elapsed_sec", "docs_per_sec", "mb_per_sec"])
            for i, r in enumerate(rest_results):
                writer.writerow(["REST", i + 1, args.docs, args.batch, f"{r['elapsed']:.3f}", f"{r['docs_sec']:.0f}", f"{r['mb_sec']:.2f}"])
            for i, r in enumerate(grpc_results):
                writer.writerow(["gRPC", i + 1, args.docs, args.batch, f"{r['elapsed']:.3f}", f"{r['docs_sec']:.0f}", f"{r['mb_sec']:.2f}"])
        print(f"  CSV saved to: {args.csv}\n")


if __name__ == "__main__":
    main()
