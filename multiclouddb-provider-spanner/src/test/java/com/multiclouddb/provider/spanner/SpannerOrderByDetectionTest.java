// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SpannerProviderClient#hasOrderByClause(String)}.
 * <p>
 * Ensures the SDK does not append a default/tiebreaker {@code ORDER BY} clause
 * to caller-supplied SQL (e.g., a raw GoogleSQL expression passed via
 * {@code QueryRequest.expression()}) when one is already present.
 */
class SpannerOrderByDetectionTest {

    @Test
    @DisplayName("returns true for trailing ORDER BY clause")
    void trailingOrderBy() {
        assertTrue(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE status = @s ORDER BY createdAt DESC"));
    }

    @Test
    @DisplayName("returns true regardless of case")
    void caseInsensitive() {
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t order by name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t Order By name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER BY name"));
    }

    @Test
    @DisplayName("returns true with multiple/newline whitespace between ORDER and BY")
    void whitespaceVariants() {
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER  BY name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER\nBY name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER\tBY name"));
    }

    @Test
    @DisplayName("returns false for SQL without ORDER BY")
    void noOrderBy() {
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT * FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE status = @s"));
    }

    @Test
    @DisplayName("returns false for column names containing the substring 'order'")
    void substringFalsePositiveGuard() {
        // Word boundaries should not match identifiers like order_id, MY_ORDER_BY
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT order_id FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT my_order, by_field FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT orderby_column FROM items"));
    }

    @Test
    @DisplayName("returns false for null SQL")
    void nullSqlReturnsFalse() {
        assertFalse(SpannerProviderClient.hasOrderByClause(null));
    }

    @Test
    @DisplayName("ignores 'ORDER BY' inside a string literal")
    void stringLiteralFalsePositiveGuard() {
        // The literal-stripping pass must mask out anything inside single
        // quotes; otherwise the SDK skips its default ORDER BY when a caller
        // happens to mention the phrase in a WHERE clause.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE comment = 'please ORDER BY date'"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT 'order by foo' AS msg FROM dual"));
        // SQL-escaped quotes ('') must still leave the surrounding literal masked.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM t WHERE c = 'don''t ORDER BY this'"));
    }

    @Test
    @DisplayName("real ORDER BY following a string literal is still detected")
    void orderByAfterLiteralIsDetected() {
        assertTrue(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE comment = 'please' ORDER BY date"));
    }

    @Test
    @DisplayName("containsAggregate identifies aggregate functions and GROUP BY")
    void aggregateDetection() {
        assertTrue(SpannerProviderClient.containsAggregate("SELECT COUNT(*) FROM items"));
        assertTrue(SpannerProviderClient.containsAggregate("SELECT SUM(amount) FROM items"));
        assertTrue(SpannerProviderClient.containsAggregate("SELECT MAX(age) FROM people"));
        assertTrue(SpannerProviderClient.containsAggregate(
                "SELECT status, COUNT(*) FROM items GROUP BY status"));
    }

    @Test
    @DisplayName("containsAggregate ignores aggregate keywords inside string literals")
    void aggregateDetectionIgnoresLiterals() {
        // Same masking pass as hasOrderByClause; a literal containing the
        // keyword must not be classified as an aggregate.
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT note FROM items WHERE note = 'COUNT(*) of stuff'"));
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT * FROM items WHERE note = 'GROUP BY day'"));
    }

    @Test
    @DisplayName("containsAggregate returns false for plain projections")
    void aggregateDetectionNegative() {
        assertFalse(SpannerProviderClient.containsAggregate("SELECT * FROM items"));
        assertFalse(SpannerProviderClient.containsAggregate("SELECT id, name FROM items"));
    }
}
