// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

/**
 * A closed, immutable document value. Java {@code null} is not a value;
 * use {@link NullValue#INSTANCE} for explicit null.
 */
public sealed interface DocumentValue permits NullValue, BooleanValue, StringValue,
        NumberValue, BinaryValue, ArrayValue, ObjectValue {
}
