// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchNumericDomainTest {

    @Test
    void integralResultsUseTheSigned64BitPortableDomain() {
        assertEquals(Long.MAX_VALUE, PatchNumericDomain.add(Long.MAX_VALUE - 1, 1));
        assertEquals(Long.MIN_VALUE, PatchNumericDomain.add(Long.MIN_VALUE + 1, -1));

        assertThrows(IllegalArgumentException.class,
                () -> PatchNumericDomain.add(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PatchNumericDomain.add(Long.MIN_VALUE, -1));
    }

    @Test
    void integralResultBoundsMatchTheAtomicProviderPredicates() {
        assertEquals(Long.MAX_VALUE - 1L,
                PatchNumericDomain.maximumBaseForIntegralDelta(1L).longValueExact());
        assertEquals(Long.MIN_VALUE + 1L,
                PatchNumericDomain.minimumBaseForIntegralDelta(-1L).longValueExact());
        assertTrue(PatchNumericDomain.isIntegralResultOutsideRange(Long.MAX_VALUE, 1));
        assertTrue(PatchNumericDomain.isIntegralResultOutsideRange(Long.MIN_VALUE, -1));
        assertFalse(PatchNumericDomain.isIntegralResultOutsideRange(Long.MAX_VALUE - 1L, 1));
        assertFalse(PatchNumericDomain.isIntegralResultOutsideRange(Long.MIN_VALUE + 1L, -1));
    }

    /**
     * Locks the divergence that {@link com.multiclouddb.api.Capability#EXACT_FRACTIONAL_INCREMENT}
     * declares: the delta is portable, the accumulated fractional result is not.
     * Cosmos and Spanner evaluate in binary64 (this method), while DynamoDB adds
     * in the exact-decimal {@code N} type and would store {@code 0.3}.
     */
    @Test
    void fractionalResultsAccumulateInBinary64NotExactDecimal() {
        assertEquals(0.2d, PatchNumericDomain.normalize(new java.math.BigDecimal("0.2")),
                "the normalized delta itself is the same IEEE-754 value on every provider");

        assertEquals(0.30000000000000004d, (Double) PatchNumericDomain.add(0.1d, 0.2d),
                "binary64 accumulation, not DynamoDB's exact decimal 0.3");
        assertNotEquals(0.3d, PatchNumericDomain.add(0.1d, 0.2d));

        assertEquals(1.5d, (Double) PatchNumericDomain.add(1, 0.5d),
                "exactly representable fractions stay identical on all three providers");
    }

    /**
     * Pins the documented — and deliberately asymmetric — result rule: the bound
     * is chosen by the <em>normalized</em> delta, not by the Java type the caller
     * passed. {@code normalize} folds any whole-valued floating delta to a
     * {@code Long}, so {@code 1.0d} takes the signed-64-bit result bound that
     * {@code 1L} takes, while {@code 1.5d} stays fractional and only has to
     * remain finite. Changing any of these outcomes is a breaking change to the
     * portable increment contract.
     */
    @Test
    void theResultBoundIsChosenByTheNormalizedDeltaNotTheCallersJavaType() {
        double base = 1e300;

        assertTrue(PatchNumericDomain.isIntegralDelta(1),
                "an integral literal normalizes to Long");
        assertTrue(PatchNumericDomain.isIntegralDelta(1.0d),
                "a whole-valued double folds to Long, so it takes the integral rule");
        assertFalse(PatchNumericDomain.isIntegralDelta(1.5d),
                "a genuinely fractional double keeps the fractional rule");

        assertThrows(IllegalArgumentException.class, () -> PatchNumericDomain.add(base, 1),
                "an integral result must round-trip as a Long, and 1e300 + 1 cannot");
        assertThrows(IllegalArgumentException.class, () -> PatchNumericDomain.add(base, 1.0d),
                "1.0 folds to the integral delta 1L, so it is rejected exactly like 1");
        assertEquals(1e300, (Double) PatchNumericDomain.add(base, 1.5d),
                "a fractional result is never promised as a Long, so only finiteness applies");

        assertTrue(PatchNumericDomain.isIntegralResultOutsideRange(base, 1));
        assertTrue(PatchNumericDomain.isIntegralResultOutsideRange(base, 1.0d));
        assertFalse(PatchNumericDomain.isIntegralResultOutsideRange(base, 1.5d),
                "a fractional delta reports false because no 64-bit bound applies, "
                        + "not because the result is small");
    }

    /**
     * {@code add} rejects a null {@code current} explicitly; the classifier used
     * by the DynamoDB adapter must agree rather than surfacing a raw
     * {@link NullPointerException} from its internal decimal conversion.
     */
    @Test
    void bothEntryPointsRejectANullCurrentValueTheSameWay() {
        assertThrows(IllegalArgumentException.class, () -> PatchNumericDomain.add(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PatchNumericDomain.isIntegralResultOutsideRange(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PatchNumericDomain.isIntegralResultOutsideRange(null, 1.5d));
    }
}
