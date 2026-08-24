// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Defines the portable numeric domain for {@code PatchOperation.INCREMENT}.
 * <p>
 * Integral deltas are normalised to signed 64-bit values, the portable exact
 * integer domain required of every provider that advertises PATCH. Fractional
 * deltas are normalised to finite {@code double}s. The lower magnitude bound
 * reflects DynamoDB's smallest non-zero number; the upper magnitude and
 * round-trip checks guarantee that the decimal representation sent to DynamoDB
 * represents the same value supplied to Cosmos DB.
 * <p>
 * <b>They do not extend to the accumulated result.</b> DynamoDB evaluates
 * {@code x = x + :delta} in its {@code N} type, which is exact decimal
 * arithmetic, while Cosmos evaluates the native patch increment as a JSON
 * binary64 number. Seeding {@code 0.1} and incrementing by {@code 0.2}
 * therefore stores exactly {@code 0.3} on DynamoDB and
 * {@code 0.30000000000000004} on Cosmos DB, and the divergence compounds across
 * repeated fractional increments. Integral results are unaffected — they stay
 * exact because {@link #add(Number, Number)} bounds them to signed 64-bit range.
 * Because that divergence cannot be reconciled without a non-atomic
 * read-modify-write, {@code patch()} does not accept a fractional
 * {@code INCREMENT} delta at all: {@code PatchValidator} rejects one with
 * {@code INVALID_REQUEST} on every provider. The fractional bounds below still
 * govern numbers <em>written</em> by {@code SET} and {@code REPLACE}, which
 * store identically everywhere because no server-side accumulation occurs.
 *
 * <h2>Which result bound applies is decided by the <em>normalized</em> delta,
 * not by the Java type you passed</h2>
 *
 * {@link #add(Number, Number)} applies two different rules to the accumulated
 * result, and which one applies is chosen <em>after</em> normalization:
 * <ul>
 *   <li><b>Integral normalized delta</b> — the result must fit the signed
 *       64-bit interval {@code [Long.MIN_VALUE, Long.MAX_VALUE]}. An integral
 *       result has to round-trip as a {@code Long} on every provider advertising
 *       PATCH, so anything wider is rejected as {@code INVALID_REQUEST}.</li>
 *   <li><b>Fractional normalized delta</b> — the result only has to stay
 *       finite. A fractional result is never promised to round-trip as a
 *       {@code Long}, so no 64-bit bound is imposed on it; the portable
 *       magnitude floor ({@link #MIN_NONZERO_FRACTIONAL_MAGNITUDE}) and ceiling
 *       ({@link #MAX_FRACTIONAL_MAGNITUDE}) constrain the <em>delta</em>, not
 *       the sum.</li>
 * </ul>
 *
 * The subtlety is that {@link #normalize(Number)} folds <em>any</em>
 * whole-valued {@code Float}, {@code Double}, or {@code BigDecimal} down to a
 * {@link Long}. A delta of {@code 1.0d} is therefore an <em>integral</em>
 * delta, identical to {@code 1L}, and picks up the 64-bit result bound — even
 * though the caller wrote a {@code double}. On a field holding {@code 1e300}:
 *
 * <pre>{@code
 * increment("/v", 1)      // integral delta   → INVALID_REQUEST (result > Long.MAX_VALUE)
 * increment("/v", 1.0d)   // folded to 1L     → INVALID_REQUEST (same rule)
 * increment("/v", 1.5d)   // fractional delta → accepted (result is finite)
 * }</pre>
 *
 * A larger delta being accepted where a smaller one is rejected is surprising,
 * but it is deliberate and stable: the accepted case produces a {@code Double}
 * that no conforming provider has to narrow, while the rejected cases would
 * produce an integral value that cannot survive a {@code Long} round-trip. Callers that
 * need to know which rule will apply before dispatch can ask
 * {@link #isIntegralDelta(Number)}, and callers that want to pre-check the
 * bound can use {@link #isIntegralResultOutsideRange(Number, Number)} or
 * {@link #maximumBaseForIntegralDelta(long)} /
 * {@link #minimumBaseForIntegralDelta(long)}.
 */
public final class PatchNumericDomain {

    /** Largest magnitude at which a fractional IEEE-754 double is portable. */
    public static final double MAX_FRACTIONAL_MAGNITUDE = 9_007_199_254_740_991d;

    /** Smallest non-zero magnitude accepted by DynamoDB's numeric type. */
    public static final double MIN_NONZERO_FRACTIONAL_MAGNITUDE = 1e-130d;

    private static final BigInteger MIN_INTEGRAL_RESULT = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_INTEGRAL_RESULT = BigInteger.valueOf(Long.MAX_VALUE);

    private PatchNumericDomain() {
    }

    /**
     * Validates and canonicalises a numeric delta for all providers.
     *
     * @param value caller-provided delta
     * @return a {@link Long} for integral deltas or a {@link Double} for fractional deltas
     * @throws IllegalArgumentException if the delta is outside the portable domain
     */
    public static Number normalize(Number value) {
        if (value == null) {
            throw new IllegalArgumentException("must not be null");
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value.longValue();
        }
        if (value instanceof BigInteger integer) {
            return integral(integer);
        }
        if (value instanceof Float || value instanceof Double) {
            return floating(value.doubleValue());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal(decimal);
        }
        throw new IllegalArgumentException("uses unsupported numeric type " + value.getClass().getName()
                + "; use an integral Java number, BigInteger, Float, Double, or BigDecimal");
    }

    private static Long integral(BigInteger value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("integral deltas must fit signed 64-bit range", e);
        }
    }

    private static Number decimal(BigDecimal value) {
        BigDecimal canonical = value.stripTrailingZeros();
        if (canonical.scale() <= 0) {
            try {
                return canonical.toBigIntegerExact().longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("integral deltas must fit signed 64-bit range", e);
            }
        }

        double converted = canonical.doubleValue();
        requireFiniteFractional(converted);
        if (BigDecimal.valueOf(converted).compareTo(canonical) != 0) {
            throw new IllegalArgumentException("fractional deltas must round-trip through a finite "
                    + "IEEE-754 double without losing decimal precision");
        }
        return converted;
    }

    private static Number floating(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("must be finite");
        }
        if (value == Math.rint(value)) {
            try {
                return BigDecimal.valueOf(value).toBigIntegerExact().longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("integral deltas must be exactly representable as signed 64-bit values");
            }
        }
        requireFiniteFractional(value);
        return value;
    }

    private static void requireFiniteFractional(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("fractional deltas must be finite");
        }
        if (Math.abs(value) > MAX_FRACTIONAL_MAGNITUDE) {
            throw new IllegalArgumentException("fractional deltas must not exceed "
                    + (long) MAX_FRACTIONAL_MAGNITUDE
                    + " in magnitude so all providers preserve their IEEE-754 value");
        }
        if (value != 0.0d && Math.abs(value) < MIN_NONZERO_FRACTIONAL_MAGNITUDE) {
            throw new IllegalArgumentException("non-zero fractional deltas must be at least "
                    + MIN_NONZERO_FRACTIONAL_MAGNITUDE
                    + " in magnitude so DynamoDB can represent them");
        }
    }

    /**
     * Applies a portable increment to an existing numeric value.
     * <p>
     * The result bound is chosen by the <em>normalized</em> delta, not by the
     * Java type the caller passed:
     * <ul>
     *   <li>an <b>integral</b> normalized delta bounds the result to the signed
     *       64-bit interval, checked independently of the numeric
     *       representation of the existing value so every provider can enforce
     *       the same result bound atomically;</li>
     *   <li>a <b>fractional</b> normalized delta only requires the result to
     *       stay finite — a fractional result is never promised to round-trip
     *       as a {@code Long}, so no 64-bit bound applies to it.</li>
     * </ul>
     * Because {@link #normalize(Number)} folds any whole-valued {@code Float},
     * {@code Double}, or {@code BigDecimal} to a {@link Long}, {@code 1.0d} is
     * an integral delta. On a {@code current} of {@code 1e300} this makes
     * {@code add(1e300, 1)} and {@code add(1e300, 1.0d)} both fail while
     * {@code add(1e300, 1.5d)} succeeds. See the class Javadoc for why this
     * asymmetry is deliberate; use {@link #isIntegralDelta(Number)} to find out
     * which rule a delta will take before dispatch.
     *
     * @param current existing numeric field value; must not be {@code null}
     * @param delta caller-supplied increment delta
     * @return a {@link Long} for an integral result or a {@link Double} for a
     *         fractional result
     * @throws IllegalArgumentException if {@code current} is {@code null}, or
     *         if the delta or result is outside the portable numeric domain
     */
    public static Number add(Number current, Number delta) {
        if (current == null) {
            throw new IllegalArgumentException("current value must not be null");
        }

        Number normalizedDelta = normalize(delta);
        if (normalizedDelta instanceof Long integralDelta) {
            BigDecimal result = decimalValue(current).add(BigDecimal.valueOf(integralDelta));
            if (result.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                    || result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("integral INCREMENT result must fit signed 64-bit range");
            }
            if (result.stripTrailingZeros().scale() <= 0) {
                try {
                    return result.toBigIntegerExact().longValueExact();
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException(
                            "integral INCREMENT result must fit signed 64-bit range", e);
                }
            }

            double fractionalResult = result.doubleValue();
            if (Double.isNaN(fractionalResult) || Double.isInfinite(fractionalResult)) {
                throw new IllegalArgumentException("INCREMENT result must remain finite");
            }
            return fractionalResult;
        }

        double result = current.doubleValue() + normalizedDelta.doubleValue();
        if (Double.isInfinite(result) || Double.isNaN(result)) {
            throw new IllegalArgumentException("INCREMENT result must remain finite");
        }
        return result;
    }

    /**
     * Reports whether an integral-delta increment would produce a result
     * outside the signed 64-bit portable result range.
     * <p>
     * Mirrors the bound {@link #add(Number, Number)} enforces, including its
     * asymmetry: the check only applies when the <em>normalized</em> delta is
     * integral, and {@link #normalize(Number)} folds whole-valued floating
     * deltas such as {@code 1.0d} to {@link Long}. A fractional delta always
     * returns {@code false} because no 64-bit result bound applies to it — not
     * because the result is small.
     *
     * @param current existing numeric value; must not be {@code null}
     * @param delta caller-supplied increment delta
     * @return {@code true} only when the normalized delta is integral and the
     *         result is outside the portable range
     * @throws IllegalArgumentException if {@code current} is {@code null} or
     *         the delta is outside the portable numeric domain
     */
    public static boolean isIntegralResultOutsideRange(Number current, Number delta) {
        if (current == null) {
            throw new IllegalArgumentException("current value must not be null");
        }
        Number normalizedDelta = normalize(delta);
        if (!(normalizedDelta instanceof Long integralDelta)) {
            return false;
        }
        BigDecimal result = decimalValue(current).add(BigDecimal.valueOf(integralDelta));
        return result.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                || result.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0;
    }

    /**
     * Returns whether a normalized increment delta is integral.
     *
     * @param delta caller-supplied increment delta
     * @return {@code true} when the portable normalized representation is a
     *         signed 64-bit integer
     */
    public static boolean isIntegralDelta(Number delta) {
        return normalize(delta) instanceof Long;
    }

    /**
     * Largest current value that can accept {@code delta} without exceeding the
     * signed 64-bit portable result range.
     *
     * @param delta a normalized integral delta
     * @return inclusive upper bound for the current numeric value
     */
    public static BigInteger maximumBaseForIntegralDelta(long delta) {
        return MAX_INTEGRAL_RESULT.subtract(BigInteger.valueOf(delta));
    }

    /**
     * Smallest current value that can accept {@code delta} without falling
     * below the signed 64-bit portable result range.
     *
     * @param delta a normalized integral delta
     * @return inclusive lower bound for the current numeric value
     */
    public static BigInteger minimumBaseForIntegralDelta(long delta) {
        return MIN_INTEGRAL_RESULT.subtract(BigInteger.valueOf(delta));
    }

    private static BigDecimal decimalValue(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double floating = value.doubleValue();
            if (Double.isNaN(floating) || Double.isInfinite(floating)) {
                throw new IllegalArgumentException("current value must be finite");
            }
            return BigDecimal.valueOf(floating);
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("current value uses unsupported numeric type "
                    + value.getClass().getName(), e);
        }
    }
}
