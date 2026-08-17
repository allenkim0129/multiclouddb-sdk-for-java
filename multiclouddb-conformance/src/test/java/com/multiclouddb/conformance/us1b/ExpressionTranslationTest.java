// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us1b;

import com.multiclouddb.api.query.*;
import com.multiclouddb.provider.cosmos.CosmosExpressionTranslator;
import com.multiclouddb.provider.dynamo.DynamoExpressionTranslator;
import com.multiclouddb.provider.spanner.SpannerExpressionTranslator;
import org.junit.jupiter.api.*;

import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for expression translators across all three providers (T046).
 * <p>
 * Verifies that the same portable expression AST produces correct
 * provider-specific SQL for Cosmos DB, DynamoDB (PartiQL), and Spanner
 * (GoogleSQL).
 */
@DisplayName("Expression Translation")
class ExpressionTranslationTest {

    private final CosmosExpressionTranslator cosmos = new CosmosExpressionTranslator();
    private final DynamoExpressionTranslator dynamo = new DynamoExpressionTranslator();
    private final SpannerExpressionTranslator spanner = new SpannerExpressionTranslator();

    private static final String TABLE = "items";
    private static final Map<String, Object> EMPTY_PARAMS = Map.of();

    private void assertPortableFalse(Expression expression, Map<String, Object> parameters) {
        assertEquals("FALSE", cosmos.translate(expression, parameters, TABLE).whereClause());
        assertEquals("FALSE", dynamo.translate(expression, parameters, TABLE).whereClause());
        assertEquals("FALSE", spanner.translate(expression, parameters, TABLE).whereClause());
    }

    private static void assertSpannerEnvelopeField(TranslatedQuery query, String field) {
        StringBuilder suffix = new StringBuilder();
        for (String segment : field.split("\\.")) {
            suffix.append(".\"").append(segment).append('"');
        }
        String envelope = "JSON_QUERY(SAFE.PARSE_JSON(r.data), '$._mcdbDocument')";
        String fieldPath = "$" + suffix;
        assertTrue(query.whereClause().contains(
                        "CASE WHEN JSON_TYPE(" + envelope + ") = 'object' THEN JSON_QUERY("
                                + envelope + ", '" + fieldPath + "')"),
                "portable predicates must choose the authoritative envelope once per row");
        assertTrue(query.whereClause().contains(
                        "ELSE JSON_QUERY(TO_JSON(r), '" + fieldPath + "') END"),
                "legacy physical rows must remain queryable through the fallback projection");
        // The accessor itself must never be COALESCE(envelope, physical): that
        // would let a field absent from an authoritative envelope fall back to a
        // stale physical column. A COALESCE around a *boolean* is unrelated —
        // field_exists folds SQL NULL to FALSE so that negation matches Cosmos
        // and DynamoDB (see SpannerFieldExistsNegationTest).
        assertFalse(query.whereClause().contains("COALESCE(JSON_QUERY("),
                "a missing field in an authoritative envelope must not fall back to stale columns");
    }

    private static void assertSpannerTypeGuard(TranslatedQuery query, String field, String type,
            String coercion) {
        StringBuilder suffix = new StringBuilder();
        for (String segment : field.split("\\.")) {
            suffix.append(".\"").append(segment).append('"');
        }
        String envelope = "JSON_QUERY(SAFE.PARSE_JSON(r.data), '$._mcdbDocument')";
        String fieldPath = "$" + suffix;
        String jsonField = "CASE WHEN JSON_TYPE(" + envelope + ") = 'object' THEN JSON_QUERY("
                + envelope + ", '" + fieldPath + "') ELSE JSON_QUERY(TO_JSON(r), '"
                + fieldPath + "') END";
        assertTrue(query.whereClause().contains("JSON_TYPE(" + jsonField + ") = '" + type + "'"),
                "Spanner must guard " + type + " comparisons before LAX coercion");
        assertTrue(query.whereClause().contains(coercion + "(" + jsonField + ")"),
                "Spanner must coerce only after the JSON type guard has passed");
    }

    // ---- Simple comparison with parameter ----

