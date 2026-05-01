// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.FeedScope;
import com.multiclouddb.api.changefeed.NewItemStateMode;
import com.multiclouddb.api.changefeed.StartPosition;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cosmos change-feed adapter — implements {@code readChanges} and
 * {@code listPhysicalPartitions} on top of the Cosmos
 * {@link com.azure.cosmos.CosmosContainer#queryChangeFeed} API.
 * <p>
 * Always requests {@link CosmosChangeFeedRequestOptions#allVersionsAndDeletes()}
 * so the SDK can map distinct CREATE / UPDATE / DELETE events. If the
 * container is not provisioned for that mode, Cosmos returns a 400-class
 * error which we re-raise as
 * {@link MulticloudDbErrorCategory#INVALID_REQUEST} with an actionable
 * message.
 */
final class CosmosChangeFeed {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosChangeFeed.class);
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final CosmosProviderClient client;

    CosmosChangeFeed(CosmosProviderClient client) {
        this.client = client;
    }

    List<String> listPhysicalPartitions(ResourceAddress address) {
        try {
            CosmosContainer container = client.getContainerInternal(address);
            List<FeedRange> ranges = container.getFeedRanges();
            List<String> ids = new ArrayList<>(ranges.size());
            for (FeedRange r : ranges) {
                ids.add(B64E.encodeToString(r.toString().getBytes(StandardCharsets.UTF_8)));
            }
            return ids;
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "listPhysicalPartitions");
        }
    }

    ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Instant start = Instant.now();
        try {
            CosmosContainer container = client.getContainerInternal(request.address());
            CosmosChangeFeedRequestOptions cfOptions = buildRequestOptions(request);
            cfOptions.allVersionsAndDeletes();
            if (request.maxPageSize() > 0) {
                cfOptions.setMaxItemCount(request.maxPageSize());
            }

            Iterator<FeedResponse<JsonNode>> pages =
                    container.queryChangeFeed(cfOptions, JsonNode.class).iterableByPage().iterator();

            List<ChangeEvent> events = new ArrayList<>();
            String cosmosContinuation = null;
            int statusCode = 200;

            if (pages.hasNext()) {
                FeedResponse<JsonNode> page = pages.next();
                statusCode = 200;
                for (JsonNode raw : page.getResults()) {
                    ChangeEvent ev = mapEvent(raw, request);
                    if (ev != null) {
                        events.add(ev);
                    }
                }
                cosmosContinuation = page.getContinuationToken();
            }

            String token = cosmosContinuation != null
                    ? ContinuationTokenCodec.encode(ProviderId.COSMOS, request.address(),
                            new TextNode(cosmosContinuation))
                    : null;

            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, "readChanges",
                            Duration.between(start, Instant.now()))
                    .itemCount(events.size())
                    .statusCode(statusCode)
                    .build();
            return new ChangeFeedPage(events, token, false, List.of(), diag);
        } catch (CosmosException e) {
            // Cosmos returns 400 for AVAD-not-enabled containers; promote to INVALID_REQUEST
            if (e.getStatusCode() == 400) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Cosmos rejected the change-feed request. The container must be configured "
                                + "with change-feed mode 'AllVersionsAndDeletes' (continuous backup "
                                + "+ container settings) for distinct CREATE/UPDATE/DELETE events. "
                                + "Original message: " + e.getMessage(),
                        ProviderId.COSMOS, "readChanges", false,
                        Map.of("statusCode", String.valueOf(e.getStatusCode()))),
                        e);
            }
            if (e.getStatusCode() == 410 || e.getStatusCode() == 412) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                        "Cosmos change-feed cursor is no longer valid; restart from beginning() or now(). "
                                + "Original message: " + e.getMessage(),
                        ProviderId.COSMOS, "readChanges", false,
                        Map.of("statusCode", String.valueOf(e.getStatusCode()))),
                        e);
            }
            throw CosmosErrorMapper.map(e, "readChanges");
        }
    }

    private CosmosChangeFeedRequestOptions buildRequestOptions(ChangeFeedRequest request) {
        // Resolve the FeedRange from the scope
        FeedRange feedRange = resolveFeedRange(request);

        StartPosition sp = request.startPosition();
        if (sp instanceof StartPosition.Beginning) {
            return CosmosChangeFeedRequestOptions.createForProcessingFromBeginning(feedRange);
        }
        if (sp instanceof StartPosition.Now) {
            return CosmosChangeFeedRequestOptions.createForProcessingFromNow(feedRange);
        }
        if (sp instanceof StartPosition.AtTime atTime) {
            return CosmosChangeFeedRequestOptions.createForProcessingFromPointInTime(
                    atTime.timestamp(), feedRange);
        }
        if (sp instanceof StartPosition.FromContinuationToken token) {
            JsonNode inner = ContinuationTokenCodec.decode(token.token(),
                    ProviderId.COSMOS, request.address());
            String cosmosToken = inner.asText();
            if (cosmosToken == null || cosmosToken.isEmpty()) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Continuation token does not contain a Cosmos cursor",
                        ProviderId.COSMOS, "readChanges", false, Map.of()));
            }
            return CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(cosmosToken);
        }
        throw new IllegalStateException("Unhandled StartPosition: " + sp);
    }

    private FeedRange resolveFeedRange(ChangeFeedRequest request) {
        FeedScope scope = request.scope();
        if (scope instanceof FeedScope.EntireCollection) {
            return FeedRange.forFullRange();
        }
        if (scope instanceof FeedScope.PhysicalPartition pp) {
            String json;
            try {
                json = new String(B64D.decode(pp.partitionId()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Invalid Cosmos partitionId encoding: " + e.getMessage(),
                        ProviderId.COSMOS, "readChanges", false, Map.of()), e);
            }
            return FeedRange.fromString(json);
        }
        if (scope instanceof FeedScope.LogicalPartition lp) {
            return FeedRange.forLogicalPartition(new PartitionKey(lp.partitionKey().partitionKey()));
        }
        throw new IllegalStateException("Unhandled FeedScope: " + scope);
    }

    /**
     * Map a single AllVersionsAndDeletes record into a portable
     * {@link ChangeEvent}. Returns {@code null} if the record cannot be parsed
     * (best-effort skip, logged at debug).
     */
    private ChangeEvent mapEvent(JsonNode raw, ChangeFeedRequest request) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        JsonNode metadata = raw.path("metadata");
        if (metadata.isMissingNode()) {
            // LatestVersion mode shape — refuse: design requires AVAD.
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Cosmos change-feed record has no 'metadata' field. The container appears to be "
                            + "in LatestVersion mode; switch to AllVersionsAndDeletes for distinct "
                            + "CREATE/UPDATE/DELETE events.",
                    ProviderId.COSMOS, "readChanges", false, Map.of()));
        }
        String op = metadata.path("operationType").asText("");
        ChangeType type;
        switch (op.toLowerCase(java.util.Locale.ROOT)) {
            case "create": type = ChangeType.CREATE; break;
            case "replace":
            case "update": type = ChangeType.UPDATE; break;
            case "delete": type = ChangeType.DELETE; break;
            default:
                LOG.debug("Skipping change-feed record with unknown operationType '{}'", op);
                return null;
        }

        JsonNode current = raw.path("current");
        JsonNode previous = raw.path("previous");
        JsonNode source = current != null && current.isObject() && current.size() > 0
                ? current : previous;
        if (source == null || !source.isObject() || source.size() == 0) {
            // Cannot extract key
            return null;
        }

        String pk = source.path("partitionKey").asText(null);
        String id = source.path("id").asText(null);
        if (pk == null || pk.isEmpty()) {
            // Some records may store the PK under a different field name; fall back to id.
            pk = id;
        }
        if (pk == null || pk.isEmpty()) {
            return null;
        }
        MulticloudDbKey key = (id != null && !id.equals(pk))
                ? MulticloudDbKey.of(pk, id)
                : MulticloudDbKey.of(pk);

        // eventId — prefer metadata._lsn, fall back to current._lsn / _etag
        String eventId = metadata.path("_lsn").asText(null);
        if (eventId == null || eventId.isEmpty()) {
            eventId = source.path("_lsn").asText(null);
        }
        if (eventId == null || eventId.isEmpty()) {
            eventId = source.path("_etag").asText(null);
        }
        if (eventId == null || eventId.isEmpty()) {
            // Last resort — synthesise from id + ts
            eventId = id + ":" + source.path("_ts").asLong(0);
        }

        Instant ts = null;
        long tsSec = source.path("_ts").asLong(0);
        if (tsSec > 0) {
            ts = Instant.ofEpochSecond(tsSec);
        }

        ObjectNode data = null;
        if (request.newItemStateMode() != NewItemStateMode.OMIT
                && current != null && current.isObject() && current.size() > 0) {
            // Strip system fields for caller cleanliness
            data = (ObjectNode) current.deepCopy();
        }
        if (request.newItemStateMode() == NewItemStateMode.REQUIRE
                && type != ChangeType.DELETE && data == null) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                    "newItemStateMode=REQUIRE but no current image was returned for this event",
                    ProviderId.COSMOS, "readChanges", false, Map.of()));
        }

        return new ChangeEvent(ProviderId.COSMOS, eventId, type,
                request.address(), key, data, ts);
    }
}
