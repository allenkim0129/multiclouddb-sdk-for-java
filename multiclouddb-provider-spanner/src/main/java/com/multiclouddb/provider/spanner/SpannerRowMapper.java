// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Maps Spanner {@link ResultSet} rows to Jackson {@link JsonNode} documents.
 * <p>
 * Supports all common Spanner column types: STRING, INT64, FLOAT64, BOOL,
 * BYTES, TIMESTAMP, DATE, and JSON.
 * <p>
 * When a {@code data} column is present and contains a JSON array of field
 * names, only those fields (plus the primary key columns) are included in the
 * result. This lets the SDK distinguish between "explicitly set to null" and
 * "empty schema column" — a distinction that Spanner's fixed schema otherwise
 * loses.
 * <p>
 * STRING values written by the SDK as JSON-serialised Map/Collection are
 * tagged with {@link SpannerConstants#JSON_VALUE_MARKER} (a control-char
 * prefix). Only marker-prefixed values are parsed back as JSON; ordinary
 * user strings (including legitimate text starting with {@code [} or
 * <code>{</code>) are returned verbatim.
 */
public final class SpannerRowMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private SpannerRowMapper() {
    }

    /**
     * Convert the current row of a {@link ResultSet} into a {@link JsonNode}.
     * The cursor must already be positioned on a valid row (i.e., after
     * {@code rs.next()} returned true).
     *
     * @param rs the result set positioned on a row
     * @return a JSON object node with column values mapped to appropriate JSON
     *         types
     */
    public static JsonNode toJsonNode(ResultSet rs) {
        ObjectNode node = MAPPER.createObjectNode();
        Type type = rs.getType();
        int columnCount = rs.getColumnCount();

        // Single pre-scan: locate the data column (once) and parse its metadata.
        int dataColumnIndex = -1;
        for (int i = 0; i < columnCount; i++) {
            if (SpannerConstants.FIELD_DATA.equals(type.getStructFields().get(i).getName())) {
                dataColumnIndex = i;
                break;
            }
        }
        Set<String> writtenFields = parseFieldMetadata(rs, dataColumnIndex);

        for (int i = 0; i < columnCount; i++) {
            if (i == dataColumnIndex) continue; // internal metadata column

            String colName = type.getStructFields().get(i).getName();
            Type colType = type.getStructFields().get(i).getType();
            boolean keyColumn = SpannerConstants.FIELD_PARTITION_KEY.equals(colName)
                    || SpannerConstants.FIELD_SORT_KEY.equals(colName);
            String outputName = keyColumn || writtenFields == null
                    ? colName
                    : metadataFieldName(writtenFields, colName);

            // A metadata spelling that differs only by case is the logical field
            // name; no match means the schema column was never written by the SDK.

            if (rs.isNull(i)) {
                // Surface null columns when either:
                //   - we have FIELD_DATA metadata and the column is listed in it
                //     (explicitly-written null — must round-trip), or
                //   - we have no/invalid FIELD_DATA metadata (legacy / pre-FIELD_DATA
                //     rows): keep the historical "no metadata => no filtering"
                //     behaviour and emit every null column so existing callers don't
                //     silently lose null fields on the upgrade.
                if (outputName != null) {
                    node.putNull(outputName);
                }
                continue;
            }

            // For non-null values, include if no metadata or if field is in metadata.
            if (outputName == null) {
                continue;
            }

            switch (colType.getCode()) {
                case STRING -> {
                    String s = rs.getString(i);
                    // Only parse as JSON if it carries the explicit SDK marker —
                    // ordinary user strings are passed through unchanged. A user
                    // string that happens to start with U+0001 is escaped at
                    // write-time with a doubled leading U+0001, which we strip
                    // here so the verbatim value round-trips.
                    if (s != null && s.startsWith(SpannerConstants.JSON_VALUE_MARKER)) {
                        String payload = s.substring(SpannerConstants.JSON_VALUE_MARKER.length());
                        try {
                            node.set(outputName, MAPPER.readTree(payload));
                        } catch (Exception e) {
                            // Marker present but payload corrupted — return raw string for diagnosis.
                            node.put(outputName, s);
                        }
                    } else if (s != null && s.length() >= 2
                            && s.charAt(0) == '\u0001'
                            && s.charAt(1) == '\u0001') {
                        // Escape pair: user string that itself starts with U+0001.
                        node.put(outputName, s.substring(1));
                    } else {
                        node.put(outputName, s);
                    }
                }
                case INT64 -> node.put(outputName, rs.getLong(i));
                case FLOAT64 -> node.put(outputName, rs.getDouble(i));
                case BOOL -> node.put(outputName, rs.getBoolean(i));
                case BYTES -> node.put(outputName, rs.getBytes(i).toBase64());
                case TIMESTAMP -> node.put(outputName, rs.getTimestamp(i).toString());
                case DATE -> node.put(outputName, rs.getDate(i).toString());
                case JSON -> {
                    try {
                        node.set(outputName, MAPPER.readTree(rs.getJson(i)));
                    } catch (Exception e) {
                        node.put(outputName, rs.getJson(i));
                    }
                }
                default -> node.put(outputName, rs.getString(i));
            }
        }

        return node;
    }

    /**
     * Convert the current row of a {@link ResultSet} into a plain
     * {@code Map<String, Object>}.
     * The cursor must already be positioned on a valid row (i.e., after
     * {@code rs.next()} returned true).
     * <p>
     * <strong>Explicit nulls are preserved.</strong> If the SDK wrote a null value
     * to a column (recorded in the {@code FIELD_DATA} metadata column), it is
     * returned as a {@code null} entry in the map — matching the schemaless
     * round-trip behaviour of Cosmos / DynamoDB and keeping the two adjacent
     * APIs ({@link #toJsonNode}, {@link #toMap}) consistent. Callers must
     * tolerate {@code null} values when iterating the returned map.
     *
     * @param rs the result set positioned on a row
     * @return a map of column name to Java value; values may be {@code null}
     *         for explicitly-written null fields
     */
    public static Map<String, Object> toMap(ResultSet rs) {
        JsonNode node = toJsonNode(rs);
        // convertValue may return an immutable view in some configurations, so
        // wrap the result in a fresh LinkedHashMap that preserves null entries
        // (parity with toJsonNode's null-preservation behaviour and with
        // schemaless stores like Cosmos / DynamoDB).
        Map<String, Object> raw = MAPPER.convertValue(node, MAP_TYPE);
        return new LinkedHashMap<>(raw);
    }

    /**
     * Parses the field-name metadata stored at the given index.
     *
     * @param rs              the result set positioned on a row
     * @param dataColumnIndex the column index of the {@code data} column, or
     *                        {@code -1} if the table has no data column
     * @return set of field names that were explicitly written, or {@code null}
     *         if no metadata is available (legacy row or data column absent)
     */
    private static String metadataFieldName(Set<String> writtenFields, String columnName) {
        if (writtenFields.contains(columnName)) return columnName;

        String match = null;
        for (String fieldName : writtenFields) {
            if (fieldName.equalsIgnoreCase(columnName)) {
                if (match != null) return null;
                match = fieldName;
            }
        }
        return match;
    }

    private static Set<String> parseFieldMetadata(ResultSet rs, int dataColumnIndex) {
        if (dataColumnIndex < 0 || rs.isNull(dataColumnIndex)) return null;
        String dataValue = rs.getString(dataColumnIndex);
        if (dataValue == null || !dataValue.startsWith("[")) return null;
        try {
            List<String> fields = MAPPER.readValue(dataValue, STRING_LIST_TYPE);
            return new HashSet<>(fields);
        } catch (Exception e) {
            return null;
        }
    }
}