    @Test
    @DisplayName("simple equality: status = @status")
    void simpleEquality() {
        Expression ast = ExpressionParser.parse("status = @status");
        Map<String, Object> params = Map.of("status", "active");

        TranslatedQuery cosmosResult = cosmos.translate(ast, params, TABLE);
        assertEquals("SELECT * FROM c WHERE c.status = @status", cosmosResult.queryString());
        assertEquals("c.status = @status", cosmosResult.whereClause());
        assertEquals(Map.of("@status", "active"), cosmosResult.namedParameters());
        assertTrue(cosmosResult.positionalParameters().isEmpty());

        TranslatedQuery dynamoResult = dynamo.translate(ast, params, TABLE);
        assertEquals("SELECT * FROM \"items\" WHERE status = ?", dynamoResult.queryString());
        assertEquals("status = ?", dynamoResult.whereClause());
        assertEquals(List.of("active"), dynamoResult.positionalParameters());
        assertTrue(dynamoResult.namedParameters().isEmpty());

        TranslatedQuery spannerResult = spanner.translate(ast, params, TABLE);
        assertTrue(spannerResult.queryString().startsWith("SELECT r.* FROM items AS r WHERE "));
        assertSpannerEnvelopeField(spannerResult, "status");
        assertEquals(Map.of("@status", "active"), spannerResult.namedParameters());
    }

    // ---- All comparison operators ----

    @Test
    @DisplayName("all comparison operators produce correct symbols")
    void allComparisonOps() {
        for (ComparisonOp op : ComparisonOp.values()) {
            String symbol = op.symbol();
            Expression ast = ExpressionParser.parse("x " + symbol + " @v");
            Map<String, Object> params = Map.of("v", 42);

            TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
            assertTrue(cosRes.whereClause().contains(symbol),
                    "Cosmos should contain " + symbol);

            TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
            assertTrue(dynRes.whereClause().contains(symbol),
                    "Dynamo should contain " + symbol);

            TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
            assertTrue(spnRes.whereClause().contains(symbol),
                    "Spanner should contain " + symbol);
        }
    }

    // ---- Literal comparison ----

