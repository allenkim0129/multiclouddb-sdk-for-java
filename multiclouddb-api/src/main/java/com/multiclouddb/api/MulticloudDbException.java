// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

/**
 * Exception carrying a structured {@link MulticloudDbError} with optional
 * diagnostics.
 */
public class MulticloudDbException extends RuntimeException {

    private final MulticloudDbError error;
    private OperationDiagnostics diagnostics;

    public MulticloudDbException(MulticloudDbError error) {
        super(error.message());
        this.error = error;
    }

    public MulticloudDbException(MulticloudDbError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public MulticloudDbError error() {
        return error;
    }

    public OperationDiagnostics diagnostics() {
        return diagnostics;
    }

    public MulticloudDbException withDiagnostics(OperationDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        return this;
    }
}
