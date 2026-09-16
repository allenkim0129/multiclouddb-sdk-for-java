// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

/**
 * Programmatic limits for portable create, upsert, and partial-update inputs.
 *
 * <p>These limits are enforced by the shared API layer before provider I/O. The
 * serialized JSON and structural-footprint limits are independent; a write must
 * satisfy both. Map/list depth counts containers below the top-level document
 * root, so a top-level map or list field value is level 1. The serialized and
 * structural constants also define the base partial-update result envelope.</p>
 */
public final class PortableWriteLimits {

    /** Maximum serialized UTF-8 JSON bytes for a portable write input (390 KiB). */
    public static final int MAX_SERIALIZED_INPUT_BYTES = 390 * 1024;

    /** Maximum provider-neutral structural footprint in bytes (390 KiB). */
    public static final int MAX_STRUCTURAL_FOOTPRINT_BYTES = 390 * 1024;

    /** Maximum UTF-8 bytes in a nested field name or partial-update field name. */
    public static final int MAX_FIELD_NAME_UTF8_BYTES = 50_000;

    /** Maximum Unicode characters in a complete document's top-level field name. */
    public static final int MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS = 128;

    /** Maximum map/list containers below the top-level document root. */
    public static final int MAX_NESTED_CONTAINERS = 31;

    /** Maximum top-level fields assigned by one portable partial update. */
    public static final int MAX_PARTIAL_UPDATE_FIELDS = 10;

    private PortableWriteLimits() {
    }
}