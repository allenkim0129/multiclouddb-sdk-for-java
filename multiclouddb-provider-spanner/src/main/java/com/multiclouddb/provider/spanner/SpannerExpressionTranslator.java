// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.query.BetweenExpression;
import com.multiclouddb.api.query.ComparisonExpression;
import com.multiclouddb.api.query.Expression;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.api.query.ExpressionValidator;
import com.multiclouddb.api.query.FieldRef;
import com.multiclouddb.api.query.FunctionCallExpression;
import com.multiclouddb.api.query.InExpression;
import com.multiclouddb.api.query.Literal;
import com.multiclouddb.api.query.LogicalExpression;
import com.multiclouddb.api.query.NotExpression;
import com.multiclouddb.api.query.Parameter;
import com.multiclouddb.api.query.TranslatedQuery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a portable expression AST into Google Cloud Spanner GoogleSQL
 * syntax.
 * <p>
 * Spanner GoogleSQL conventions:
 * <ul>
 * <li>Portable fields read the authoritative {@code data} envelope via JSON
 *     functions, with a physical-column fallback for legacy rows</li>
 * <li>Parameters use {@code @paramName} notation</li>
 * <li>Functions: STARTS_WITH, STRPOS(field,value)&gt;0 for contains,
 * JSON_TYPE for field_exists, CHAR_LENGTH, ARRAY_LENGTH</li>
 * </ul>
 */
public final class SpannerExpressionTranslator implements ExpressionTranslator {

    static final String ROW_ALIAS = "r";

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        ExpressionValidator.validate(expression, parameters);
        StringBuilder where = new StringBuilder();
        Map<String, Object> namedParams = new LinkedHashMap<>();

