// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us1b;

import com.multiclouddb.api.query.*;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExpressionValidator} (supplementary to T045-T046).
 */
@DisplayName("ExpressionValidator")
class ExpressionValidatorTest {

    @Test
    @DisplayName("valid parameters pass validation")
    void validParameters() {
        Expression ast = ExpressionParser.parse("status = @status AND age > @age");
        Map<String, Object> params = Map.of("status", "active", "age", 18);
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("missing parameter throws ExpressionValidationException")
    void missingParameter() {
        Expression ast = ExpressionParser.parse("status = @status AND age > @age");
        Map<String, Object> params = Map.of("status", "active");
        // "age" is missing
        ExpressionValidationException ex = assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, params));
        assertFalse(ex.getErrors().isEmpty());
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("age")));
    }

    @Test
    @DisplayName("no parameters required when only literals used")
    void noParametersRequired() {
        Expression ast = ExpressionParser.parse("status = 'active'");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, Map.of()));
    }

    @Test
    @DisplayName("null parameters map with parameter reference throws")
    void nullParametersMap() {
        Expression ast = ExpressionParser.parse("status = @status");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, null));
    }

    @Test
    @DisplayName("function parameter references are validated")
    void functionParameterValidation() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        Map<String, Object> params = Map.of("prefix", "abc");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("function with missing parameter throws")
    void functionMissingParameter() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of()));
    }

    @Test
    @DisplayName("IN expression parameter references are validated")
    void inParameterValidation() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        Map<String, Object> params = Map.of("a", "x", "b", "y");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("BETWEEN parameter references are validated")
    void betweenParameterValidation() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        Map<String, Object> params = Map.of("min", 1, "max", 100);
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    // ---- Homogeneous scalar operands ----
    //
    // Spanner must pick one JSON coercion for the whole IN/BETWEEN predicate,
    // while Cosmos and DynamoDB compare each operand in its native kind. A mixed
    // predicate would therefore return different rows per provider, so the
    // portable contract rejects it up front rather than letting it diverge.

    @Test
    @DisplayName("mixed scalar kinds in an IN list are rejected")
    void mixedScalarKindsInListAreRejected() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        ExpressionValidationException ex = assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of("a", "open", "b", 2)));
        assertTrue(ex.getErrors().stream()
                        .anyMatch(e -> e.contains("IN operands must use one scalar kind")),
                () -> "expected a scalar-kind error, got " + ex.getErrors());
    }

    @Test
    @DisplayName("mixed scalar kinds in BETWEEN bounds are rejected")
    void mixedScalarKindsInBetweenBoundsAreRejected() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        ExpressionValidationException ex = assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of("min", 1, "max", "ten")));
        assertTrue(ex.getErrors().stream()
                        .anyMatch(e -> e.contains("BETWEEN operands must use one scalar kind")),
                () -> "expected a scalar-kind error, got " + ex.getErrors());
    }

    @Test
    @DisplayName("mixed literal kinds are rejected the same way as parameters")
    void mixedLiteralKindsAreRejected() {
        Expression ast = ExpressionParser.parse("status IN ('open', 2)");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of()));
    }

    @Test
    @DisplayName("an unsupported operand kind is rejected")
    void unsupportedOperandKindIsRejected() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        ExpressionValidationException ex = assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast,
                        Map.of("a", "open", "b", java.util.List.of("nested"))));
        assertTrue(ex.getErrors().stream()
                        .anyMatch(e -> e.contains("must be String, Number, Boolean, or null")),
                () -> "expected an unsupported-kind error, got " + ex.getErrors());
    }

    @Test
    @DisplayName("a null operand does not trip the scalar-kind rule")
    void nullOperandIsCompatibleWithAnyKind() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("a", "open");
        params.put("b", null);
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params),
                "null keeps the portable never-matches semantics; it is not a competing kind");
    }

    @Test
    @DisplayName("boolean operands are a kind of their own")
    void booleanAndStringOperandsAreRejected() {
        Expression ast = ExpressionParser.parse("flag IN (@a, @b)");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of("a", true, "b", "true")));
    }
}
