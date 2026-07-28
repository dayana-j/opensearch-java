# gRPC Search Implementation — Handoff Notes

## Branch: `grpc-search`
This branch contains the implementation of Search over gRPC for the opensearch-java client.

## Branch: `grpc-search-backup`
Contains skeleton files, demo scripts, and draw.io diagrams for reference.

## What's Implemented (Bulk — on `grpc-java` branch, merged)
- `GrpcTransport` — implements `OpenSearchTransport`, routes supported endpoints to gRPC
- `HybridTransport` — composes gRPC + REST, routes automatically
- `BulkRequestConverter` / `BulkResponseConverter` — translation layer
- `AwsGrpcTransport` — AWS SigV4 support (separate from general transport)
- TLS, Basic Auth, JWT interceptors
- Channel health monitoring
- 112 unit tests + integration test framework

## What Needs to Be Done (Search — on `grpc-search` branch)

### Implementation Steps (in order):
1. **Register `SearchRequest._ENDPOINT`** in `GrpcTransport.SUPPORTED_ENDPOINTS`
2. **Add `SearchServiceBlockingStub`** to `GrpcTransport` constructor
3. **`SearchRequestConverter.toProto()`** — convert opensearch-java SearchRequest → protobuf
4. **`SearchResponseConverter.fromProto()`** — convert protobuf SearchResponse → opensearch-java
5. **Wire `performSearch()`** into `GrpcTransport.performRequest()`
6. **Start with `match_all`** then expand to `term`, `match`, `bool`, `range`
7. **Handle `_source` deserialization** — bytes → JSON → TDocument via JsonpMapper

### Key Files to Create/Modify:
- `translation/SearchRequestConverter.java` (skeleton exists)
- `translation/SearchResponseConverter.java` (skeleton exists)
- `GrpcTransport.java` — add search stub + performSearch handler
- `GrpcTransportTest.java` — add search tests
- `translation/SearchRequestConverterTest.java` (new)
- `translation/SearchResponseConverterTest.java` (new)

### Key Challenges:
| Challenge | Details |
|-----------|---------|
| Query DSL tree | Must convert the full query hierarchy (bool → must → [term, match]) |
| `_source` deserialization | Response bytes → JSON → generic TDocument. Need JsonpMapper + class token |
| Generics | `SearchResponse<TDocument>` — converter needs the target class |
| Many query types | Server supports ~25. Start with match_all, expand incrementally |
| Aggregations | Server supports max/min/terms aggs. Defer to later |

### Server-Side gRPC Search API:
- Service: `SearchService` (separate from `DocumentService`)
- Method: `Search(SearchRequest) returns (SearchResponse)`
- Proto: `opensearch-protobufs` version 1.4.0
- Supported queries: match_all, match_none, term, terms, match, match_phrase, bool, k-NN, etc.
- Docs: https://docs.opensearch.org/latest/api-reference/grpc-apis/search/

### Pattern to Follow (from Bulk):
```java
// In GrpcTransport constructor:
this.searchStub = SearchServiceGrpc.newBlockingStub(channel);

// In performRequest():
if (endpoint == SearchRequest._ENDPOINT) {
    return performSearch(request);
}

// performSearch():
private SearchResponse performSearch(SearchRequest request) {
    var protoRequest = SearchRequestConverter.toProto(request, jsonpMapper);
    var protoResponse = searchStub.search(protoRequest);
    return SearchResponseConverter.fromProto(protoResponse, jsonpMapper, tDocumentClass);
}
```

## Demo Files (in `demo-scripts/`)
- `java/DemoRest.java` — REST bulk benchmark
- `java/DemoGrpc.java` — gRPC bulk benchmark
- `python/demo_rest.py` — REST demo with metrics
- `python/demo_grpc_only.py` — gRPC demo with metrics
- `python/grpc_bulk_bench.py` — full benchmark script
- `drawio/` — architecture diagrams for presentations

## Related PRs:
- Java gRPC Transport: https://github.com/opensearch-project/opensearch-java/pull/2062
- Python gRPC: PRs #1058, #1078, #1087, #1089, #1093 (merged)
- Documentation: https://github.com/dayana-j/documentation-website (branch: grpc_java)
