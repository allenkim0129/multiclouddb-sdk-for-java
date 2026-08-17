// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.query.Expression;
import com.multiclouddb.api.query.ExpressionParser;
import com.multiclouddb.api.query.NotExpression;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the two-valued {@code field_exists} predicate.
 *
 * <p><b>Why this test exists.</b> Spanner's {@code JSON_QUERY} over an absent
 * path yields SQL {@code NULL}, so the previous
 * {@code JSON_TYPE(<accessor>) != 'null'} evaluated to SQL {@code NULL} — not
 * {@code FALSE} — for a missing field. Bare, that still excludes the row, which
 * is why the bug was invisible; but {@code NOT NULL} is also {@code NULL}, so
 * {@code NOT field_exists(x)} excluded the very rows it must return. Cosmos
 * ({@code NOT (IS_DEFINED(c.x) AND NOT IS_NULL(c.x))}) and DynamoDB
 * ({@code NOT (x IS NOT MISSING AND x IS NOT NULL)}) both evaluate to
 * {@code TRUE} and include the row, so the Spanner form was an ungated
 * cross-provider divergence.
 *
 * <p>The truth table below was measured on the Spanner emulator for the
 * {@code COALESCE(..., FALSE)} form now emitted:
 *
 * <table>
 *   <caption>field_exists truth table</caption>
 *   <tr><th>row</th><th>field_exists</th><th>NOT field_exists</th></tr>
 *   <tr><td>absent</td><td>FALSE</td><td>TRUE</td></tr>
 *   <tr><td>explicit JSON null</td><td>FALSE</td><td>TRUE</td></tr>
 *   <tr><td>present non-null</td><td>TRUE</td><td>FALSE</td></tr>
 * </table>
 *
 * <p>No provider tag is set, so the {@code unit} profile runs this test.
 */
@DisplayName("Spanner — field_exists is two-valued so NOT field_exists matches Cosmos/Dynamo")
class SpannerFieldExistsNegationTest {

    private static final String TABLE = "items";
    private static final Map<String, Object> NO_PARAMS = Map.of();

    private final SpannerExpressionTranslator translator = new SpannerExpressionTranslator();

    @Test
    @DisplayName("field_exists folds SQL NULL to FALSE via COALESCE")
    void fieldExistsIsTwoValued() {
        TranslatedQuery query =
                translator.translate(ExpressionParser.parse("field_exists(metadata)"), NO_PARAMS, TABLE);

        String accessor = SpannerExpressionTranslator.jsonField("metadata");
        assertEquals("COALESCE(JSON_TYPE(" + accessor + ") != 'null', FALSE)",
                query.whereClause(),
                "an absent path makes JSON_TYPE(...) SQL NULL; without COALESCE the "
                        + "predicate is three-valued and cannot be negated portably");
    }

    @Test
    @DisplayName("NOT field_exists negates a real boolean, not a SQL NULL")
    void notFieldExistsNegatesABoolean() {
        Expression negated =
                new NotExpression(ExpressionParser.parse("field_exists(metadata)"));
        String where = translator.translate(negated, NO_PARAMS, TABLE).whereClause();

        String accessor = SpannerExpressionTranslator.jsonField("metadata");
        assertEquals("NOT (COALESCE(JSON_TYPE(" + accessor + ") != 'null', FALSE))", where);

        // The guard that matters: the inner predicate must never be left bare,
        // because NOT NULL is NULL and the absent-field row would be dropped on
        // Spanner while Cosmos and DynamoDB return it.
        assertFalse(where.contains("NOT (JSON_TYPE("),
                "NOT must wrap a two-valued predicate: " + where);
        assertTrue(where.startsWith("NOT (COALESCE("), where);
    }

    @Test
    @DisplayName("the non-negated truth value is unchanged (same accessor, same comparison)")
    void nonNegatedBehaviourIsPreserved() {
        String where = translator
                .translate(ExpressionParser.parse("field_exists(meta.region)"), NO_PARAMS, TABLE)
                .whereClause();

        // Still the authoritative envelope accessor, still "is not JSON null".
        assertTrue(where.contains(SpannerExpressionTranslator.jsonField("meta.region")), where);
        assertTrue(where.contains(") != 'null'"), where);
    }
}
