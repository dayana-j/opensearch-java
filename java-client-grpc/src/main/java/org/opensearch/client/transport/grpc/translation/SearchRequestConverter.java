/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.client.transport.grpc.translation;

import org.opensearch.client.json.JsonpMapper;
import org.opensearch.client.opensearch.core.SearchRequest;

/**
 * Converts opensearch-java SearchRequest to protobuf SearchRequest.
 * <p>
 * Supported query types (expand incrementally):
 * - match_all
 * - term / terms (TODO)
 * - match (TODO)
 * - bool (TODO)
 * - range (TODO)
 * <p>
 * Architecture:
 * <pre>
 * client.search(req)
 *   → SearchRequestConverter.toProto(searchRequest, jsonpMapper)
 *   → protobuf SearchRequest
 *   → SearchServiceGrpc.search()
 * </pre>
 */
public class SearchRequestConverter {

    /**
     * Convert an opensearch-java SearchRequest to a protobuf SearchRequest.
     *
     * @param request    the client SearchRequest
     * @param jsonpMapper the JSON mapper for serialization
     * @return the protobuf SearchRequest
     */
    public static org.opensearch.protobufs.SearchRequest toProto(SearchRequest request, JsonpMapper jsonpMapper) {
        org.opensearch.protobufs.SearchRequest.Builder protoBuilder = org.opensearch.protobufs.SearchRequest.newBuilder();

        // Set index(es)
        if (request.index() != null && !request.index().isEmpty()) {
            protoBuilder.addAllIndex(request.index());
        }

        // Build SearchRequestBody
        org.opensearch.protobufs.SearchRequestBody.Builder bodyBuilder = org.opensearch.protobufs.SearchRequestBody.newBuilder();

        // Set size
        if (request.size() != null) {
            bodyBuilder.setSize(request.size());
        }

        // Set from
        if (request.from() != null) {
            bodyBuilder.setFrom(request.from());
        }

        // Convert query
        if (request.query() != null) {
            org.opensearch.protobufs.QueryContainer queryContainer = convertQuery(request.query());
            if (queryContainer != null) {
                bodyBuilder.setQuery(queryContainer);
            }
        }

        protoBuilder.setSearchRequestBody(bodyBuilder.build());
        return protoBuilder.build();
    }

    /**
     * Convert an opensearch-java Query to a protobuf QueryContainer.
     * Start with match_all, expand to other query types.
     */
    private static org.opensearch.protobufs.QueryContainer convertQuery(org.opensearch.client.opensearch._types.query_dsl.Query query) {
        org.opensearch.protobufs.QueryContainer.Builder containerBuilder = org.opensearch.protobufs.QueryContainer.newBuilder();

        // TODO: Use query._kind() to determine the query type
        // For now, only match_all is implemented

        if (query.isMatchAll()) {
            containerBuilder.setMatchAll(convertMatchAll(query.matchAll()));
        }
        // TODO: Add more query types here
        // else if (query.isTerm()) { ... }
        // else if (query.isMatch()) { ... }
        // else if (query.isBool()) { ... }
        else {
            throw new UnsupportedOperationException(
                "Query type not yet supported for gRPC transport: " + query._kind()
            );
        }

        return containerBuilder.build();
    }

    /**
     * Convert MatchAllQuery to protobuf.
     * MatchAllQuery only has optional boost and _name fields.
     */
    private static org.opensearch.protobufs.MatchAllQuery convertMatchAll(
        org.opensearch.client.opensearch._types.query_dsl.MatchAllQuery matchAll
    ) {
        org.opensearch.protobufs.MatchAllQuery.Builder builder = org.opensearch.protobufs.MatchAllQuery.newBuilder();

        if (matchAll.boost() != null) {
            builder.setBoost(matchAll.boost().floatValue());
        }

        // TODO: handle _name/x_name if needed

        return builder.build();
    }
}
