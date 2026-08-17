// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a parsed expression AST before translation.
 * <p>
 * Checks that all {@code @param} references in the expression have
 * corresponding entries in the provided parameters map. It also enforces the
 * portable range contract: non-null {@code IN} members and {@code BETWEEN}
 * bounds must be one scalar kind ({@link String}, {@link Number}, or
 * {@link Boolean}), because providers do not share a heterogeneous comparison
 * or coercion rule.
 */
public final class ExpressionValidator {

    private ExpressionValidator() {
    }

    /**
     * Validate an expression tree against the provided parameters.
     *
     * @param expression the parsed expression AST
     * @param parameters the parameter map (may be null or empty)
     * @throws ExpressionValidationException if validation fails
     */
    public static void validate(Expression expression, Map<String, Object> parameters) {
        List<String> errors = new ArrayList<>();
        collectErrors(expression, parameters, errors);
        if (!errors.isEmpty()) {
            throw new ExpressionValidationException(errors);
        }
    }

    private static void collectErrors(Expression expr, Map<String, Object> params, List<String> errors) {
        if (expr instanceof ComparisonExpression comp) {
            checkValue(comp.operand(), params, errors);
        } else if (expr instanceof LogicalExpression logical) {
            collectErrors(logical.left(), params, errors);
            collectErrors(logical.right(), params, errors);
        } else if (expr instanceof NotExpression not) {
            collectErrors(not.child(), params, errors);
        } else if (expr instanceof FunctionCallExpression func) {
            for (Object arg : func.arguments()) {
                checkValue(arg, params, errors);
            }
        } else if (expr instanceof InExpression in) {
            validateHomogeneousOperands("IN", in.values(), params, errors);
        } else if (expr instanceof BetweenExpression between) {
            validateHomogeneousOperands("BETWEEN", List.of(between.low(), between.high()),
                    params, errors);
        }
    }

    private static void checkValue(Object value, Map<String, Object> params, List<String> errors) {
        if (value instanceof Parameter param) {
            if (params == null || !params.containsKey(param.name())) {
                errors.add("Parameter '@" + param.name()
                        + "' is referenced in the expression but not provided in the parameters map");
            }
        }
    }

    /**
     * Providers disagree about heterogeneous scalar comparisons. In particular,
     * Spanner must choose one JSON coercion for the whole IN/BETWEEN predicate,
     * while Cosmos and DynamoDB preserve each operand's native kind. The
     * portable contract therefore accepts only one non-null scalar kind per
     * range or membership predicate. A null operand retains the existing
     * portable FALSE semantics and is not compared with the other operands.
     */
    private static void validateHomogeneousOperands(String operation, List<Object> operands,
            Map<String, Object> params, List<String> errors) {
        ScalarKind expected = null;
        for (Object operand : operands) {
            checkValue(operand, params, errors);
            ScalarKind kind = scalarKind(operand, params);
            if (kind == ScalarKind.UNRESOLVED) {
                continue;
            }
            if (kind == ScalarKind.NULL) {
                continue;
            }
            if (kind == ScalarKind.UNSUPPORTED) {
                errors.add(operation + " operands must be String, Number, Boolean, or null; got "
                        + operandDescription(operand, params));
                continue;
            }
            if (expected == null) {
                expected = kind;
            } else if (expected != kind) {
                errors.add(operation + " operands must use one scalar kind; found "
                        + expected.displayName + " and " + kind.displayName);
                return;
            }
        }
    }

    private static ScalarKind scalarKind(Object operand, Map<String, Object> params) {
        Object value;
        if (operand instanceof Parameter parameter) {
            if (params == null || !params.containsKey(parameter.name())) {
                return ScalarKind.UNRESOLVED;
            }
            value = params.get(parameter.name());
        } else if (operand instanceof Literal literal) {
            value = literal.value();
        } else {
            return ScalarKind.UNSUPPORTED;
        }

        if (value == null) {
            return ScalarKind.NULL;
        }
        if (value instanceof Number) {
            return ScalarKind.NUMBER;
        }
        if (value instanceof Boolean) {
            return ScalarKind.BOOLEAN;
        }
        if (value instanceof String) {
            return ScalarKind.STRING;
        }
        return ScalarKind.UNSUPPORTED;
    }

    private static String operandDescription(Object operand, Map<String, Object> params) {
        Object value = operand instanceof Parameter parameter && params != null
                ? params.get(parameter.name())
                : operand instanceof Literal literal ? literal.value() : operand;
        return value == null ? "null" : value.getClass().getName();
    }

    private enum ScalarKind {
        STRING("String"),
        NUMBER("Number"),
        BOOLEAN("Boolean"),
        NULL("null"),
        UNRESOLVED("unresolved parameter"),
        UNSUPPORTED("unsupported value");

        private final String displayName;

        ScalarKind(String displayName) {
            this.displayName = displayName;
        }
    }
}
