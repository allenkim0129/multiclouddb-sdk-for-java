// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.Arrays;
import java.util.Objects;

/** An immutable binary value. Portable database writes do not support this kind. */
public final class BinaryValue implements DocumentValue {
    private final byte[] value;

    private BinaryValue(byte[] value) {
        this.value = value;
    }

    /** Copies the supplied bytes. */
    public static BinaryValue of(byte[] value) {
        return new BinaryValue(Objects.requireNonNull(value, "value").clone());
    }

    /** Returns a new copy of the bytes. */
    public byte[] value() {
        return value.clone();
    }

    /** Returns the byte count without allocating a copy. */
    public int size() {
        return value.length;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BinaryValue binary
                && Arrays.equals(value, binary.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "BinaryValue[length=" + value.length + "]";
    }
}
