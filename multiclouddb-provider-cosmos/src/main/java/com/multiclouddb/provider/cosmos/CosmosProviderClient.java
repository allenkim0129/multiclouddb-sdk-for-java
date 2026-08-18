// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.multiclouddb.api.*;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.PatchNumericDomain;
import com.multiclouddb.spi.DocumentFieldValidator;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import com.multiclouddb.spi.SdkUserAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Azure Cosmos DB provider client implementing CRUD + query with continuation
 * token paging.
 * <p>
 * Connection config keys:
 * <ul>
 * <li>{@code endpoint} — Cosmos account endpoint URL (required)</li>
 * <li>{@code key} — Cosmos account key (optional; omit to use
 *     {@link DefaultAzureCredentialBuilder})</li>
 * <li>{@code consistencyLevel} — read consistency override (optional; omit to
 *     inherit the Cosmos account's default consistency level). Accepted values
 *     (case-insensitive): {@code STRONG}, {@code BOUNDED_STALENESS},
 *     {@code SESSION}, {@code CONSISTENT_PREFIX}, {@code EVENTUAL}.
 *     The override must be equal to or weaker than the account's default.</li>
 * </ul>
 */
public class CosmosProviderClient implements MulticloudDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosProviderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CosmosClient cosmosClient;
    private final MulticloudDbClientConfig config;
    private final CosmosChangeFeedReader changeFeedReader;
    /**
     * Lifecycle flag flipped by {@link #close()}. Public CRUD/query/provisioning
     * entry points check this first via {@link #checkOpen(String)} and throw
     * {@link MulticloudDbErrorCategory#CLIENT_CLOSED} instead of leaking the
     * underlying {@code IllegalStateException} that the azure-cosmos SDK would
     * surface after its own close. Declared {@code volatile} so cross-thread
     * close → operation racing observes the flip without locking; double-close
     * is guarded by the {@code synchronized} {@link #close()} method.
     */
    private volatile boolean closed = false;


    /**
     * Constructs a Cosmos DB provider client from the supplied configuration.
     * <p>
     * Authentication is selected automatically:
     * <ul>
     *   <li>If {@code connection.key} is present, key-based authentication is used.</li>
     *   <li>Otherwise {@link DefaultAzureCredentialBuilder} is used, supporting
     *       Managed Identity, Azure CLI, environment variables, and the full
     *       DefaultAzureCredential chain.</li>
     * </ul>
     *
     * @param config client configuration carrying connection, auth, and options
     * @throws IllegalArgumentException if {@code connection.endpoint} is missing or blank,
     *                                  or if {@code connection.consistencyLevel} is present
     *                                  but not a valid consistency level value
     */
    public CosmosProviderClient(MulticloudDbClientConfig config) {
        this.config = config;
        String endpoint = config.connection().get(CosmosConstants.CONFIG_ENDPOINT);
        String key      = config.connection().get(CosmosConstants.CONFIG_KEY);

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(CosmosConstants.ERR_ENDPOINT_REQUIRED);
        }

        CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(endpoint)
                .contentResponseOnWriteEnabled(true);

        if (key != null && !key.isBlank()) {
            builder.key(key);
            LOG.info("Cosmos client using key-based authentication");
        } else {
            String tenantId = config.connection().get(CosmosConstants.CONFIG_TENANT_ID);
            DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder();
            if (tenantId != null && !tenantId.isBlank()) {
                credentialBuilder.tenantId(tenantId);
            }
            TokenCredential credential = credentialBuilder.build();
            builder.credential(credential);
            LOG.info("Cosmos client using DefaultAzureCredential (supports Managed Identity, Azure CLI, environment variables)");
        }

        String connectionMode = config.connection().getOrDefault(
                CosmosConstants.CONFIG_CONNECTION_MODE, CosmosConstants.CONNECTION_MODE_DEFAULT);
        if (CosmosConstants.CONNECTION_MODE_DIRECT.equalsIgnoreCase(connectionMode)) {
            builder.directMode();
        } else {
            builder.gatewayMode();
        }

        String consistencyStr = config.connection().get(CosmosConstants.CONFIG_CONSISTENCY_LEVEL);
        ConsistencyLevel readConsistencyOverride = null;
        if (consistencyStr != null) {
            readConsistencyOverride = CosmosConstants.parseConsistencyLevel(consistencyStr);
            builder.consistencyLevel(readConsistencyOverride);
            LOG.warn("Cosmos read consistency override set to '{}'. " +
                    "This must be equal to or weaker than the account's default consistency level; " +
                    "a stronger override will cause a runtime error from the Cosmos DB service.",
                    readConsistencyOverride);
        }

        builder.userAgentSuffix(SdkUserAgent.userAgent(config));

        this.cosmosClient = builder.buildClient();
        // Stamp the configured extendedRetention onto every minted cursor so a
        // persisted token can outlive the 24h portable baseline up to the
        // server-side AVAD retention window. Defaults to the baseline when
        // the opt-in is not set, keeping the wire form unchanged for the
        // common case.
        long effectiveRetentionMillis = config.changeFeed().extendedRetention()
                .map(java.time.Duration::toMillis)
                .orElse(com.multiclouddb.api.changefeed.internal.CursorTokenCodec.MAX_TOKEN_AGE_MILLIS);
        this.changeFeedReader = new CosmosChangeFeedReader(ProviderId.COSMOS, effectiveRetentionMillis);
        LOG.info("Cosmos client created for endpoint: {}", endpoint);
        LOG.info("Cosmos read consistency: {}", readConsistencyOverride != null ? readConsistencyOverride : "account default");
    }

    /**
     * Inserts a new document into the specified container.
     * <p>
     * Before writing, two system fields are injected into the document:
     * <ul>
     *   <li>{@code id} — set to {@code key.sortKey()} if present, otherwise {@code key.partitionKey()}.
     *       This is the Cosmos DB item identifier required by the SDK.</li>
     *   <li>{@code partitionKey} — set to {@code key.partitionKey()}, matching the
     *       container's partition key path ({@code /partitionKey}).</li>
     * </ul>
     * Uses a {@code createItem} call with no pre-condition, so the operation fails with
     * {@link com.multiclouddb.api.MulticloudDbErrorCategory#CONFLICT} if an item with
     * the same {@code id} already exists in the partition.
     *
     * @param address the logical database + container to write to
     * @param key     the document key; {@code partitionKey} is required, {@code sortKey} is optional
     * @param document the document payload as a flat or nested map
     * @param options  operation options (currently unused by this provider; reserved for timeout support)
     * @throws com.multiclouddb.api.MulticloudDbException mapped from {@link CosmosException} —
     *         category {@code CONFLICT} (409) if the key already exists
     */
    @Override
    public void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.CREATE);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.COSMOS, OperationNames.CREATE);
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.createItem(doc, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.CREATE, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.CREATE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    /**
     * Reads a single document by its composite key.
     * <p>
     * Performs a direct point-read using the Cosmos DB {@code readItem} API, which is
     * the lowest-latency read path. The item is looked up by the Cosmos {@code id}
     * (derived from {@code key.sortKey()} or {@code key.partitionKey()}) within the
     * specified logical partition.
     *
     * @param address the logical database + container to read from
     * @param key     the document key; {@code partitionKey} is required, {@code sortKey} is optional
     * @param options operation options (currently unused by this provider)
     * @return the document as a {@code Map<String, Object>}, or {@code null} if not found (HTTP 404)
     * @throws com.multiclouddb.api.MulticloudDbException for any non-404 Cosmos error
     */
    @Override
    public DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.READ);
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            CosmosItemRequestOptions readOpts = new CosmosItemRequestOptions();
            CosmosItemResponse<ObjectNode> response = container.readItem(cosmosId, pk, readOpts, ObjectNode.class);
            logItemDiagnostics(OperationNames.READ, address, response);
            ObjectNode raw = response.getItem();
            if (raw == null) return null;

            // Strip Cosmos system properties so the returned document is portable
            // across providers (Cosmos-only fields like _rid, _self, _ts, etc. must
            // not appear in a DocumentResult that callers compare across providers).
            ObjectNode item = raw.deepCopy();
            CosmosConstants.SYSTEM_FIELDS.forEach(item::remove);

            DocumentMetadata metadata = null;
            if (options != null && options.includeMetadata()) {
                DocumentMetadata.Builder metaBuilder = DocumentMetadata.builder();
                if (response.getETag() != null) {
                    metaBuilder.version(response.getETag());
                }
                // _ts is a Unix epoch second — expose as lastModified
                JsonNode tsNode = raw.get(CosmosConstants.SYS_TIMESTAMP);
                if (tsNode != null && tsNode.isNumber()) {
                    metaBuilder.lastModified(Instant.ofEpochSecond(tsNode.longValue()));
                }
                metadata = metaBuilder.build();
            }
            return new DocumentResult(item, metadata);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            logExceptionDiagnostics(OperationNames.READ, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.READ);
        }
    }

    /**
     * Replaces an existing document in the specified container.
     * <p>
     * Uses the Cosmos DB {@code replaceItem} API, which requires the item to already
     * exist — the operation fails with {@link com.multiclouddb.api.MulticloudDbErrorCategory#NOT_FOUND}
     * if no matching item is found. The system fields {@code id} and {@code partitionKey}
     * are injected before the write, consistent with {@link #create}.
     *
     * @param address  the logical database + container
     * @param key      the document key identifying the item to replace
     * @param document the new document payload; replaces the entire stored document
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException category {@code NOT_FOUND} (404) if the item
     *         does not exist
     */
    @Override
    public void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPDATE);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.COSMOS, OperationNames.UPDATE);
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            doc.put(CosmosConstants.FIELD_ID, cosmosId);
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.replaceItem(doc, cosmosId, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.UPDATE, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.UPDATE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    /**
     * Creates or replaces a document (upsert semantics).
     * <p>
     * Uses the Cosmos DB {@code upsertItem} API, which inserts the item if it does not
     * exist, or replaces it completely if it does. The system fields {@code id} and
     * {@code partitionKey} are injected before the write.
     *
     * @param address  the logical database + container
     * @param key      the document key
     * @param document the document payload
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos error
     */
    @Override
    public void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPSERT);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.COSMOS, OperationNames.UPSERT);
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.upsertItem(doc, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.UPSERT, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.UPSERT, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    /**
     * Applies field-level modifications using the Cosmos DB Patch API.
     * <p>
     * Every operation is translated into a {@link CosmosPatchOperations} entry and
     * sent as a single request, so Cosmos applies them atomically server-side.
     * Direct SPI callers are validated before any provider SDK work. For strict
     * paths and increments, the adapter point-reads the document and validates
     * required paths, numeric targets, and the portable result domain, so a
     * missing target or a deterministic numeric failure is reported before the
     * write.
     *
     * <h4>Optimistic concurrency</h4>
     * The write carries a <em>path-scoped</em>, server-evaluated precondition —
     * Cosmos's conditional patch
     * ({@link CosmosPatchItemRequestOptions#setFilterPredicate}) — never an
     * item-scoped {@code If-Match} ETag. The predicate asserts only what the
     * portable contract requires, namely that each addressed path already
     * exists, for the operations whose native Cosmos translation cannot enforce
     * that by itself:
     * <ul>
     *   <li>{@code REPLACE} — translated to a native {@code set}, which would
     *       otherwise <em>create</em> a target the contract requires to exist.</li>
     *   <li>{@code REMOVE} — Cosmos rejects a missing path with a
     *       provider-specific {@code 400} rather than the portable
     *       {@code NOT_FOUND}.</li>
     *   <li>a nested non-increment path — native {@code set} must not create
     *       intermediate objects, so the <em>parent</em> must be defined.</li>
     * </ul>
     * An {@code If-Match} ETag would fail on <em>any</em> concurrent mutation of
     * the item, including one touching a completely unrelated field, so two
     * threads patching disjoint fields would collide on Cosmos alone: DynamoDB's
     * {@code attribute_exists(...)} condition and Spanner's auto-retried
     * read-write transaction both let them through. The filter predicate is
     * evaluated atomically with the mutation and only over the addressed paths,
     * so disjoint concurrent patches all land, matching both peers.
     * <p>
     * {@code INCREMENT} contributes no predicate term at any depth.
     * {@link CosmosPatchOperations#increment} is evaluated server-side inside the
     * atomic write, so N concurrent increments all land, matching DynamoDB's
     * {@code SET x = x + :v} and Spanner's retryable read-write transaction. A
     * target deleted or retyped <em>between</em> the validating read and the
     * write is rejected by Cosmos's own native increment error; that untyped
     * {@code 400} is not passed through — the adapter re-reads and reclassifies
     * it from current state, so a raced increment reports {@code NOT_FOUND} for a
     * vanished target exactly as DynamoDB's before-image re-read does.
     * <p>
     * That reclassification is evidence-based and does not claim to eliminate the
     * race. If the re-read shows the target present and numeric — the {@code 400}
     * had another cause, or a third writer recreated the field before the re-read
     * — or if the follow-up read itself fails, the original {@code INVALID_REQUEST}
     * mapping stands rather than an unsubstantiated {@code NOT_FOUND}. The
     * residual is bounded to "the cause could not be determined" and no longer
     * covers the ordinary raced-delete case.
     *
     * <h4>Cost</h4>
     * Patch is not a billing guarantee. Request charge depends on account
     * configuration, item shape, and indexing; measure it against a full write
     * for the workload that matters to the application.
     *
     * @param address    the logical database + container
     * @param key        the document key; must identify an existing item
     * @param operations the validated modifications to apply
     * @param options    operation options; {@code ttlSeconds} is intentionally ignored
     * @throws com.multiclouddb.api.MulticloudDbException category {@code NOT_FOUND} for a
     *         missing item or required field, or {@code INVALID_REQUEST} for a
     *         nonnumeric increment target or integral-result overflow, or
     *         {@code CONFLICT} when the rejection cannot be attributed to a
     *         deterministic cause. The filter predicate is path-scoped, so a
     *         concurrent write to a field this patch does not address cannot fail
     *         it — but a concurrent write to a field it <em>does</em> address can,
     *         including one that moves an increment target outside the integral
     *         range the predicate bounds. Re-read state is classified first
     *         ({@code classifyRacedPatchRejection}); {@code CONFLICT} is reported
     *         only when that state satisfies every portable precondition, and is
     *         safe to retry
     */
    @Override
    public void patch(ResourceAddress address, MulticloudDbKey key, List<PatchOperation> operations,
            OperationOptions options) {
        checkOpen(OperationNames.PATCH);
        validatePatchRequest(operations);
        String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
        PartitionKey pk = resolvePartitionKey(key);
        // Pure function of the request, so the catch block can tell a failed
        // path-scoped precondition apart from a native Cosmos rejection.
        String filterPredicate = patchFilterPredicate(operations);
        try {
            CosmosContainer container = getContainer(address);
            validatePatchState(container, cosmosId, pk, operations);

            CosmosPatchOperations patchOps = CosmosPatchOperations.create();
            for (PatchOperation op : operations) {
                switch (op.type()) {
                    // REPLACE is translated to a native set, which would create a
                    // missing target; the filter predicate asserts the addressed
                    // path exists so the portable REPLACE outcome does not rely on
                    // Cosmos's provider-specific missing-path error shape.
                    case SET, REPLACE -> patchOps.set(op.path(), op.value());
                    case REMOVE -> patchOps.remove(op.path());
                    case INCREMENT -> {
                        Number delta = PatchNumericDomain.normalize((Number) op.value());
                        if (delta instanceof Long integralDelta) {
                            patchOps.increment(op.path(), integralDelta);
                        } else {
                            patchOps.increment(op.path(), delta.doubleValue());
                        }
                    }
                }
            }

            CosmosPatchItemRequestOptions patchOptions = new CosmosPatchItemRequestOptions();
            if (filterPredicate != null) {
                patchOptions.setFilterPredicate(filterPredicate);
            }

            CosmosItemResponse<ObjectNode> response =
                    container.patchItem(cosmosId, pk, patchOps, patchOptions, ObjectNode.class);
            logItemDiagnostics(OperationNames.PATCH, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.PATCH, address, e);
            if (filterPredicate != null
                    && e.getStatusCode() == CosmosConstants.STATUS_PRECONDITION_FAILED) {
                // No If-Match is ever sent, so the only precondition on the
                // request is the filter predicate. Most terms are IS_DEFINED
                // existence checks, but an INCREMENT also contributes a BETWEEN
                // bound, so a 412 no longer proves a single cause. Re-read and
                // let current state name it, exactly as DynamoDB classifies its
                // own ConditionalCheckFailedException from the before-image.
                // An unprovable 412 is a genuine race, so it reports CONFLICT
                // rather than an invented NOT_FOUND.
                throw classifyRacedPatchRejection(address, cosmosId, pk, operations, e, true);
            }
            if (e.getStatusCode() == CosmosConstants.STATUS_BAD_REQUEST) {
                // Cosmos reports a vanished or retyped native target as an untyped
                // 400. Prove the cause from current state instead of passing it
                // through, mirroring DynamoDB's before-image re-read.
                throw classifyRacedPatchRejection(address, cosmosId, pk, operations, e, false);
            }
            throw CosmosErrorMapper.map(e, OperationNames.PATCH);
        }
    }

    /**
     * Whether the adapter must point-read the document before writing.
     * <p>
     * Required for every operation that the portable contract says must find an
     * existing target ({@code REPLACE}, {@code REMOVE}, {@code INCREMENT}) and
     * for nested paths, whose parent must already exist. Cosmos reports all of
     * those natively as an untyped {@code 400}, so without this read a missing
     * {@code INCREMENT} target would surface as {@code INVALID_REQUEST} where
     * DynamoDB and Spanner both report {@code NOT_FOUND}, and an integral-result
     * overflow would not be detected at all.
     */
    static boolean needsPatchStateValidation(List<PatchOperation> operations) {
        for (PatchOperation operation : operations) {
            if (operation.requiresExistingPath() || operation.isNested()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the server-side, path-scoped precondition for the write, or returns
     * {@code null} when the write needs none.
     * <p>
     * Only operations whose native Cosmos translation cannot enforce the portable
     * contract by itself contribute a term, and every term is an
     * {@code IS_DEFINED} existence check over exactly the path that operation
     * addresses ({@code REPLACE} / {@code REMOVE}: the target itself; a nested
     * {@code SET}: its parent, which patch must not create).
     * <p>
     * The predicate is deliberately <em>path-scoped</em> rather than an
     * item-scoped {@code If-Match} ETag. An ETag fails on any concurrent mutation
     * of the item, so two threads patching disjoint fields would collide on
     * Cosmos while DynamoDB's {@code attribute_exists(...)} condition and
     * Spanner's auto-retried read-write transaction both let them through.
     * <p>
     * {@code INCREMENT} contributes an existence term plus, for an integral
     * delta, a {@code BETWEEN} bound on the current value. The bound is the
     * Cosmos spelling of the {@code BETWEEN} condition DynamoDB attaches to its
     * own increment: it keeps the portable signed 64-bit result range enforced
     * atomically with the write, because a concurrent writer can raise the
     * counter between the validating pre-read and the native increment and make
     * the same delta overflow. Without it Cosmos would silently store a value
     * DynamoDB rejects. A fractional delta contributes no bound, matching
     * {@link PatchNumericDomain#isIntegralResultOutsideRange}, which only
     * constrains integral results. A term that fails is re-proved from current
     * state by {@link #classifyRacedPatchRejection}.
     *
     * @return a Cosmos conditional-patch predicate of the form
     *         {@code FROM c WHERE ...}, or {@code null} for an unconditional write
     */
    static String patchFilterPredicate(List<PatchOperation> operations) {
        StringJoiner terms = new StringJoiner(" AND ");
        for (PatchOperation operation : operations) {
            int depth = requiredPathDepth(operation);
            if (depth > 0) {
                terms.add("IS_DEFINED(" + cosmosPropertyAccessor(operation.pathSegments(), depth) + ")");
            }
            if (operation.type() == PatchOperation.Type.INCREMENT) {
                addIntegralResultBounds(operation, terms);
            }
        }
        return terms.length() == 0 ? null : "FROM c WHERE " + terms;
    }

    /**
     * Adds the {@code BETWEEN} bound that keeps an {@code INCREMENT} result inside
     * the portable signed 64-bit domain, mirroring the condition
     * {@code DynamoProviderClient.addIntegralResultBounds} attaches to its own
     * update. The bounds are computed from the delta alone, so the check is a
     * pure function of the request and stays valid however the counter moves.
     * A fractional delta adds nothing: the portable domain only bounds integral
     * results.
     */
    private static void addIntegralResultBounds(PatchOperation operation, StringJoiner terms) {
        Number delta = PatchNumericDomain.normalize((Number) operation.value());
        if (!(delta instanceof Long integralDelta)) {
            return;
        }
        String accessor = cosmosPropertyAccessor(operation.pathSegments(),
                operation.pathSegments().size());
        terms.add("(" + accessor + " BETWEEN "
                + PatchNumericDomain.minimumBaseForIntegralDelta(integralDelta) + " AND "
                + PatchNumericDomain.maximumBaseForIntegralDelta(integralDelta) + ")");
    }

    /**
     * How many leading segments of an operation's path must already exist for it
     * to satisfy the portable contract: the whole path when the target itself
     * must exist, the parent for a nested {@code SET} (patch never creates
     * intermediate objects), and nothing for a top-level {@code SET}.
     * <p>
     * Shared by the pre-read validation and the filter predicate so the
     * client-side classification and the server-side precondition cannot drift.
     */
    private static int requiredPathDepth(PatchOperation operation) {
        if (operation.requiresExistingPath()) {
            return operation.pathSegments().size();
        }
        return operation.isNested() ? operation.pathSegments().size() - 1 : 0;
    }

    /**
     * Renders a document path as a Cosmos SQL quoted property accessor
     * ({@code c["address"]["city"]}). Quoted rather than dotted because a patch
     * segment may legally contain characters a bare identifier cannot, and the
     * segment is escaped so it can never terminate the literal and alter the
     * predicate.
     */
    private static String cosmosPropertyAccessor(List<String> segments, int segmentCount) {
        StringBuilder accessor = new StringBuilder("c");
        for (int index = 0; index < segmentCount; index++) {
            accessor.append("[\"").append(escapeSqlStringLiteral(segments.get(index))).append("\"]");
        }
        return accessor.toString();
    }

    private static String escapeSqlStringLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Point-reads and validates the pre-write state when required. The read is
     * what lets Cosmos report the same portable category as DynamoDB and Spanner
     * for the deterministic (non-raced) failures; the filter predicate closes the
     * window between this read and the write.
     */
    private static void validatePatchState(CosmosContainer container,
            String cosmosId, PartitionKey partitionKey, List<PatchOperation> operations) {
        if (!needsPatchStateValidation(operations)) {
            return;
        }
        ObjectNode document;
        try {
            document = container.readItem(cosmosId, partitionKey,
                    new CosmosItemRequestOptions(), ObjectNode.class).getItem();
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.PATCH);
        }
        if (document == null) {
            throw patchFailure(MulticloudDbErrorCategory.NOT_FOUND,
                    "Patch target document does not exist");
        }
        validatePatchPreconditionState(document, operations);
    }

    /**
     * Reclassifies an untyped Cosmos {@code 400} from current state instead of
     * letting it through as {@code INVALID_REQUEST}.
     * <p>
     * A native {@code increment} carries preconditions the filter predicate
     * cannot express — the target must exist and be numeric — and Cosmos reports
     * a violation of either as a bare {@code 400}. Passing that through would
     * report {@code INVALID_REQUEST} where DynamoDB (which re-reads the
     * before-image) and Spanner (which sees true state inside its transaction)
     * both report {@code NOT_FOUND} for a target that vanished after the
     * validating read. The re-read is evidence-based: the portable category is
     * only changed when the current document actually proves the cause,
     * otherwise the original mapping stands.
     */
    private MulticloudDbException classifyRacedPatchRejection(ResourceAddress address,
            String cosmosId, PartitionKey partitionKey, List<PatchOperation> operations,
            CosmosException original, boolean conditionFailure) {
        ObjectNode current;
        try {
            current = getContainer(address).readItem(cosmosId, partitionKey,
                    new CosmosItemRequestOptions(), ObjectNode.class).getItem();
        } catch (CosmosException followUpFailure) {
            if (followUpFailure.getStatusCode() == CosmosConstants.STATUS_NOT_FOUND) {
                return patchFailure(MulticloudDbErrorCategory.NOT_FOUND,
                        "Patch target document does not exist", original);
            }
            // The rejected write remains authoritative. A failed follow-up read
            // cannot prove a cause, so callers keep the original classification
            // rather than an invented one.
            return unprovenPatchRejection(original, conditionFailure);
        }
        if (current == null) {
            return patchFailure(MulticloudDbErrorCategory.NOT_FOUND,
                    "Patch target document does not exist", original);
        }
        try {
            validatePatchPreconditionState(current, operations);
        } catch (MulticloudDbException proven) {
            // Same classifier as the pre-read, so a raced failure and a
            // deterministic one report the identical portable category.
            return patchFailure(proven.error().category(), proven.error().message(), original);
        }
        // Current state satisfies every portable precondition, so the rejection
        // had some other cause. Do not invent a NOT_FOUND.
        return unprovenPatchRejection(original, conditionFailure);
    }

    /**
     * Classification for a rejection whose cause current state does not prove.
     * <p>
     * A failed filter predicate had a real precondition falsified server-side, so
     * state that now satisfies every term means the request lost a race — the same
     * CONFLICT DynamoDB reports when its before-image explains nothing. Any other
     * rejection keeps the provider mapping rather than an invented category.
     */
    private static MulticloudDbException unprovenPatchRejection(CosmosException original,
            boolean conditionFailure) {
        return conditionFailure
                ? patchFailure(MulticloudDbErrorCategory.CONFLICT,
                        "Patch condition changed concurrently", original)
                : CosmosErrorMapper.map(original, OperationNames.PATCH);
    }

    /**
     * Checks the state read before the native mutation. Package-visible so the
     * classification matrix can be unit-tested without an emulator or a Cosmos
     * SDK mock.
     */
    static void validatePatchPreconditionState(ObjectNode document,
            List<PatchOperation> operations) {
        for (PatchOperation op : operations) {
            int segmentCount = requiredPathDepth(op);
            if (segmentCount > 0
                    && jsonPathValue(document, op.pathSegments(), segmentCount) == null) {
                throw patchFailure(MulticloudDbErrorCategory.NOT_FOUND,
                        "Patch target field does not exist: '" + op.path() + "'");
            }

            if (op.type() == PatchOperation.Type.INCREMENT) {
                JsonNode current = jsonPathValue(document, op.pathSegments(),
                        op.pathSegments().size());
                if (current == null) {
                    throw patchFailure(MulticloudDbErrorCategory.NOT_FOUND,
                            "Patch target field does not exist: '" + op.path() + "'");
                }
                if (!current.isNumber()) {
                    throw patchFailure(MulticloudDbErrorCategory.INVALID_REQUEST,
                            "INCREMENT target is not numeric: '" + op.path() + "'");
                }
                try {
                    PatchNumericDomain.add(current.numberValue(), (Number) op.value());
                } catch (IllegalArgumentException e) {
                    throw patchFailure(MulticloudDbErrorCategory.INVALID_REQUEST,
                            "INCREMENT target '" + op.path() + "' is outside the portable numeric "
                                    + "domain: " + e.getMessage());
                }
            }
        }
    }

    private static MulticloudDbException patchFailure(MulticloudDbErrorCategory category,
            String message) {
        return new MulticloudDbException(new MulticloudDbError(
                category,
                message,
                ProviderId.COSMOS,
                OperationNames.PATCH,
                false,
                Map.of()));
    }

    /**
     * Portable classification the adapter proved itself, carrying the rejecting
     * Cosmos request's diagnostics so the raw status and activity id are not lost
     * behind the reclassified category. Mirrors DynamoDB's
     * {@code patchConditionFailure}.
     */
    private static MulticloudDbException patchFailure(MulticloudDbErrorCategory category,
            String message, CosmosException cause) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("subStatusCode", String.valueOf(cause.getSubStatusCode()));
        if (cause.getActivityId() != null) {
            details.put("requestId", cause.getActivityId());
        }
        details.put("requestCharge", String.valueOf(cause.getRequestCharge()));
        return new MulticloudDbException(new MulticloudDbError(
                category,
                message + ": " + cause.getMessage(),
                ProviderId.COSMOS,
                OperationNames.PATCH,
                false,
                cause.getStatusCode(),
                details), cause);
    }

    private static JsonNode jsonPathValue(JsonNode document, List<String> segments, int segmentCount) {
        JsonNode current = document;
        for (int index = 0; index < segmentCount; index++) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(segments.get(index));
        }
        return current;
    }

    /**
     * Deletes a document by its composite key.
     * <p>
     * Idempotent: a 404 (item not found) response is treated as success and the
     * call returns silently. This matches the natural behaviour of DynamoDB
     * {@code DeleteItem} and Spanner {@code Mutation.delete}, giving the SDK a
     * portable LCD contract: deleting a missing key is a no-op on every backend.
     * All other Cosmos errors are mapped via {@link CosmosErrorMapper}.
     *
     * @param address the logical database + container
     * @param key     the document key identifying the item to delete
     * @param options operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any non-404 Cosmos error
     */
    @Override
    public void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.DELETE);
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            CosmosItemResponse<Object> response = container.deleteItem(cosmosId, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.DELETE, address, response);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return;
            }
            logExceptionDiagnostics(OperationNames.DELETE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    /**
     * Executes a query and returns a single page of results.
     * <p>
     * Query routing logic (evaluated in order):
     * <ol>
     *   <li>If {@link QueryRequest#nativeExpression()} is set, it is used as-is as the
     *       Cosmos SQL string (native passthrough).</li>
     *   <li>If {@link QueryRequest#expression()} is set, it is used as the Cosmos SQL
     *       WHERE expression.</li>
     *   <li>If neither is set, {@code SELECT * FROM c} is used (full container scan).</li>
     * </ol>
     * Named parameters ({@code @name} syntax) from {@link QueryRequest#parameters()} are
     * bound as {@link SqlParameter} values. Parameter names that do not already start with
     * {@code @} are prefixed automatically.
     * <p>
     * If {@link QueryRequest#partitionKey()} is set, the query is scoped to a single
     * logical partition via {@link CosmosQueryRequestOptions#setPartitionKey}, avoiding
     * a cross-partition fan-out.
     * <p>
     * Only the first page of results is returned; pass the returned
     * {@link QueryPage#continuationToken()} in the next request to page forward.
     *
     * @param address the logical database + container to query
     * @param query   query request containing expression, parameters, page size, and
     *                optional continuation token
     * @param options operation options (currently unused by this provider)
     * @return a page of results; {@link QueryPage#continuationToken()} is non-null when
     *         more pages are available
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos query error
     */
    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        checkOpen(OperationNames.QUERY);
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.maxPageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.maxPageSize());
            }

            String expression = query.nativeExpression() != null ? query.nativeExpression() : query.expression();
            if (expression == null || expression.isBlank()) {
                expression = CosmosConstants.QUERY_SELECT_ALL;
            }

            // Apply TOP N and ORDER BY for non-native expressions
            if (query.nativeExpression() == null) {
                expression = applyResultSetControl(expression, query);
            }

            List<SqlParameter> sqlParams = new ArrayList<>();
            if (query.parameters() != null) {
                for (Map.Entry<String, Object> entry : query.parameters().entrySet()) {
                    String paramName = entry.getKey().startsWith(CosmosConstants.QUERY_PARAM_PREFIX)
                            ? entry.getKey()
                            : CosmosConstants.QUERY_PARAM_PREFIX + entry.getKey();
                    sqlParams.add(new SqlParameter(paramName, entry.getValue()));
                }
            }

            SqlQuerySpec sqlQuery = new SqlQuerySpec(expression, sqlParams);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<Map<String, Object>> items = new ArrayList<>();
            String continuationToken = null;
            java.time.Instant queryStart = java.time.Instant.now();

            Iterable<FeedResponse<JsonNode>> pages;
            if (query.continuationToken() != null) {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(query.continuationToken(), pageSize);
            } else {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(pageSize);
            }

            for (FeedResponse<JsonNode> page : pages) {
                for (JsonNode item : page.getResults()) {
                    items.add(toMap(item));
                }
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY, address, page,
                        items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            OperationDiagnostics emptyDiag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, OperationNames.QUERY,
                            java.time.Duration.between(queryStart, java.time.Instant.now()))
                    .itemCount(0).build();
            return new QueryPage(items, continuationToken, emptyDiag);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.QUERY, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Executes a pre-translated portable query and returns a single page of results.
     * <p>
     * Called by {@link com.multiclouddb.api.internal.DefaultMulticloudDbClient} after
     * the portable expression has been parsed, validated, and translated into Cosmos SQL
     * by {@link CosmosExpressionTranslator}. Named parameters from
     * {@link TranslatedQuery#namedParameters()} are bound directly as
     * {@link SqlParameter} values.
     * <p>
     * If {@link QueryRequest#partitionKey()} is set the query is scoped to a single
     * partition, consistent with {@link #query}.
     *
     * @param address    the logical database + container to query
     * @param translated the Cosmos SQL string and bound named parameters produced by the
     *                   expression translator
     * @param query      the original query request (used for page size, continuation
     *                   token, and partition key)
     * @param options    operation options (currently unused by this provider)
     * @return a page of results with an optional continuation token
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos query error
     */
    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        checkOpen(OperationNames.QUERY_WITH_TRANSLATION);
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.maxPageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.maxPageSize());
            }

            List<SqlParameter> sqlParams = new ArrayList<>();
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                sqlParams.add(new SqlParameter(entry.getKey(), entry.getValue()));
            }

            String sql = applyResultSetControl(translated.queryString(), query);
            SqlQuerySpec sqlQuery = new SqlQuerySpec(sql, sqlParams);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<Map<String, Object>> items = new ArrayList<>();
            String continuationToken = null;
            java.time.Instant queryStart = java.time.Instant.now();

            Iterable<FeedResponse<JsonNode>> pages;
            if (query.continuationToken() != null) {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(query.continuationToken(), pageSize);
            } else {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(pageSize);
            }

            for (FeedResponse<JsonNode> page : pages) {
                for (JsonNode item : page.getResults()) {
                    items.add(toMap(item));
                }
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address,
                        page, items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            OperationDiagnostics emptyDiag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, OperationNames.QUERY,
                            java.time.Duration.between(queryStart, java.time.Instant.now()))
                    .itemCount(0).build();
            return new QueryPage(items, continuationToken, emptyDiag);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return CosmosCapabilities.CAPABILITIES;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    public void close() {
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
            cosmosClient.close();
        }
    }

    /**
     * Guards public entry points against use after {@link #close()}.
     * <p>
     * Provider-level mirror of the facade guard in
     * {@code DefaultMulticloudDbClient.checkOpen(String)}: callers that talk
     * directly to the SPI (e.g., conformance harnesses, test fixtures) still
     * see a typed {@link MulticloudDbErrorCategory#CLIENT_CLOSED} envelope
     * rather than a raw {@code IllegalStateException} from azure-cosmos.
     *
     * @param operation the caller's operation name from {@link OperationNames}
     */
    private void checkOpen(String operation) {
        if (closed) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CLIENT_CLOSED,
                    "CosmosProviderClient has been closed",
                    ProviderId.COSMOS, operation, false, Map.of()));
        }
    }

    // ── Change Feed ──────────────────────────────────────────────────────────

    @Override
    public java.util.List<com.multiclouddb.api.changefeed.ChangeFeedCursor> listCursors(
            ResourceAddress address) {
        checkOpen(OperationNames.LIST_CURSORS);
        CosmosContainer container = getContainer(address);
        return changeFeedReader.listCursors(container, address);
    }

    @Override
    public com.multiclouddb.api.changefeed.ChangeFeedPage readChanges(
            ResourceAddress address,
            com.multiclouddb.api.changefeed.ChangeFeedCursor cursor,
            OperationOptions options) {
        checkOpen(OperationNames.READ_CHANGES);
        CosmosContainer container = getContainer(address);
        return changeFeedReader.readChanges(container, address, cursor, options);
    }

    // ── Provisioning ─────────────────────────────────────────────────────────

    /**
     * Creates the Cosmos DB database if it does not already exist (idempotent).
     * <p>
     * Uses the data-plane {@code createDatabaseIfNotExists} call. No management
     * or ARM SDK dependency is required.
     * <p>
     * <b>Permission requirement:</b> The caller must hold a role that permits
     * control-plane database creation (e.g., <em>Cosmos DB Operator</em> or
     * key-based authentication). When operating under a data-plane-only RBAC role
     * (e.g., <em>Cosmos DB Built-in Data Contributor</em>), this call will fail
     * with a {@code PERMISSION_DENIED} error. In that case provision the database
     * out-of-band (Azure Portal, CLI, or Bicep/Terraform) and do not call this
     * method.
     *
     * @param database the logical database name to create if absent
     * @throws MulticloudDbException with category {@code PERMISSION_DENIED} when the
     *                               caller lacks control-plane permissions, or
     *                               category {@code INTERNAL_ERROR} for other failures
     */
    @Override
    public void ensureDatabase(String database) {
        checkOpen(OperationNames.ENSURE_DATABASE);
        try {
            cosmosClient.createDatabaseIfNotExists(database);
            LOG.info("ensureDatabase: created or verified Cosmos database '{}'", database);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.ENSURE_DATABASE);
        }
    }

    /**
     * Creates the Cosmos DB container if it does not already exist (idempotent).
     * <p>
     * The container is always created with partition key path
     * {@code /partitionKey}, matching the system field injected by the CRUD
     * methods.
     *
     * @param address the logical database + container address
     * @throws MulticloudDbException if creation fails
     */
    @Override
    public void ensureContainer(ResourceAddress address) {
        checkOpen(OperationNames.ENSURE_CONTAINER);
        try {
            CosmosDatabase db = cosmosClient.getDatabase(address.database());
            CosmosContainerProperties props = new CosmosContainerProperties(
                    address.collection(), CosmosConstants.PARTITION_KEY_PATH);
            // If the user opted in to extended change-feed retention via
            // MulticloudDbClientConfig.builder().changeFeed(ChangeFeedConfig
            // .builder().extendedRetention(...).build()), provision the
            // container with an AVAD ChangeFeedPolicy carrying that retention.
            // The build-time capability gate in MulticloudDbClientFactory
            // guarantees we only get here if the provider declared
            // EXTENDED_CHANGE_FEED_HISTORY_CAP, so the policy is always safe
            // to set when hasExtendedRetention() is true.
            boolean optIn = config.changeFeed().hasExtendedRetention();
            Duration requestedRetention = optIn
                    ? config.changeFeed().extendedRetention().orElseThrow()
                    : null;
            if (optIn) {
                props.setChangeFeedPolicy(
                        ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(requestedRetention));
                LOG.info("ensureContainer: provisioning Cosmos container '{}/{}' with "
                                + "AVAD ChangeFeedPolicy retention={}",
                        address.database(), address.collection(), requestedRetention);
            }
            db.createContainerIfNotExists(props);
            LOG.info("ensureContainer: created or verified Cosmos container '{}/{}'",
                    address.database(), address.collection());

            // Cosmos's createContainerIfNotExists is a no-op when the container
            // already exists — props (including the new ChangeFeedPolicy) is
            // silently discarded. Under the opt-in path we must read back the
            // container's active policy and refuse silently honouring an
            // already-existing non-AVAD or weaker-retention container. Cosmos
            // has no public SDK API to update an existing container's
            // ChangeFeedPolicy in place, so the correct behaviour is to throw
            // UNSUPPORTED_CAPABILITY(reason=extended_retention_not_enacted)
            // with both requested and active retention values so the operator
            // can drop-and-recreate or roll back the opt-in.
            if (optIn) {
                CosmosContainer existing = db.getContainer(address.collection());
                CosmosContainerProperties active = existing.read().getProperties();
                ChangeFeedPolicy activePolicy = active.getChangeFeedPolicy();
                // Coalesce BOTH null axes:
                //   (a) activePolicy == null  →  no ChangeFeedPolicy at all
                //   (b) activePolicy != null  →  AVAD-retention getter returns
                //       null when the policy is LATEST_VERSION (the Cosmos
                //       Java SDK's convention for "this getter is not
                //       applicable to this policy mode")
                // Without (b), a container created with the historical pre-
                // AVAD default policy throws NullPointerException on
                // activeAvadRetention.toString() / Map.of(...) below, leaking
                // a provider-specific exception type through the portable
                // surface instead of the documented UNSUPPORTED_CAPABILITY
                // envelope.
                Duration activeAvadRetention = activePolicy == null
                        ? null
                        : activePolicy.getRetentionDurationForAllVersionsAndDeletesPolicy();
                if (!requestedRetention.equals(activeAvadRetention)) {
                    String activeDescription;
                    if (activePolicy == null) {
                        activeDescription = "no ChangeFeedPolicy at all";
                    } else if (activeAvadRetention == null) {
                        activeDescription = "a non-AVAD ChangeFeedPolicy "
                                + "(LATEST_VERSION — the historical default)";
                    } else {
                        activeDescription = "an AVAD ChangeFeedPolicy with retention="
                                + activeAvadRetention;
                    }
                    throw new MulticloudDbException(new MulticloudDbError(
                            MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            "Cosmos container '" + address.database() + "/" + address.collection()
                                    + "' already exists with " + activeDescription + ". "
                                    + "Cosmos cannot update an existing container's ChangeFeedPolicy "
                                    + "in place — ensureContainer cannot enact the requested "
                                    + "extendedRetention(" + requestedRetention + ") without "
                                    + "dropping and recreating the container. Drop the container "
                                    + "(losing data!) and re-run ensureContainer, or revert to "
                                    + (activeAvadRetention != null
                                            ? "ChangeFeedConfig.extendedRetention(" + activeAvadRetention + ")."
                                            : "the default ChangeFeedConfig (no extended retention)."),
                            ProviderId.COSMOS, OperationNames.ENSURE_CONTAINER, false,
                            // String.valueOf is null-safe so Map.of never sees null.
                            // "capability" mirrors the factory and Dynamo gates so
                            // observability consumers grouping by providerDetails.capability
                            // never see null on this failure path.
                            Map.of("reason", "extended_retention_not_enacted",
                                    "capability", Capability.EXTENDED_CHANGE_FEED_HISTORY,
                                    "requestedRetention", requestedRetention.toString(),
                                    "activeRetention", String.valueOf(activeAvadRetention))));
                }
            }
        } catch (CosmosException e) {
            // Only consult the continuous-backup fingerprint when the caller
            // actually opted in to extended retention. Without this gate, v1
            // callers can see UNSUPPORTED_CAPABILITY where they used to see
            // INVALID_REQUEST for unrelated 400s that mention PITR / continuous
            // backup — breaking the "bit-for-bit identical to v1" guarantee
            // documented for callers that never touch ChangeFeedConfig.
            if (config.changeFeed().hasExtendedRetention()) {
                MulticloudDbException normalized =
                        maybeContinuousBackupRequired(e, OperationNames.ENSURE_CONTAINER);
                if (normalized != null) throw normalized;
            }
            throw CosmosErrorMapper.map(e, OperationNames.ENSURE_CONTAINER);
        }
    }

    /**
     * Re-maps a Cosmos 400 BadRequest whose message fingerprint indicates the
     * account does not have Continuous Backup enabled (a prerequisite for AVAD
     * change-feed policies with >7d retention) into a portable
     * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY UNSUPPORTED_CAPABILITY}
     * envelope tagged {@code reason=continuous_backup_required}.
     * <p>
     * Without this re-mapping a callers would see a generic INVALID_REQUEST
     * and have to substring-match the message to disambiguate provisioning
     * failures from genuine input validation. Returns {@code null} if the
     * exception does not match the fingerprint, so the caller falls through
     * to the generic mapper.
     */
    private MulticloudDbException maybeContinuousBackupRequired(CosmosException e, String operation) {
        if (e.getStatusCode() != 400) return null;
        String msg = e.getMessage();
        if (msg == null) return null;
        String lower = msg.toLowerCase(Locale.ROOT);
        boolean fingerprint = false;
        for (String needle : CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS) {
            if (lower.contains(needle)) { fingerprint = true; break; }
        }
        if (!fingerprint) return null;
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                "Cosmos account does not have Continuous Backup enabled, which is required "
                        + "for AVAD ChangeFeedPolicy (the basis for portable change-feed history "
                        + "beyond the 24h baseline). Enable Continuous Backup (7d or 30d tier) on "
                        + "the account; the tier ceiling caps the maximum value you can pass to "
                        + "ChangeFeedConfig.extendedRetention(...). Underlying message: " + msg,
                ProviderId.COSMOS, operation, false,
                Map.of("reason", "continuous_backup_required",
                        "statusCode", String.valueOf(e.getStatusCode()))), e);
    }

    /**
     * Returns the {@link CosmosContainer} handle for the given resource address.
     * Does not make a network call — the Cosmos SDK resolves the container lazily.
     *
     * @param address the logical database + container
     * @return a live {@link CosmosContainer} reference
     */
    private CosmosContainer getContainer(ResourceAddress address) {
        CosmosDatabase database = cosmosClient.getDatabase(address.database());
        return database.getContainer(address.collection());
    }

    /**
     * Resolves the Cosmos DB {@link PartitionKey} from the portable {@link MulticloudDbKey}.
     * The partition key value is always {@code key.partitionKey()}.
     *
     * @param key the portable document key
     * @return the Cosmos SDK partition key object
     */
    private PartitionKey resolvePartitionKey(MulticloudDbKey key) {
        return new PartitionKey(key.partitionKey());
    }

    /**
     * Returns {@code true} if the SQL string already contains a syntactic
     * {@code ORDER BY} clause.
     * <p>
     * Uses a word-boundary regex rather than a plain {@code contains()} call so that
     * string literals in the query (e.g. {@code WHERE c.note = 'place order by friday'})
     * do not produce false positives. String literals are stripped before the check via
     * {@link #stripStringLiterals(String)}, which correctly handles SQL-escaped quotes
     * (doubled single quotes: {@code ''}).
     */
    private static final Pattern ORDER_BY_PATTERN =
            Pattern.compile("(?i)\\bORDER\\s+BY\\b");

    /**
     * Matches aggregate functions that Cosmos DB forbids combining with ORDER BY.
     * Covers: COUNT, SUM, MIN, MAX, AVG — both plain and SELECT VALUE variants.
     */
    private static final Pattern AGGREGATE_PATTERN =
            Pattern.compile("(?i)\\b(COUNT|SUM|MIN|MAX|AVG)\\s*\\(|\\bGROUP\\s+BY\\b");

    private static boolean containsOrderBy(String sql) {
        return ORDER_BY_PATTERN.matcher(stripStringLiterals(sql)).find();
    }

    private static boolean containsAggregate(String sql) {
        return AGGREGATE_PATTERN.matcher(stripStringLiterals(sql)).find();
    }

    /**
     * Matches SQL single-quoted string literals, including those that contain
     * SQL-escaped single quotes (doubled: {@code ''}).
     * <p>
     * Pattern: {@code '(?:[^']|'')*'} — matches the opening quote, then zero or
     * more of either a non-quote character or a doubled-quote escape, then the
     * closing quote. This correctly handles {@code 'it''s order by'} as a single
     * token, preventing {@code s order by'} from leaking into keyword detection.
     * <p>
     * Pre-compiled as a {@code static final} field to avoid per-call regex
     * compilation overhead.
     */
    private static final Pattern STRING_LITERAL_PATTERN =
            Pattern.compile("'(?:[^']|'')*'");

    /**
     * Replaces all single-quoted string literals in a SQL fragment with empty
     * placeholders so that keyword detection is not confused by literal content.
     * Uses the pre-compiled {@link #STRING_LITERAL_PATTERN}.
     */
    private static String stripStringLiterals(String sql) {
        return STRING_LITERAL_PATTERN.matcher(sql).replaceAll("''");
    }

    /**
     * Applies TOP N (limit) and ORDER BY from the query request to a Cosmos SQL string.
     * <p>
     * For TOP N: rewrites {@code SELECT} to {@code SELECT TOP N} when limit is set.
     * <p>
     * For ORDER BY: appends {@code ORDER BY c.field ASC/DESC} when an explicit
     * {@code orderBy} is set, or appends {@code ORDER BY c.id ASC} as a default for
     * all queries without an explicit order (to match DynamoDB's implicit sort behavior).
     * <p>
     * The default {@code ORDER BY} is <b>skipped</b> when:
     * <ul>
     *   <li>The SQL already contains an {@code ORDER BY} clause (idempotency guard —
     *       uses a word-boundary regex so string literals containing "order by" are
     *       not mistakenly detected).</li>
     *   <li>The SQL contains an aggregate function ({@code COUNT}, {@code SUM},
     *       {@code MIN}, {@code MAX}, {@code AVG}) or a {@code GROUP BY} clause —
     *       Cosmos DB rejects {@code ORDER BY} on aggregate queries.</li>
     * </ul>
     * <p>
     * Package-private and static for unit testing.
     */
    static String applyResultSetControl(String sql, QueryRequest query) {
        String result = sql;

        // Apply TOP N — rewrite SELECT to SELECT TOP N using a boolean flag to
        // track success, avoiding false positives from field names that contain
        // the substring "TOP" (e.g. "topic", "topology", "stopper").
        if (query.limit() != null) {
            boolean topApplied = false;

            // Pattern 1: "SELECT VALUE c ..." — Cosmos scalar projection
            String r1 = result.replaceFirst("(?i)^SELECT\\s+VALUE\\s+c\\b",
                    "SELECT TOP " + query.limit() + " VALUE c");
            if (!r1.equals(result)) {
                result = r1;
                topApplied = true;
            }

            // Pattern 2: "SELECT * ..." — full document projection
            if (!topApplied) {
                String r2 = result.replaceFirst("(?i)^SELECT\\s+\\*",
                        "SELECT TOP " + query.limit() + " *");
                if (!r2.equals(result)) {
                    result = r2;
                    topApplied = true;
                }
            }

            // Pattern 3: any other SELECT (custom projections, aliases, etc.)
            if (!topApplied) {
                result = result.replaceFirst("(?i)^SELECT\\b",
                        "SELECT TOP " + query.limit());
            }
        }

        // Apply ORDER BY
        if (query.orderBy() != null && !query.orderBy().isEmpty()) {
            StringBuilder orderClause = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < query.orderBy().size(); i++) {
                SortOrder so = query.orderBy().get(i);
                if (i > 0) orderClause.append(", ");
                // Reuse the translator's bracket accessor: a bare `c.<name>` collides
                // with Cosmos NoSQL reserved words, so ORDER BY on a field named
                // `value` was rejected outright while the same portable query
                // succeeded on Spanner.
                orderClause.append(CosmosExpressionTranslator.fieldRef(so.field()))
                        .append(' ').append(so.direction().name());
            }
            result = result + orderClause;
        } else if (!containsOrderBy(result) && !containsAggregate(result)) {
            // DynamoDB Query implicitly sorts by range key within a partition; DynamoDB
            // Scan sorts per-page in memory. Cosmos has no implicit ordering, so always
            // append ORDER BY c.id ASC to ensure sorted results on every query.
            // For single-page results both providers return identically sorted output.
            // For multi-page cross-partition queries Cosmos is globally sorted server-side,
            // which is strictly better than DynamoDB's per-page sort — this is a documented
            // capability difference, not a bug.
            // Guards:
            //  1. Skip if SQL already has ORDER BY (prevents double-ORDER BY on native exprs).
            //     Uses word-boundary regex — plain contains() would falsely match string
            //     literals like WHERE c.note = 'place order by friday'.
            //  2. Skip if SQL is an aggregate query (COUNT/SUM/MIN/MAX/AVG, GROUP BY) —
            //     Cosmos DB rejects ORDER BY on aggregate expressions at runtime.
            result = result + " ORDER BY c.id ASC";
        }

        return result;
    }

    /**
     * Logs per-operation diagnostics from a {@link CosmosItemResponse} at DEBUG
     * level: activity ID (request correlation), request charge (RU cost), and
     * HTTP status code.
     */
    private void logItemDiagnostics(String operation, ResourceAddress address,
            CosmosItemResponse<?> response) {
        CosmosDiagnosticsLogger.logItem(operation, address, response);
    }

    private void logFeedDiagnostics(String operation, ResourceAddress address,
            FeedResponse<?> page, int itemCount) {
        CosmosDiagnosticsLogger.logFeed(operation, address, page, itemCount);
    }

    private void logExceptionDiagnostics(String operation, ResourceAddress address,
            CosmosException e) {
        CosmosDiagnosticsLogger.logException(operation, address, e);
    }

    /**
     * Builds {@link OperationDiagnostics} from a {@link FeedResponse}, logs them
     * at DEBUG level, and logs full native diagnostics when opted-in via config.
     */
    private OperationDiagnostics buildFeedDiagnostics(String operation, ResourceAddress address,
            FeedResponse<?> page, int itemCount, java.time.Duration duration) {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.COSMOS, operation, duration)
                .requestCharge(page.getRequestCharge())
                .itemCount(itemCount)
                .build();

        LOG.debug("cosmos.diagnostics op={} db={} col={} requestCharge={} itemCount={} hasMore={}",
                operation, address.database(), address.collection(),
                diag.requestCharge(), itemCount, page.getContinuationToken() != null);

        if (config.nativeDiagnosticsEnabled()) {
            CosmosDiagnostics native_ = page.getCosmosDiagnostics();
            if (native_ != null) {
                LOG.info("cosmos.native-diagnostics op={} db={} col={} details={}",
                        operation, address.database(), address.collection(), native_);
            }
        }
        return diag;
    }

    /**
     * Converts a caller-supplied {@code Map<String, Object>} document into a Jackson
     * {@link ObjectNode} suitable for Cosmos SDK write calls.
     * Jackson is used as a private implementation detail and does not appear on the
     * public API surface.
     *
     * @param document the document payload
     * @return an {@link ObjectNode} representation of the document
     */
    private ObjectNode toObjectNode(Map<String, Object> document) {
        return MAPPER.convertValue(document, ObjectNode.class);
    }


    /**
     * Converts a Cosmos SDK {@link JsonNode} response item into a plain
     * {@code Map<String, Object>} for return on the public API surface.
     *
     * @param node the JSON node returned by the Cosmos SDK; may be {@code null}
     * @return a map representation, or {@code null} if {@code node} is {@code null}
     */
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null) return null;
        return MAPPER.convertValue(node, MAP_TYPE);
    }
}