    @Test
    @DisplayName("string literal: name = 'hello'")
    void stringLiteral() {
        Expression ast = ExpressionParser.parse("name = 'hello'");

        assertEquals("c.name = 'hello'", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("name = 'hello'", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "name");
    }

    @Test
    @DisplayName("numeric literal: count = 42")
    void numericLiteral() {
        Expression ast = ExpressionParser.parse("count = 42");

        assertEquals("c.count = 42", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("count = 42", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "count");
    }

    @Test
    @DisplayName("boolean literal: active = true")
    void booleanLiteral() {
        Expression ast = ExpressionParser.parse("active = true");

        assertEquals("c.active = true", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("active = true", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "active");
    }

    @Test
    @DisplayName("null literal: value = null")
    void nullLiteral() {
        Expression ast = ExpressionParser.parse("value = null");

        assertEquals("c.value = null", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("value = NULL", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "value");
    }

    // ---- Logical operators ----

    @Test
    @DisplayName("AND expression")
    void andExpression() {
        Expression ast = ExpressionParser.parse("a = @a AND b = @b");
        Map<String, Object> params = Map.of("a", 1, "b", 2);

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(c.a = @a AND c.b = @b)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(a = ? AND b = ?)", dynRes.whereClause());
        assertEquals(List.of(1, 2), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spnRes, "a");
        assertSpannerEnvelopeField(spnRes, "b");
    }

    @Test
    @DisplayName("OR expression")
    void orExpression() {
        Expression ast = ExpressionParser.parse("a = @a OR b = @b");
        Map<String, Object> params = Map.of("a", 1, "b", 2);

        assertEquals("(c.a = @a OR c.b = @b)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("(a = ? OR b = ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        TranslatedQuery spannerResult = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spannerResult, "a");
        assertSpannerEnvelopeField(spannerResult, "b");
    }

    @Test
    @DisplayName("NOT expression")
    void notExpression() {
        Expression ast = ExpressionParser.parse("NOT active = true");

        assertEquals("NOT (c.active = true)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("NOT (active = true)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "active");
    }

    // ---- Complex logical with precedence ----

    @Test
    @DisplayName("AND + OR precedence: a=1 OR b=2 AND c=3")
    void andOrPrecedence() {
        Expression ast = ExpressionParser.parse("a = 1 OR b = 2 AND c = 3");

        // Parser: a=1 OR (b=2 AND c=3) → LogicalExpression(OR, a=1,
        // LogicalExpression(AND, b=2, c=3))
        String cosWhere = cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause();
        assertEquals("(c.a = 1 OR (c.b = 2 AND c.c = 3))", cosWhere);
    }

    // ---- Function calls ----

    @Test
    @DisplayName("starts_with function")
    void startsWithFunction() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        Map<String, Object> params = Map.of("prefix", "abc");

        assertEquals("STARTSWITH(c.name, @prefix)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("begins_with(name, ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, params, TABLE), "name");
    }

    @Test
    @DisplayName("contains function")
    void containsFunction() {
        Expression ast = ExpressionParser.parse("contains(description, @kw)");
        Map<String, Object> params = Map.of("kw", "test");

        assertEquals("CONTAINS(c.description, @kw)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("contains(description, ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, params, TABLE), "description");
    }

    @Test
    @DisplayName("field_exists function")
    void fieldExistsFunction() {
        Expression ast = ExpressionParser.parse("field_exists(metadata)");

        assertEquals("(IS_DEFINED(c.metadata) AND NOT IS_NULL(c.metadata))",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("(metadata IS NOT MISSING AND metadata IS NOT NULL)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "metadata");
    }

    @Test
    @DisplayName("Spanner guards every scalar comparison against unlike JSON types")
    void spannerComparisonTypeGuardsApplyToParametersAndLiterals() {
        for (ComparisonOp op : ComparisonOp.values()) {
            TranslatedQuery numericParameter = spanner.translate(
                    ExpressionParser.parse("numeric " + op.symbol() + " @value"),
                    Map.of("value", 1), TABLE);
            assertSpannerTypeGuard(numericParameter, "numeric", "number", "LAX_FLOAT64");
        }

        assertSpannerTypeGuard(spanner.translate(ExpressionParser.parse("numeric = 1"),
                EMPTY_PARAMS, TABLE), "numeric", "number", "LAX_FLOAT64");
        assertSpannerTypeGuard(spanner.translate(ExpressionParser.parse("text = '1'"),
                EMPTY_PARAMS, TABLE), "text", "string", "LAX_STRING");
        assertSpannerTypeGuard(spanner.translate(ExpressionParser.parse("enabled = true"),
                EMPTY_PARAMS, TABLE), "enabled", "boolean", "LAX_BOOL");

        assertSpannerTypeGuard(spanner.translate(
                ExpressionParser.parse("numeric IN (@first, @second)"),
                Map.of("first", 1, "second", 2), TABLE),
                "numeric", "number", "LAX_FLOAT64");
        assertSpannerTypeGuard(spanner.translate(
                ExpressionParser.parse("numeric BETWEEN 1 AND 2"), EMPTY_PARAMS, TABLE),
                "numeric", "number", "LAX_FLOAT64");
    }

    @Test
    @DisplayName("Spanner null comparisons do not invoke LAX coercion")
    void spannerNullComparisonsUseJsonNullSemantics() {
        assertEquals("JSON_TYPE(" + spannerJsonField("value") + ") = 'null'",
                spanner.translate(ExpressionParser.parse("value = null"), EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("JSON_TYPE(" + spannerJsonField("value") + ") != 'null'",
                spanner.translate(ExpressionParser.parse("value != null"), EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("FALSE",
                spanner.translate(ExpressionParser.parse("value < null"), EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("FALSE",
                spanner.translate(ExpressionParser.parse("value IN (null)"), EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("FALSE",
                spanner.translate(ExpressionParser.parse("value BETWEEN null AND 1"),
                        EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("null IN and BETWEEN operands never match on any provider")
    void nullRangeOperandsNeverMatch() {
        assertPortableFalse(ExpressionParser.parse("value IN (1, null)"), EMPTY_PARAMS);
        assertPortableFalse(ExpressionParser.parse("value BETWEEN 1 AND null"), EMPTY_PARAMS);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("first", 1);
        parameters.put("missing", null);
        assertPortableFalse(ExpressionParser.parse("value IN (@first, @missing)"), parameters);
        assertPortableFalse(ExpressionParser.parse("value BETWEEN @first AND @missing"), parameters);
    }

    @Test
    @DisplayName("empty IN lists are rejected up front and never match on any provider")
    void emptyInListNeverMatches() {
        assertThrows(IllegalArgumentException.class,
                () -> new InExpression(new FieldRef("status"), List.of()),
                "the portable AST must keep rejecting an empty IN list at construction time");

        InExpression emptyIn = new InExpression(new FieldRef("status"), new VanishingList());
        assertTrue(emptyIn.values().isEmpty(), "test fixture must yield an empty-valued IN");

        assertPortableFalse(emptyIn, EMPTY_PARAMS);
    }

    /**
     * Reports one element to {@link InExpression}'s emptiness check and none to
     * the defensive {@code List.copyOf} that follows it. This is the only way an
     * empty-valued {@code InExpression} can exist — a caller-owned list emptied
     * between the check and the copy — so it exercises the translators'
     * defence-in-depth guard without weakening or bypassing that validation.
     */
    private static final class VanishingList extends AbstractList<Object> {
        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public Object get(int index) {
            throw new IndexOutOfBoundsException(index);
        }
    }

    private static String spannerJsonField(String field) {
        String envelope = "JSON_QUERY(SAFE.PARSE_JSON(r.data), '$._mcdbDocument')";
        return "CASE WHEN JSON_TYPE(" + envelope + ") = 'object' THEN JSON_QUERY(" + envelope
                + ", '$.\"" + field + "\"') ELSE JSON_QUERY(TO_JSON(r), '$.\""
                + field + "\"') END";
    }

    @Test
    @DisplayName("string_length function")
    void stringLengthFunction() {
        Expression ast = ExpressionParser.parse("string_length(name)");

        assertEquals("LENGTH(c.name)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("char_length(name)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "name");
    }

    @Test
    @DisplayName("collection_size function")
    void collectionSizeFunction() {
        Expression ast = ExpressionParser.parse("collection_size(tags)");

        assertEquals("ARRAY_LENGTH(c.tags)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("size(tags)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "tags");
    }

    // ---- IN expression ----

    @Test
    @DisplayName("IN with parameters")
    void inWithParameters() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        Map<String, Object> params = Map.of("a", "open", "b", "closed");

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("c.status IN (@a, @b)", cosRes.whereClause());
        assertEquals(Map.of("@a", "open", "@b", "closed"), cosRes.namedParameters());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("status IN (?, ?)", dynRes.whereClause());
        assertEquals(List.of("open", "closed"), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spnRes, "status");
    }

    @Test
    @DisplayName("IN with string literals")
    void inWithLiterals() {
        Expression ast = ExpressionParser.parse("category IN ('X', 'Y')");

        assertEquals("c.category IN ('X', 'Y')",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("category IN ('X', 'Y')",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "category");
    }

    @Test
    @DisplayName("mixed scalar IN operands are rejected uniformly before translation")
    void mixedScalarInOperandsAreRejected() {
        Expression ast = ExpressionParser.parse("value IN (@number, @text)");
        Map<String, Object> parameters = Map.of("number", 1, "text", "1");

        assertMixedScalarRejected(ast, parameters);
    }

    // ---- BETWEEN expression ----

    @Test
    @DisplayName("BETWEEN with parameters")
    void betweenWithParameters() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        Map<String, Object> params = Map.of("min", 18, "max", 65);

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(c.age BETWEEN @min AND @max)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(age BETWEEN ? AND ?)", dynRes.whereClause());
        assertEquals(List.of(18, 65), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spnRes, "age");
    }

    @Test
    @DisplayName("BETWEEN with numeric literals")
    void betweenWithLiterals() {
        Expression ast = ExpressionParser.parse("price BETWEEN 10 AND 100");

        assertEquals("(c.price BETWEEN 10 AND 100)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("(price BETWEEN 10 AND 100)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, EMPTY_PARAMS, TABLE), "price");
    }

    @Test
    @DisplayName("mixed scalar BETWEEN bounds are rejected uniformly before translation")
    void mixedScalarBetweenBoundsAreRejected() {
        Expression ast = ExpressionParser.parse("value BETWEEN @low AND @high");
        Map<String, Object> parameters = Map.of("low", 1, "high", true);

        assertMixedScalarRejected(ast, parameters);
    }

    @Test
    @DisplayName("BETWEEN combined with trailing AND keeps inner parens")
    void betweenWithTrailingAnd() {
        // This is the exact form that motivated the parens wrap on Cosmos:
        // without the wrapping parens, the Cosmos NoSQL parser greedily binds
        // BETWEEN's inner AND together with the trailing logical AND, raising
        // BadRequest "Syntax error, incorrect syntax near 'AND'". A future
        // refactor that drops the parens because the standalone form parses
        // fine on every backend would re-introduce that production bug — this
        // test pins the parenthesised contract for the failure shape itself.
        Expression ast = ExpressionParser.parse(
                "age BETWEEN @lo AND @hi AND marker = @m");
        Map<String, Object> params = Map.of("lo", 18, "hi", 65, "m", "x");

        // Outer parens wrap the whole logical AND — the translators emit parens
        // around every binary AND expression. The inner (BETWEEN ...) parens are
        // what this test pins: without them, Cosmos NoSQL's parser greedily
        // binds BETWEEN's inner AND with the trailing logical AND.
        assertEquals("((c.age BETWEEN @lo AND @hi) AND c.marker = @m)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("((age BETWEEN ? AND ?) AND marker = ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        TranslatedQuery spannerResult = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spannerResult, "age");
        assertSpannerEnvelopeField(spannerResult, "marker");
    }

    // ---- Dot notation ----

    @Test
    @DisplayName("dot notation field")
    void dotNotationField() {
        Expression ast = ExpressionParser.parse("address.city = @city");
        Map<String, Object> params = Map.of("city", "Amsterdam");

        assertEquals("c.address.city = @city",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("address.city = ?",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertSpannerEnvelopeField(spanner.translate(ast, params, TABLE), "address.city");
    }

    // ---- Full query string format ----

    @Test
    @DisplayName("Cosmos full query has SELECT * FROM c WHERE prefix")
    void cosmosFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = cosmos.translate(ast, EMPTY_PARAMS, TABLE);
        assertTrue(result.queryString().startsWith("SELECT * FROM c WHERE "));
    }

    @Test
    @DisplayName("DynamoDB full query has double-quoted table name")
    void dynamoFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = dynamo.translate(ast, EMPTY_PARAMS, "my_table");
        assertTrue(result.queryString().startsWith("SELECT * FROM \"my_table\" WHERE "));
    }

    @Test
    @DisplayName("Spanner full query has bare table name")
    void spannerFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = spanner.translate(ast, EMPTY_PARAMS, "my_table");
        assertTrue(result.queryString().startsWith("SELECT r.* FROM my_table AS r WHERE "));
    }

    // ---- Parameter propagation ----

    @Test
    @DisplayName("Cosmos passes named parameters from input map")
    void cosmosNamedParameterPropagation() {
        Expression ast = ExpressionParser.parse("a = @val AND b = @other");
        Map<String, Object> params = Map.of("val", "hello", "other", 42);

        TranslatedQuery result = cosmos.translate(ast, params, TABLE);
        assertEquals("hello", result.namedParameters().get("@val"));
        assertEquals(42, result.namedParameters().get("@other"));
    }

    @Test
    @DisplayName("Dynamo produces positional parameters in expression order")
    void dynamoPositionalOrder() {
        Expression ast = ExpressionParser.parse("a = @first AND b = @second");
        Map<String, Object> params = Map.of("first", "A", "second", "B");

        TranslatedQuery result = dynamo.translate(ast, params, TABLE);
        assertEquals(List.of("A", "B"), result.positionalParameters());
    }

    // ---- Complex combined queries ----

    @Test
    @DisplayName("function + comparison AND chain")
    void functionPlusComparisonAndChain() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix) AND status = @status");
        Map<String, Object> params = Map.of("prefix", "abc", "status", "active");

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(STARTSWITH(c.name, @prefix) AND c.status = @status)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(begins_with(name, ?) AND status = ?)", dynRes.whereClause());
        assertEquals(List.of("abc", "active"), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertSpannerEnvelopeField(spnRes, "name");
        assertSpannerEnvelopeField(spnRes, "status");
    }

    private void assertMixedScalarRejected(Expression expression, Map<String, Object> parameters) {
        assertThrows(ExpressionValidationException.class,
                () -> cosmos.translate(expression, parameters, TABLE));
        assertThrows(ExpressionValidationException.class,
                () -> dynamo.translate(expression, parameters, TABLE));
        assertThrows(ExpressionValidationException.class,
                () -> spanner.translate(expression, parameters, TABLE));
    }
}
