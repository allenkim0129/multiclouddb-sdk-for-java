// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static com.multiclouddb.serializer.jackson.ObjectCodecException.Phase.*;
import static com.multiclouddb.serializer.jackson.ObjectCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectCodecExceptionTest {
    @Test
    void retainsPhaseAndReasonWithoutRawCausesOrSuppressedDetails() {
        ObjectCodecException failure = new ObjectCodecException(CONSTRUCTION, MAPPER_COPY_FAILED);
        IllegalStateException raw = new IllegalStateException("customer-secret");
        assertThrows(IllegalStateException.class, () -> failure.initCause(raw));
        failure.addSuppressed(raw);
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(CONSTRUCTION, failure.phase());
        assertEquals(MAPPER_COPY_FAILED, failure.reason());
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains("customer-secret"));
        assertTrue(failure.getMessage().contains("CONSTRUCTION/MAPPER_COPY_FAILED"));
    }

    @Test
    void rejectsMissingIdentifiers() {
        assertThrows(NullPointerException.class, () -> new ObjectCodecException(null, INVALID_ARGUMENT));
        assertThrows(NullPointerException.class, () -> new ObjectCodecException(ENCODE, null));
    }
}
