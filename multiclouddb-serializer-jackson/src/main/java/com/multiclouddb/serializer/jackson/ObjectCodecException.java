// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import java.util.Objects;

/**
 * An application-object mapping failure, separate from database request failures.
 * Messages contain only SDK phase/reason identifiers, not customer values,
 * field names or serializer messages. Raw causes and suppressed exceptions are
 * deliberately not accepted or attached.
 */
public final class ObjectCodecException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Boundary at which mapping failed. */
    public enum Phase {
        CONSTRUCTION, ENCODE, DECODE
    }

    /** Stable identifiers for implemented preparation and structural checks. */
    public enum Reason {
        INVALID_ARGUMENT,
        MAPPER_COPY_FAILED,
        INVALID_ROOT,
        INVALID_STRUCTURE,
        DUPLICATE_FIELD,
        UNSUPPORTED_OUTPUT,
        MAPPING_FAILED
    }

    private final Phase phase;
    private final Reason reason;

    public ObjectCodecException(Phase phase, Reason reason) {
        super(message(phase, reason), null, false, true);
        this.phase = phase;
        this.reason = reason;
    }

    public Phase phase() {
        return phase;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Phase phase, Reason reason) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(reason, "reason");
        return "Object codec failure: " + phase.name() + "/" + reason.name() + ".";
    }
}
