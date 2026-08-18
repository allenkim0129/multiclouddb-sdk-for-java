// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.SortDirection;
import com.multiclouddb.api.query.ComparisonExpression;
import com.multiclouddb.api.query.ComparisonOp;
import com.multiclouddb.api.query.FieldRef;
import com.multiclouddb.api.query.InExpression;
import com.multiclouddb.api.query.Literal;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the two portability fixes in the Spanner query surface:
 *
 * <ol>
 *   <li><b>Envelope-authoritative ORDER BY.</b> Reads and WHERE-clause field
 *       references resolve through the authoritative {@code data} envelope, so
 *       ORDER BY must too. A bare physical column reference cannot see a
 *       dynamic top-level field (no DDL column exists — GoogleSQL fails with
 *       {@code Unrecognized name}), and it silently mis-sorts a field whose
 *       physical mirror was cleared to typed NULL because the runtime value no
 *       longer matched the column type. Cosmos and DynamoDB sort both cases
 *       correctly, so leaving Spanner on bare columns is an ungated
 *       cross-provider divergence.</li>
 *   <li><b>Degenerate IN.</b> An IN predicate over an empty value set is
 *       unsatisfiable and must translate to the portable always-false predicate
 *       rather than throw a raw {@link IndexOutOfBoundsException} out of
 *       {@code translate()}.</li>
 * </ol>
 *
 * <p>No provider tag is set, so the {@code unit} profile runs this test.
 */
@DisplayName("Spanner — envelope-authoritative ORDER BY and degenerate IN")
class SpannerEnvelopeOrderByTest {

    private static final String ALIAS = SpannerExpressionTranslator.ROW_ALIAS;

    // ---- ORDER BY routes through the authoritative envelope ----

    @Test
    @DisplayName("ORDER BY on a dynamic field reads the data envelope, not a bare column")
    void orderByUsesEnvelopeAccessorForDocumentFields() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("onSale", SortDirection.ASC)
                .build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT " + ALIAS + ".* FROM items AS " + ALIAS, q, true);

