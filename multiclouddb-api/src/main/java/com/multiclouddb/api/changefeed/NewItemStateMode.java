// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

/**
 * Whether {@link ChangeEvent#data()} should be populated with the new image of
 * the document.
 *
 * <ul>
 *   <li>{@link #OMIT} — never populate {@code data}; events carry only the
 *       key and change type. Cheapest; works on every provider.</li>
 *   <li>{@link #INCLUDE_IF_AVAILABLE} — populate {@code data} when the
 *       provider can supply the new image (Cosmos always; Dynamo when
 *       {@code StreamViewType ∈ {NEW_IMAGE, NEW_AND_OLD_IMAGES}}; Spanner when
 *       {@code value_capture_type ∈ {NEW_ROW, NEW_ROW_AND_OLD_VALUES}}).
 *       Otherwise {@code data} is {@code null}.</li>
 *   <li>{@link #REQUIRE} — fail with
 *       {@link com.multiclouddb.api.MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}
 *       if the provider cannot deliver the new image. Use this when your
 *       consumer cannot tolerate {@code null} payloads.</li>
 * </ul>
 */
public enum NewItemStateMode {
    OMIT,
    INCLUDE_IF_AVAILABLE,
    REQUIRE
}
