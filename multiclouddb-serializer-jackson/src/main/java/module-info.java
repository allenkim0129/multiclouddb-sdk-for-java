// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/** Explicit application-owned object mapping for the existing Map/ObjectNode API. */
module com.multiclouddb.serializer.jackson {
    exports com.multiclouddb.serializer.jackson;
    requires com.fasterxml.jackson.core;
    requires transitive com.fasterxml.jackson.databind;
}