        translateExpression(expression, where, parameters, namedParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT " + ROW_ALIAS + ".* FROM " + container + " AS " + ROW_ALIAS
                + " WHERE " + whereClause;

        return TranslatedQuery.withNamedParameters(fullQuery, whereClause, namedParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        if (expr instanceof ComparisonExpression comp) {
            ValueKind kind = valueKind(comp.operand(), srcParams);
            if (kind == ValueKind.NULL) {
                appendNullComparison(comp, sb);
                return;
            }
            sb.append('(');
            appendTypeGuard(comp.field(), kind, sb);
            sb.append(" AND ");
            appendField(comp.field(), kind, sb);
            sb.append(' ').append(comp.op().symbol()).append(' ');
            appendValue(comp.operand(), kind, sb, srcParams, outParams);
            sb.append(')');

        } else if (expr instanceof LogicalExpression logical) {
            sb.append('(');
            translateExpression(logical.left(), sb, srcParams, outParams);
            sb.append(' ').append(logical.op().name()).append(' ');
            translateExpression(logical.right(), sb, srcParams, outParams);
            sb.append(')');

        } else if (expr instanceof NotExpression not) {
            sb.append("NOT (");
            translateExpression(not.child(), sb, srcParams, outParams);
            sb.append(')');

        } else if (expr instanceof FunctionCallExpression func) {
            translateFunction(func, sb, srcParams, outParams);

        } else if (expr instanceof InExpression in) {
            if (in.values().isEmpty()) {
                // Membership in an empty set is unsatisfiable. `InExpression`
                // already rejects an empty list in its canonical constructor, so
                // this is defence-in-depth for any AST built around that record;
                // emitting FALSE keeps the degenerate-IN behaviour identical to
                // the null-operand branch below and to the Cosmos / DynamoDB
                // translators, instead of leaking a raw IndexOutOfBoundsException
                // out of the portable translate() surface.
                sb.append("FALSE");
                return;
            }
            if (hasNullOperand(in.values(), srcParams)) {
                sb.append("FALSE");
                return;
            }
            ValueKind kind = valueKind(in.values().get(0), srcParams);
            sb.append('(');
            appendTypeGuard(in.field(), kind, sb);
            sb.append(" AND ");
            appendField(in.field(), kind, sb);
            sb.append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0)
                    sb.append(", ");
                appendValue(in.values().get(i), kind, sb, srcParams, outParams);
            }
            sb.append("))");

        } else if (expr instanceof BetweenExpression between) {
            if (isNullOperand(between.low(), srcParams) || isNullOperand(between.high(), srcParams)) {
                sb.append("FALSE");
                return;
            }
            ValueKind kind = valueKind(between.low(), srcParams);
            sb.append('(');
            appendTypeGuard(between.field(), kind, sb);
            sb.append(" AND ");
            appendField(between.field(), kind, sb);
            sb.append(" BETWEEN ");
            appendValue(between.low(), kind, sb, srcParams, outParams);
            sb.append(" AND ");
            appendValue(between.high(), kind, sb, srcParams, outParams);
            sb.append(')');
        }
    }

    private static boolean hasNullOperand(Iterable<Object> operands, Map<String, Object> parameters) {
        for (Object operand : operands) {
            if (isNullOperand(operand, parameters)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullOperand(Object operand, Map<String, Object> parameters) {
        if (operand instanceof Parameter parameter) {
            return parameters == null || parameters.get(parameter.name()) == null;
        }
        return operand instanceof Literal literal && literal.value() == null;
    }

    private void translateFunction(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        switch (func.function()) {
            case STARTS_WITH -> {
                sb.append("STARTS_WITH(");
                appendFunctionArgs(func, ValueKind.STRING, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                // STRPOS(field, value) > 0
                if (func.arguments().size() >= 2) {
                    sb.append("STRPOS(");
                    appendFunctionArgs(func, ValueKind.STRING, sb, srcParams, outParams);
                    sb.append(") > 0");
                }
            }
            case FIELD_EXISTS -> {
                if (!func.arguments().isEmpty() && func.arguments().get(0) instanceof FieldRef field) {
                    // COALESCE(..., FALSE) is load-bearing, not defensive noise.
                    // JSON_QUERY over an absent path yields SQL NULL, so a bare
                    // `JSON_TYPE(<accessor>) != 'null'` is SQL NULL — not FALSE —
                    // for a missing field. As a top-level predicate NULL excludes
                    // the row correctly, but under NotExpression `NOT NULL` stays
                    // NULL and the row is excluded again, while Cosmos
                    // (`NOT (IS_DEFINED(c.x) AND NOT IS_NULL(c.x))`) and DynamoDB
                    // (`NOT (x IS NOT MISSING AND x IS NOT NULL)`) both evaluate to
                    // TRUE and include it. Folding NULL to FALSE here makes
                    // `field_exists` three-valued-free and therefore negatable
                    // identically on all three providers; the non-negated truth
                    // value is unchanged.
                    sb.append("COALESCE(JSON_TYPE(").append(jsonField(field))
                            .append(") != 'null', FALSE)");
                }
            }
            case STRING_LENGTH -> {
                sb.append("CHAR_LENGTH(");
                appendFunctionArgs(func, ValueKind.STRING, sb, srcParams, outParams);
                sb.append(')');
            }
            case COLLECTION_SIZE -> {
                if (!func.arguments().isEmpty() && func.arguments().get(0) instanceof FieldRef field) {
                    sb.append("ARRAY_LENGTH(JSON_QUERY_ARRAY(").append(jsonField(field))
                            .append(", '$'))");
                }
            }
        }
    }

    private void appendFunctionArgs(FunctionCallExpression func, ValueKind kind, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        for (int i = 0; i < func.arguments().size(); i++) {
            if (i > 0)
                sb.append(", ");
            Object arg = func.arguments().get(i);
            if (arg instanceof FieldRef field) {
                appendField(field, kind, sb);
            } else {
                appendValue(arg, kind, sb, srcParams, outParams);
            }
        }
    }

    private void appendField(FieldRef field, ValueKind kind, StringBuilder sb) {
        String value = jsonField(field);
        switch (kind) {
            case NUMBER -> sb.append("LAX_FLOAT64(").append(value).append(')');
            case BOOLEAN -> sb.append("LAX_BOOL(").append(value).append(')');
            case STRING, NULL -> sb.append("LAX_STRING(").append(value).append(')');
        }
    }

    private void appendTypeGuard(FieldRef field, ValueKind kind, StringBuilder sb) {
        if (kind == ValueKind.NULL) {
            return;
        }
        sb.append("JSON_TYPE(").append(jsonField(field)).append(") = '")
                .append(jsonType(kind)).append('\'');
    }

    private static String jsonType(ValueKind kind) {
        return switch (kind) {
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case NULL -> "null";
        };
    }

    private static void appendNullComparison(ComparisonExpression comparison, StringBuilder sb) {
        String fieldType = "JSON_TYPE(" + jsonField(comparison.field()) + ")";
        switch (comparison.op()) {
            case EQ -> sb.append(fieldType).append(" = 'null'");
            case NE -> sb.append(fieldType).append(" != 'null'");
            // Relational comparisons with NULL have no portable truth value.
            case LT, GT, LE, GE -> sb.append("FALSE");
        }
    }

    private void appendValue(Object value, ValueKind kind, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        if (value instanceof Parameter param) {
            String paramName = "@" + param.name();
            appendTypedValue(paramName, kind, sb);
            if (srcParams != null && srcParams.containsKey(param.name())) {
                outParams.put(paramName, srcParams.get(param.name()));
            }
        } else if (value instanceof Literal lit) {
            appendLiteral(lit, kind, sb);
        }
    }

    private void appendTypedValue(String value, ValueKind kind, StringBuilder sb) {
        if (kind == ValueKind.NUMBER) {
            sb.append("CAST(").append(value).append(" AS FLOAT64)");
        } else {
            sb.append(value);
        }
    }

    private void appendLiteral(Literal lit, ValueKind kind, StringBuilder sb) {
        if (kind == ValueKind.NUMBER) {
            sb.append("CAST(").append(lit.value()).append(" AS FLOAT64)");
            return;
        }
        if (lit.value() == null) {
            sb.append("NULL");
        } else if (lit.value() instanceof String s) {
            sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else {
            sb.append(lit.value());
        }
    }

    private static ValueKind valueKind(Object operand, Map<String, Object> parameters) {
        Object value = operand instanceof Parameter parameter
                ? parameters == null ? null : parameters.get(parameter.name())
                : operand instanceof Literal literal ? literal.value() : null;
        if (value == null) {
            return ValueKind.NULL;
        }
        if (value instanceof Number) {
            return ValueKind.NUMBER;
        }
        if (value instanceof Boolean) {
            return ValueKind.BOOLEAN;
        }
        return ValueKind.STRING;
    }

    /**
     * The envelope is authoritative for every SDK-written row. The fallback is
     * selected once per row, not once per field: a missing field in a valid
     * envelope remains missing even if an old physical column contains a stale
     * value. {@code SAFE.PARSE_JSON} makes malformed or legacy metadata fall
     * through to the same physical projection used by {@link SpannerRowMapper}.
     */
    private static String jsonField(FieldRef field) {
        return jsonField(field.name());
    }

    /**
     * Envelope-authoritative accessor for a portable document field name. The
     * emitted SQL references the {@link #ROW_ALIAS} row alias, so the enclosing
     * statement must expose the scanned table as {@code AS r}.
     * <p>
     * The legacy-row fallback projects the physical columns with {@code TO_JSON(r)},
     * which yields a flat object keyed by column name. Do <em>not</em> rewrite this
     * as {@code TO_JSON(STRUCT(r.*))}: star expansion inside {@code STRUCT} is
     * BigQuery-only syntax and Spanner rejects it with
     * {@code INVALID_ARGUMENT: Syntax error: Unexpected "*"}. {@code TO_JSON(STRUCT(r))}
     * parses but is also wrong -- it nests the row under an {@code "r"} key.
     */
    static String jsonField(String field) {
        String envelope = documentEnvelope();
        return "CASE WHEN JSON_TYPE(" + envelope + ") = 'object' THEN JSON_QUERY("
                + envelope + ", '" + jsonPath(field) + "') ELSE JSON_QUERY(TO_JSON("
                + ROW_ALIAS + "), '" + jsonPath(field) + "') END";
    }

    /**
     * Builds the {@code ORDER BY} sort-key list for a portable document field,
     * reading the same authoritative {@code data} envelope (with the same
     * legacy-row physical fallback) that {@link #jsonField(String)} produces
     * for the {@code WHERE} clause.
     * <p>
     * Sorting the raw {@code JSON_QUERY} result would compare JSON <em>text</em>
     * (so {@code 10} would sort before {@code 9}), and a bare physical column
     * reference cannot see dynamic top-level fields at all — nor a value whose
     * mirror was cleared to typed {@code NULL} because it no longer matched the
     * column type. The emitted list therefore extracts a typed scalar:
     * <ol>
     *   <li>a type-rank so JSON kinds never interleave. The rank reproduces
     *       Cosmos NoSQL's documented total order —
     *       {@code null/absent (1) < boolean (2) < number (3) < string (4)} —
     *       because DynamoDB declares {@code ORDER_BY} unsupported, which leaves
     *       Cosmos and Spanner as the only two providers on this surface: a
     *       different rank here would be an ungated cross-provider divergence.
     *       The rank carries the caller's direction, so {@code DESC} is the exact
     *       reverse of {@code ASC};</li>
     *   <li>{@code LAX_FLOAT64} — numeric ordering within the number rank;</li>
     *   <li>{@code LAX_STRING} — lexicographic ordering within the string rank;</li>
     *   <li>{@code LAX_BOOL} — {@code FALSE} before {@code TRUE} within the
     *       boolean rank.</li>
     * </ol>
     * Ranks 2–4 evaluate to SQL {@code NULL} outside their own rank, so they act
     * purely as tie-breakers within a rank and never reorder across ranks.
     *
     * @param field     the portable document field name
     * @param direction the GoogleSQL sort direction ({@code ASC} / {@code DESC});
     *                  may be {@code null} for the engine default
     * @return a comma-separated GoogleSQL {@code ORDER BY} sort-key list
     */
    static String orderByExpression(String field, String direction) {
        String accessor = jsonField(field);
        String dir = direction == null || direction.isBlank() ? "" : " " + direction;
        // The ELSE arm covers JSON `null`, an absent field (JSON_QUERY yields SQL
        // NULL, which matches no WHEN arm), and any non-scalar kind, keeping them
        // on the single lowest rank exactly as Cosmos sorts undefined / null first.
        return "CASE JSON_TYPE(" + accessor + ")"
                + " WHEN 'boolean' THEN 2 WHEN 'number' THEN 3 WHEN 'string' THEN 4 ELSE 1 END" + dir
                + ", LAX_FLOAT64(" + accessor + ")" + dir
                + ", LAX_STRING(" + accessor + ")" + dir
                + ", LAX_BOOL(" + accessor + ")" + dir;
    }

    private static String documentEnvelope() {
        return "JSON_QUERY(SAFE.PARSE_JSON(" + ROW_ALIAS + ".data), '$."
                + SpannerConstants.FIELD_DATA_DOCUMENT + "')";
    }

    private static String jsonPath(String field) {
        StringBuilder path = new StringBuilder("$");
        for (String segment : field.split("\\.", -1)) {
            path.append(".\"").append(segment.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return path.toString();
    }

    private enum ValueKind {
        NUMBER,
        BOOLEAN,
        STRING,
        NULL
    }
}
