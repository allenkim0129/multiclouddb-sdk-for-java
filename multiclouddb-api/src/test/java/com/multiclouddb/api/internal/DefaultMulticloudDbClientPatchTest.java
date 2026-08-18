// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the capability gating and delegation behaviour of
 * {@link DefaultMulticloudDbClient#patch}.
 * <p>
 * Patch is the first operation whose portability depends on <em>two</em>
 * capability gates: {@link Capability#PATCH} for the operation itself, and
 * {@link Capability#NESTED_PATCH} for sub-document paths, which Spanner cannot
 * express. Both must fail fast with {@code UNSUPPORTED_CAPABILITY} rather than
 * reaching an adapter that would silently do something different.
 */
class DefaultMulticloudDbClientPatchTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "coll");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    /**
     * Provider stub whose declared capabilities are supplied per test. Records the
     * operations handed to the SPI so delegation can be asserted; leaves
     * {@code patch} unimplemented when {@code recording} is false, exercising the
     * SPI's default {@code UNSUPPORTED_CAPABILITY} implementation.
     */
    private static class FakeProviderClient implements MulticloudDbProviderClient {
        private final ProviderId pid = ProviderId.fromId("fake-patch-test");
        private final CapabilitySet caps;
        List<PatchOperation> received;
        int writeCalls;
        int validateCalls;

        FakeProviderClient(Capability... declared) {
            this.caps = new CapabilitySet(List.of(declared));
        }

        @Override public ProviderId providerId() { return pid; }
        @Override public CapabilitySet capabilities() { return caps; }

        /** Counts how often the shared portable guard runs for one facade call. */
        @Override
        public void validatePatchRequest(List<PatchOperation> operations) {
            validateCalls++;
            MulticloudDbProviderClient.super.validatePatchRequest(operations);
        }

        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { writeCalls++; throw new UnsupportedOperationException(); }
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { writeCalls++; throw new UnsupportedOperationException(); }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { writeCalls++; throw new UnsupportedOperationException(); }
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }

    /**
     * Stub that implements the SPI patch hook and records what it was given.
     * <p>
     * Calls {@link MulticloudDbProviderClient#validatePatchRequest(List)} as its
     * first statement, exactly as the SPI contract requires and as the Cosmos,
     * DynamoDB, and Spanner adapters do. The facade no longer duplicates that
     * call, so a stub that skipped it would not model any real adapter.
     */
    private static class RecordingProviderClient extends FakeProviderClient {
        RecordingProviderClient(Capability... declared) {
            super(declared);
        }

        @Override
        public void patch(ResourceAddress a, MulticloudDbKey k, List<PatchOperation> ops, OperationOptions o) {
            validatePatchRequest(ops);
            this.received = ops;
        }
    }

    /**
     * Adapter stub that mutates the caller-owned list from inside
     * {@code validatePatchRequest}, standing in for another thread mutating it
     * in the window between validation and execution.
     */
    private static final class RacingCallerProviderClient extends RecordingProviderClient {
        private final List<PatchOperation> callerOwned;

        RacingCallerProviderClient(List<PatchOperation> callerOwned, Capability... declared) {
            super(declared);
            this.callerOwned = callerOwned;
        }

        @Override
        public void validatePatchRequest(List<PatchOperation> operations) {
            super.validatePatchRequest(operations);
            // The instant validation passes, the caller swaps in an operation the
            // validator would have rejected and appends another one.
            callerOwned.set(0, PatchOperation.set("/id", "hijacked"));
            callerOwned.add(PatchOperation.remove("/smuggled"));
        }
    }

    private static MulticloudDbClient clientFor(FakeProviderClient pc) {
        return new DefaultMulticloudDbClient(pc, MulticloudDbClientConfig.builder()
                .provider(pc.providerId())
                .build());
    }

    @Test
    @DisplayName("provider that does not declare PATCH fails with UNSUPPORTED_CAPABILITY")
    void unsupportedWhenCapabilityAbsent() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(Capability.PATCH_UNSUPPORTED);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/a", 1))));
            assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations when the capability gate fails");
        }
    }

    @Test
    @DisplayName("nested path on a provider without NESTED_PATCH fails with UNSUPPORTED_CAPABILITY")
    void unsupportedNestedPath() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_UNSUPPORTED);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/a/b", 1))));
            assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations when the capability gate fails");
        }
    }

    @Test
    @DisplayName("top-level patch still works on a provider without NESTED_PATCH")
    void topLevelPatchAllowedWithoutNestedCapability() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_UNSUPPORTED);
        try (MulticloudDbClient client = clientFor(pc)) {
            List<PatchOperation> ops = List.of(PatchOperation.set("/a", 1));
            client.patch(ADDRESS, KEY, ops);
            assertEquals(ops, pc.received);
        }
    }

    @Test
    @DisplayName("nested path is delegated when NESTED_PATCH is declared")
    void nestedPathDelegatedWhenSupported() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            List<PatchOperation> ops = List.of(PatchOperation.set("/a/b", 1));
            client.patch(ADDRESS, KEY, ops);
            assertEquals(ops, pc.received);
        }
    }

    /**
     * An adapter written before patch existed inherits the SPI default, which must
     * fail predictably instead of compiling to a silent no-op.
     */
    @Test
    @DisplayName("SPI default implementation fails with UNSUPPORTED_CAPABILITY")
    void spiDefaultIsUnsupported() throws Exception {
        FakeProviderClient pc = new FakeProviderClient(Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/a", 1))));
            assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
        }
    }

    @Test
    @DisplayName("contract violations surface as INVALID_REQUEST attributed to patch")
    void validationFailuresAreAttributedToPatch() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/id", "x"))));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations for an invalid request");
        }
    }

    @Test
    @DisplayName("reserved document data field is rejected before every public write dispatch")
    void reservedDataFieldIsRejectedBeforeWriteDispatch() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException create = assertThrows(MulticloudDbException.class,
                    () -> client.create(ADDRESS, KEY, Map.of("data", "reserved")));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, create.error().category());
            assertEquals(OperationNames.CREATE, create.error().operation());

            MulticloudDbException update = assertThrows(MulticloudDbException.class,
                    () -> client.update(ADDRESS, KEY, Map.of("DaTa", "reserved")));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, update.error().category());
            assertEquals(OperationNames.UPDATE, update.error().operation());

            MulticloudDbException upsert = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(ADDRESS, KEY, Map.of("DATA", "reserved")));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, upsert.error().category());
            assertEquals(OperationNames.UPSERT, upsert.error().operation());
            assertEquals(0, pc.writeCalls,
                    "reserved-field validation must run before a provider write is dispatched");
        }
    }

    @Test
    @DisplayName("oversized REMOVE paths fail before the provider is invoked")
    void oversizedRemovePathIsRejectedBeforeDelegation() throws Exception {
        FakeProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        String oversizedPath = "/" + "x".repeat(DocumentSizeValidator.MAX_BYTES);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.remove(oversizedPath))));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations for an oversized REMOVE request");
        }
    }

    /**
     * The SPI contract makes the adapter the single validation point, and all
     * three adapters honour it. A second call from the facade would re-run the
     * request-size check — a full serialization of an operand graph that may
     * approach the 399 KB portable ceiling — on every patch.
     */
    @Test
    @DisplayName("the portable patch guard runs exactly once per facade call")
    void validationIsNotDuplicatedByTheFacade() throws Exception {
        RecordingProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/a", 1)));
            assertEquals(1, pc.validateCalls,
                    "validatePatchRequest must run once, in the adapter, not once per layer");
        }
    }

    /**
     * {@code PatchOperation} is a deeply immutable, cycle-checked graph, but the
     * {@code List} carrying those operations is caller-owned and was re-read by
     * the provider <em>after</em> validation. A caller mutating it concurrently
     * could otherwise slip an operation the validator rejected — here {@code /id},
     * a reserved root — past every check.
     */
    @Test
    @DisplayName("mutating the caller's list after validation cannot change what the provider applies")
    void callerListMutationAfterValidationCannotReachTheProvider() throws Exception {
        List<PatchOperation> callerOwned = new ArrayList<>();
        callerOwned.add(PatchOperation.set("/a", 1));

        RacingCallerProviderClient pc = new RacingCallerProviderClient(callerOwned,
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            client.patch(ADDRESS, KEY, callerOwned);

            assertEquals(2, callerOwned.size(), "the racing mutation must really have happened");
            assertNotNull(pc.received);
            assertEquals(1, pc.received.size(),
                    "the provider must see the snapshot taken at the facade, not the mutated list");
            assertEquals("/a", pc.received.get(0).path(),
                    "the reserved-root operation swapped in after validation must not reach the provider");
            assertThrows(UnsupportedOperationException.class,
                    () -> pc.received.add(PatchOperation.remove("/b")),
                    "the snapshot handed to the provider must be unmodifiable");
        }
    }

    /**
     * {@code List.copyOf} would reject a null list with a raw
     * {@link NullPointerException}, which the facade normalises to
     * {@code PROVIDER_ERROR}. The portable contract says a null operation list is
     * {@code INVALID_REQUEST}, so the snapshot must let null through to the
     * adapter's guard.
     */
    @Test
    @DisplayName("a null operation list is still INVALID_REQUEST, not a wrapped NullPointerException")
    void nullOperationListRemainsInvalidRequest() throws Exception {
        RecordingProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, null));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations for a null request");
        }
    }

    /**
     * {@code List.copyOf} rejects a null <em>element</em> for the same reason it
     * rejects a null list, so the snapshot must tolerate both. Without this the
     * {@link NullPointerException} escapes to the facade's catch-all and is
     * reported as {@code PROVIDER_ERROR}, leaving
     * {@code PatchValidator}'s "must not contain null entries" rule unreachable.
     */
    @Test
    @DisplayName("a null operation entry is INVALID_REQUEST, not a wrapped NullPointerException")
    void nullOperationEntryRemainsInvalidRequest() throws Exception {
        RecordingProviderClient pc = new RecordingProviderClient(
                Capability.PATCH_CAP, Capability.NESTED_PATCH_CAP);
        try (MulticloudDbClient client = clientFor(pc)) {
            List<PatchOperation> operations = new ArrayList<>();
            operations.add(PatchOperation.set("/status", "new"));
            operations.add(null);

            MulticloudDbException e = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, operations));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
            assertEquals(OperationNames.PATCH, e.error().operation());
            assertNull(pc.received, "the adapter must not apply operations for a null entry");
        }
    }
}
