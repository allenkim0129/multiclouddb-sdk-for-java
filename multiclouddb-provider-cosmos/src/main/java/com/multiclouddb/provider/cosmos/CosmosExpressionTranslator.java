// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

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
 * Translates a portable expression AST into Cosmos DB SQL syntax.
 * <p>
 * Cosmos SQL conventions:
 * <ul>
 * <li>Fields are prefixed with {@code c.} (container alias)</li>
 * <li>Parameters use {@code @paramName} notation</li>
 * <li>Functions: STARTSWITH, CONTAINS, IS_DEFINED, LENGTH, ARRAY_LENGTH</li>
 * </ul>
 */
public final class CosmosExpressionTranslator implements ExpressionTranslator {

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        ExpressionValidator.validate(expression, parameters);
        StringBuilder where = new StringBuilder();
        Map<String, Object> namedParams = new LinkedHashMap<>();

        translateExpression(expression, where, parameters, namedParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT * FROM c WHERE " + whereClause;

        return TranslatedQuery.withNamedParameters(fullQuery, whereClause, namedParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        if (expr instanceof ComparisonExpression comp) {
            sb.append(fieldRef(comp.field().name()));
            sb.append(' ').append(comp.op().symbol()).append(' ');
            appendValue(comp.operand(), sb, srcParams, outParams);

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
                // the null-operand branch below and to the DynamoDB / Spanner
                // translators, instead of emitting the invalid `c.field IN ()`.
                sb.append("FALSE");
                return;
            }
            if (hasNullOperand(in.values(), srcParams)) {
                sb.append("FALSE");
                return;
            }
            sb.append(fieldRef(in.field().name())).append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0)
                    sb.append(", ");
                appendValue(in.values().get(i), sb, srcParams, outParams);
            }
            sb.append(')');

        } else if (expr instanceof BetweenExpression between) {
            if (isNullOperand(between.low(), srcParams) || isNullOperand(between.high(), srcParams)) {
                sb.append("FALSE");
                return;
            }
            // Wrap in parentheses so the inner BETWEEN ... AND ... binds correctly
            // when this expression is combined with an outer logical AND.
            // Without parens Cosmos NoSQL rejects "BETWEEN @lo AND @hi AND ..." with
            // a syntax error near the second AND.
            sb.append('(').append(fieldRef(between.field().name())).append(" BETWEEN ");
            appendValue(between.low(), sb, srcParams, outParams);
            sb.append(" AND ");
            appendValue(between.high(), sb, srcParams, outParams);
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
                sb.append("STARTSWITH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                sb.append("CONTAINS(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case FIELD_EXISTS -> {
                // A present JSON null is distinct from a non-null field.
                sb.append("(IS_DEFINED(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(") AND NOT IS_NULL(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append("))");
            }
            case STRING_LENGTH -> {
                sb.append("LENGTH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case COLLECTION_SIZE -> {
                sb.append("ARRAY_LENGTH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
        }
    }

    private void appendFunctionArgs(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        for (int i = 0; i < func.arguments().size(); i++) {
            if (i > 0)
                sb.append(", ");
            Object arg = func.arguments().get(i);
            if (arg instanceof FieldRef field) {
                sb.append(fieldRef(field.name()));
            } else {
                appendValue(arg, sb, srcParams, outParams);
            }
        }
    }

    private void appendValue(Object value, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        if (value instanceof Parameter param) {
            String paramName = "@" + param.name();
            sb.append(paramName);
            if (srcParams != null && srcParams.containsKey(param.name())) {
                outParams.put(paramName, srcParams.get(param.name()));
            }
        } else if (value instanceof Literal lit) {
            appendLiteral(lit, sb);
        }
    }

    /**
     * Renders a portable field reference as a quoted Cosmos property accessor.
     * <p>
     * The bracket form is used rather than {@code c.<name>} because a bare
     * accessor collides with Cosmos NoSQL reserved words: a document field named
     * {@code value} emits {@code c.value} and the gateway rejects the whole query
     * with {@code Syntax error, incorrect syntax near 'value'}. The same field
     * name is accepted by the Spanner translator (which addresses fields through a
     * JSON path) and by this adapter's own patch path, so leaving it unquoted made
     * one portable query succeed on one provider and fail on another.
     * <p>
     * Dotted names are nested references ({@code fieldRef ::= IDENTIFIER ('.'
     * IDENTIFIER)*}), so each segment is quoted separately: {@code address.city}
     * becomes {@code c["address"]["city"]}, not a single literal key.
     */
    private static String fieldRef(String name) {
        StringBuilder accessor = new StringBuilder("c");
        for (String segment : name.split("\\.", -1)) {
            accessor.append("[\"")
                    .append(segment.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"]");
        }
        return accessor.toString();
    }

    private void appendLiteral(Literal lit, StringBuilder sb) {
        if (lit.value() == null) {
            sb.append("null");
        } else if (lit.value() instanceof String s) {
            sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else {
            sb.append(lit.value());
        }
    }
}
