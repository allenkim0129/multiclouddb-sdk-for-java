// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a portable expression AST into DynamoDB PartiQL syntax.
 * <p>
 * PartiQL conventions:
 * <ul>
 * <li>Table names are double-quoted: {@code "tableName"}</li>
 * <li>Uses positional {@code ?} parameters in query order</li>
 * <li>Functions: begins_with, contains, IS NOT MISSING, char_length, size</li>
 * </ul>
 */
public final class DynamoExpressionTranslator implements ExpressionTranslator {

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        ExpressionValidator.validate(expression, parameters);
        StringBuilder where = new StringBuilder();
        List<Object> positionalParams = new ArrayList<>();

        translateExpression(expression, where, parameters, positionalParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT * FROM \"" + container + "\" WHERE " + whereClause;

        return TranslatedQuery.withPositionalParameters(fullQuery, whereClause, positionalParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
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
                // the null-operand branch below and to the Cosmos / Spanner
                // translators, instead of emitting the invalid `field IN ()`.
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
            // Wrap in parentheses for consistency with sibling translators.
            // PartiQL parses the un-parenthesised form correctly (its operator
            // precedence binds BETWEEN tighter than logical AND), so this is
            // not strictly required here — but uniform output across providers
            // simplifies cross-provider debugging and query stitching.
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
            List<Object> outParams) {
        switch (func.function()) {
            case STARTS_WITH -> {
                sb.append("begins_with(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                sb.append("contains(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case FIELD_EXISTS -> {
                // A present PartiQL NULL is not a portable existing field.
                if (!func.arguments().isEmpty() && func.arguments().get(0) instanceof FieldRef field) {
                    String accessor = fieldRef(field.name());
                    sb.append('(').append(accessor).append(" IS NOT MISSING AND ")
                            .append(accessor).append(" IS NOT NULL)");
                }
            }
            case STRING_LENGTH -> {
                sb.append("char_length(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case COLLECTION_SIZE -> {
                sb.append("size(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
        }
    }

    /**
     * Renders a portable field reference as a quoted PartiQL identifier.
     * <p>
     * A bare identifier collides with PartiQL reserved words: a document field
     * named {@code value} emits {@code value > ?} and DynamoDB rejects the
     * statement with {@code Statement wasn't well formed}. This adapter already
     * quotes its own partition key ({@link DynamoConstants#PARTIQL_PARTITION_KEY})
     * and compiles patch paths to {@code ExpressionAttributeNames} placeholders for
     * exactly this reason, so leaving caller-supplied query fields bare meant a
     * field could be patched but never queried.
     * <p>
     * Dotted names are nested references ({@code fieldRef ::= IDENTIFIER ('.'
     * IDENTIFIER)*}), so each segment is quoted separately: {@code address.city}
     * becomes {@code "address"."city"}, not a single literal attribute name.
     */
    private static String fieldRef(String name) {
        StringBuilder accessor = new StringBuilder();
        String[] segments = name.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                accessor.append('.');
            }
            accessor.append('"').append(segments[i].replace("\"", "\"\"")).append('"');
        }
        return accessor.toString();
    }

    private void appendFunctionArgs(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
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
            List<Object> outParams) {
        if (value instanceof Parameter param) {
            sb.append('?');
            if (srcParams != null && srcParams.containsKey(param.name())) {
                outParams.add(srcParams.get(param.name()));
            }
        } else if (value instanceof Literal lit) {
            appendLiteral(lit, sb);
        }
    }

    private void appendLiteral(Literal lit, StringBuilder sb) {
        if (lit.value() == null) {
            sb.append("NULL");
        } else if (lit.value() instanceof String s) {
            sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else {
            sb.append(lit.value());
        }
    }
}