        // `onSale` has no physical column: a bare `ORDER BY onSale` would fail
        // GoogleSQL analysis with "Unrecognized name: onSale".
        assertFalse(out.contains("ORDER BY onSale"),
                "a dynamic field must never be emitted as a bare column: " + out);
        assertTrue(out.contains("SAFE.PARSE_JSON(" + ALIAS + ".data)"),
                "ORDER BY must read the authoritative envelope: " + out);
        assertTrue(out.contains("TO_JSON(" + ALIAS + ")"),
                "the legacy-row physical fallback branch must be preserved: " + out);
        assertTrue(out.contains(SpannerConstants.FIELD_DATA_DOCUMENT), out);
    }

    @Test
    @DisplayName("ORDER BY extracts typed scalars so numbers sort numerically")
    void orderByExtractsTypedScalars() {
        String keys = SpannerExpressionTranslator.orderByExpression("value", "ASC");

        // Ordering the raw JSON_QUERY result would compare JSON text, sorting
        // 10 before 9. Type-ranked LAX_* extraction avoids that.
        assertTrue(keys.contains("JSON_TYPE("), keys);
        assertTrue(keys.contains("LAX_FLOAT64("), keys);
        assertTrue(keys.contains("LAX_STRING("), keys);
        assertTrue(keys.contains("LAX_BOOL("), keys);
        assertTrue(keys.contains("WHEN 'boolean' THEN 2 WHEN 'number' THEN 3 WHEN 'string' THEN 4"
                + " WHEN 'array' THEN 5 WHEN 'object' THEN 6 ELSE 1"),
                "JSON kinds must not interleave: " + keys);
        // The rank carries the direction so DESC is the exact reverse of ASC.
        assertEquals(4, keys.split(" ASC", -1).length - 1,
                "every sort key must carry the caller's direction: " + keys);

        String desc = SpannerExpressionTranslator.orderByExpression("value", "DESC");
        assertEquals(4, desc.split(" DESC", -1).length - 1, desc);
        assertFalse(desc.contains(" ASC"), desc);
    }

    @Test
    @DisplayName("cross-type ORDER BY rank matches Cosmos across every JSON kind")
    void orderByTypeRankMatchesCosmosTotalOrder() {
        // DynamoDB declares ORDER_BY unsupported, so Cosmos and Spanner are the
        // only two providers on this surface. Cosmos NoSQL's documented total
        // order is `undefined < null < boolean < number < string < array < object`;
        // any other Spanner rank is an ungated cross-provider divergence.
        String keys = SpannerExpressionTranslator.orderByExpression("value", null);
        // The accessor itself contains a nested CASE ... END, so slice at the
        // first tie-breaker rather than at the first " END".
        String rank = keys.substring(0, keys.indexOf(", LAX_FLOAT64("));

        assertTrue(rank.indexOf("'boolean' THEN 2") > 0, rank);
        assertTrue(rank.indexOf("'number' THEN 3") > 0, rank);
        assertTrue(rank.indexOf("'string' THEN 4") > 0, rank);
        // Arrays and objects are Cosmos's two highest kinds. Folding them into
        // the ELSE arm put them on the lowest rank instead — a silent divergence.
        assertTrue(rank.indexOf("'array' THEN 5") > 0, rank);
        assertTrue(rank.indexOf("'object' THEN 6") > 0, rank);
        // JSON `null` and an absent field (JSON_QUERY yields SQL NULL, which
        // matches no WHEN arm) share the lowest rank, as `undefined` and `null`
        // do on Cosmos.
        assertTrue(rank.endsWith("ELSE 1 END"),
                "JSON null / absent fields must sort first, like Cosmos: " + rank);
    }

    @Test
    @DisplayName("ORDER BY uses the same accessor the WHERE clause uses")
    void orderByAndWhereShareTheSameAccessor() {
        TranslatedQuery translated = new SpannerExpressionTranslator().translate(
                new ComparisonExpression(new FieldRef("value"), ComparisonOp.GT,
                        new Literal(1L)),
                Map.of(), "items");
        String accessor = SpannerExpressionTranslator.jsonField("value");

        assertTrue(translated.whereClause().contains(accessor),
                "WHERE must use the envelope accessor: " + translated.whereClause());
        assertTrue(SpannerExpressionTranslator.orderByExpression("value", "ASC").contains(accessor),
                "ORDER BY must use the identical accessor so filters and sorts agree");
    }

    @Test
    @DisplayName("primary-key tiebreakers stay bare physical columns")
    void keyColumnsRemainPhysicalAndTiebreakersSurvive() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("score", SortDirection.DESC)
                .build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT " + ALIAS + ".* FROM items AS " + ALIAS, q, true);
        assertTrue(out.endsWith(", " + SpannerConstants.FIELD_PARTITION_KEY
                        + ", " + SpannerConstants.FIELD_SORT_KEY),
                "deterministic-pagination tiebreakers must be preserved: " + out);

        // partitionKey / sortKey are physical key columns that never appear in
        // the envelope, so they must not be routed through the JSON accessor.
        QueryRequest keys = QueryRequest.builder()
                .orderBy(SpannerConstants.FIELD_PARTITION_KEY, SortDirection.ASC)
                .orderBy(SpannerConstants.FIELD_SORT_KEY, SortDirection.DESC)
                .build();
        assertEquals("SELECT * FROM items ORDER BY partitionKey ASC, sortKey DESC",
                SpannerProviderClient.appendResultSetControl("SELECT * FROM items", keys, true));
    }

    @Test
    @DisplayName("caller-supplied native SQL keeps bare-column ordering (no row alias exists)")
    void nativeSqlKeepsBareColumnOrdering() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("name", SortDirection.ASC)
                .build();
        assertEquals("SELECT * FROM items ORDER BY name ASC, partitionKey, sortKey",
                SpannerProviderClient.appendResultSetControl("SELECT * FROM items", q));
    }

    // ---- Degenerate IN ----

    @Test
    @DisplayName("an IN with no values translates to FALSE instead of throwing")
    void emptyInListTranslatesToFalse() {
        // InExpression's canonical constructor already rejects an empty list —
        // this asserts that contract and then drives the translator's
        // defence-in-depth guard with a stand-in that bypasses it, proving no
        // raw IndexOutOfBoundsException can escape translate().
        assertThrows(IllegalArgumentException.class,
                () -> new InExpression(new FieldRef("status"), List.of()));

        InExpression empty = mock(InExpression.class);
        when(empty.field()).thenReturn(new FieldRef("status"));
        when(empty.values()).thenReturn(List.of());

        TranslatedQuery translated =
                new SpannerExpressionTranslator().translate(empty, Map.of(), "items");
        assertEquals("FALSE", translated.whereClause(),
                "membership in an empty set is unsatisfiable — the null-operand "
                        + "branch and the Cosmos/DynamoDB translators use the same "
                        + "portable always-false predicate");
    }
}
