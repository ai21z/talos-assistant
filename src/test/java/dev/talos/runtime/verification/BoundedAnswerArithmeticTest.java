package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.talos.runtime.verification.BoundedAnswerArithmetic.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedAnswerArithmeticTest {
    @Test
    void computesExactBoundDecimalsAndReducedRationals() {
        var expression = BoundedAnswerArithmetic.parse("mass divided by length squared");
        assertEquals(Set.of("mass", "length"), expression.variables());
        assertEquals("(mass / (length * length))", expression.canonical());
        var first = expression.evaluate(Map.of("mass", "72", "length", "1.8"), 1);
        assertEquals(Status.SUCCESS, first.status());
        assertEquals("200/9", first.exact());
        assertEquals("22.2", first.rounded());
        assertEquals("(72 / (1.8 * 1.8))", first.expression());
        assertEquals("HALF_UP", first.roundingMode());
        assertEquals(1, first.decimalPlaces());
        assertEquals("22.22", expression.evaluate(Map.of("mass", "72", "length", "1.8"), 2).rounded());
        assertResult("span multiplied by rise", Map.of("span", "8.125", "rise", "4"), 2, "65/2", "32.50");
        assertResult("distance / elapsed", Map.of("distance", "12.5", "elapsed", "0.5"), 0, "25/1", "25");
        assertResult("(a plus b) divided by (c minus d)",
                Map.of("a", "1.25", "b", "2.75", "c", "5", "d", "3"), 2, "2/1", "2.00");
    }

    @Test
    void operationOrderRemainsPartOfFormulaIdentity() {
        var ratioOfSquare = BoundedAnswerArithmetic.parse("a divided by b squared");
        var ratioThenProduct = BoundedAnswerArithmetic.parse("a divided by b multiplied by b");
        assertNotEquals(ratioOfSquare.canonical(), ratioThenProduct.canonical());
        assertEquals("(a / (b * b))", ratioOfSquare.canonical());
        assertEquals("((a / b) * b)", ratioThenProduct.canonical());
        assertEquals("2.0", ratioOfSquare.evaluate(Map.of("a", "8", "b", "2"), 1).rounded());
        assertEquals("8.0", ratioThenProduct.evaluate(Map.of("a", "8", "b", "2"), 1).rounded());
        assertNotEquals(BoundedAnswerArithmetic.parse("a + b").canonical(),
                BoundedAnswerArithmetic.parse("b + a").canonical());
        assertNotEquals(BoundedAnswerArithmetic.parse("a + (b + c)").canonical(),
                BoundedAnswerArithmetic.parse("(a + b) + c").canonical());
        assertResult("10 - 3 - 2 * 2", Map.of(), 0, "3/1", "3");
    }

    @Test
    void squareBindsBeforeUnarySignsWhileParenthesesOverrideIt() {
        var negativeSquare = BoundedAnswerArithmetic.parse("-a squared");
        var squareOfNegative = BoundedAnswerArithmetic.parse("(-a) squared");
        assertEquals("(-(a * a))", negativeSquare.canonical());
        assertEquals("((-a) * (-a))", squareOfNegative.canonical());
        assertEquals("-9", negativeSquare.evaluate(Map.of("a", "3"), 0).rounded());
        assertEquals("9", squareOfNegative.evaluate(Map.of("a", "3"), 0).rounded());
        assertResult("minus a squared", Map.of("a", "3"), 0, "-9/1", "-9");
        assertResult("--a squared", Map.of("a", "3"), 0, "9/1", "9");
        assertResult("a squared squared", Map.of("a", "3"), 0, "81/1", "81");
        assertResult("a * -b squared", Map.of("a", "2", "b", "3"), 0, "-18/1", "-18");
        assertResult("+(a MINUS b) MULTIPLIED BY c DIVIDED BY d PLUS 1",
                Map.of("a", "9", "b", "3", "c", "2", "d", "4"), 2, "4/1", "4.00");
    }

    @Test
    void equalDecimalsNormalizeWithoutAlgebraicSimplification() {
        assertEquals(BoundedAnswerArithmetic.parse("a * 1").canonical(),
                BoundedAnswerArithmetic.parse("a multiplied by 01.00e0").canonical());
        assertEquals("0.01", BoundedAnswerArithmetic.parse(".0100").canonical());
        assertEquals("1000", BoundedAnswerArithmetic.parse("1e3").canonical());
        assertEquals("1", BoundedAnswerArithmetic.parse("1.").canonical());
        assertEquals("0", BoundedAnswerArithmetic.parse("000.000").canonical());
        assertNotEquals(BoundedAnswerArithmetic.parse("a * 1").canonical(),
                BoundedAnswerArithmetic.parse("a").canonical());
        assertResult("x", Map.of("x", "+001.2500e+1"), 2, "25/2", "12.50");
    }

    @Test
    void variableRenamingIsSimultaneousImmutableAndCaseSensitive() {
        var original = BoundedAnswerArithmetic.parse("a - b");
        Map<String, String> names = new HashMap<>(Map.of("a", "b", "b", "a"));
        var swapped = original.renameVariables(names);
        names.put("a", "c");
        assertEquals("(a - b)", original.canonical());
        assertEquals("(b - a)", swapped.canonical());
        assertEquals("(x - b)", original.renameVariables(Map.of("a", "x")).canonical());
        assertEquals("(a - b)", original.renameVariables(Map.of("unrelated", "bad target")).canonical());
        assertThrows(UnsupportedOperationException.class, () -> original.variables().add("c"));
        assertResult("A + a + _$2", Map.of("A", "1", "a", "2", "_$2", "3"), 0, "6/1", "6");
    }

    @Test
    void invalidVariableRenamingsDoNotCreateExpressionSyntax() {
        var parsed = BoundedAnswerArithmetic.parse("a + b");
        for (String value : Arrays.asList(null, "", "a b", "1x", "x+1", "squared", "DIVIDED", "z".repeat(65))) {
            Map<String, String> names = new HashMap<>();
            names.put("a", value);
            assertEquals(Status.INVALID_BINDING, parsed.renameVariables(names).status(), String.valueOf(value));
        }
        assertEquals(Status.INVALID_BINDING, parsed.renameVariables(null).status());
    }

    @Test
    void requiresTheEntireClosedGrammar() {
        for (String input : Arrays.asList(null, "", " ", "a divided by b squared approximately",
                "a plus b and c", "a^2", "a**2", "2a", "a(b)", "sqrt(9)", "Math.pow(a,2)",
                "a % 2", "a = 1", "[1]", "{a:1}", "'1'", "`1`", "a; b", "a // b", "a /* b */",
                "(a", "a)", "()", "a divided", "a multiplied b", "a divided byproduct", "a ÷ b", "a × b")) {
            var parsed = BoundedAnswerArithmetic.parse(input);
            assertFalse(parsed.successful(), String.valueOf(input));
            assertEquals("", parsed.canonical());
            assertTrue(parsed.variables().isEmpty());
            assertEquals(parsed.status(), parsed.evaluate(Map.of(), 2).status());
        }
    }

    @Test
    void malformedDecimalsHaveNoNumericFallback() {
        var parsed = BoundedAnswerArithmetic.parse("a");
        for (String input : Arrays.asList(null, "", " ", " 2", "2 ", "+", "-", ".", "+.",
                "1/2", "NaN", "Infinity", "0x10", "1_000", "1e", "1e+", "1e-", "2.3.4", "1ee2")) {
            Map<String, String> operands = new HashMap<>();
            operands.put("a", input);
            var result = parsed.evaluate(operands, 2);
            assertEquals(Status.INVALID_DECIMAL, result.status(), String.valueOf(input));
            assertNull(result.numerator());
            assertNull(result.denominator());
            assertEquals("", result.rounded());
            assertEquals("", result.exact());
            assertEquals("", result.expression());
        }
        assertEquals(Status.INVALID_DECIMAL, BoundedAnswerArithmetic.parse("1.2.3").status());
    }

    @Test
    void missingOperandsAndPrecisionHaveSpecificStatuses() {
        var parsed = BoundedAnswerArithmetic.parse("a + b");
        assertEquals(Status.MISSING_VARIABLE, parsed.evaluate(Map.of("a", "1"), 2).status());
        assertEquals(Status.INVALID_BINDING, parsed.evaluate(null, 2).status());
        assertEquals(Status.INVALID_PRECISION, parsed.evaluate(Map.of(), -1).status());
        assertEquals(Status.INVALID_PRECISION, parsed.evaluate(Map.of(), 13).status());
        assertResult("1 / 3", Map.of(), 12, "1/3", "0.333333333333");
        assertResult("a", Map.of("a", "2", "unrelated", "invalid"), 0, "2/1", "2");
    }

    @Test
    void divisionByZeroIsExplicitIncludingComputedDenominators() {
        for (String input : List.of("1 / 0", "0 / 0", "a / (b - b)", "(1 / 0) * 0")) {
            var result = BoundedAnswerArithmetic.parse(input).evaluate(Map.of("a", "3", "b", "2"), 2);
            assertEquals(Status.DIVISION_BY_ZERO, result.status(), input);
            assertFalse(result.successful());
            assertEquals("", result.rounded());
        }
        assertResult("0 / -2", Map.of(), 2, "0/1", "0.00");
        assertResult("2 / -4", Map.of(), 2, "-1/2", "-0.50");
    }

    @Test
    void exactMidpointsAndNegativeResultsRoundMathematicallyHalfUp() {
        assertResult("2.55", Map.of(), 1, "51/20", "2.6");
        assertResult("-2.55", Map.of(), 1, "-51/20", "-2.6");
        assertResult("1.005", Map.of(), 2, "201/200", "1.01");
        assertResult("-1.005", Map.of(), 2, "-201/200", "-1.01");
        assertResult("2.5", Map.of(), 0, "5/2", "3");
        assertResult("-2.5", Map.of(), 0, "-5/2", "-3");
        assertResult("2.549", Map.of(), 1, "2549/1000", "2.5");
        assertResult("-0.04", Map.of(), 1, "-1/25", "0.0");
        assertResult("-0.05", Map.of(), 1, "-1/20", "-0.1");
        assertResult("9.995", Map.of(), 2, "1999/200", "10.00");
        assertResult("2 / 3", Map.of(), 0, "2/3", "1");
        assertResult("1 / 3", Map.of(), 0, "1/3", "0");
    }

    @Test
    void literalLengthSignificanceAndScaleAreBoundedBeforeLargePowers() {
        assertResult("0".repeat(63) + "1", Map.of(), 0, "1/1", "1");
        assertTrue(BoundedAnswerArithmetic.parse("9".repeat(32)).successful());
        assertTrue(BoundedAnswerArithmetic.parse("1e18").successful());
        assertTrue(BoundedAnswerArithmetic.parse("1e-18").successful());
        assertTrue(BoundedAnswerArithmetic.parse("1e000000000000000000000000000000000000000000000000000000000018").successful());
        for (String input : List.of("0".repeat(64) + "1", "9".repeat(33), "1e19", "1e-19",
                "0.0000000000000000001", "1e999999999999999999", "1e-999999999999999999")) {
            assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse(input).status(), input);
            assertEquals(Status.LIMIT_EXCEEDED,
                    BoundedAnswerArithmetic.parse("a").evaluate(Map.of("a", input), 2).status(), input);
        }
    }

    @Test
    void characterAndIdentifierAdmissionRejectLargeInputsWithoutParsingThem() {
        for (String input : List.of("1".repeat(1_000_000), " ".repeat(1_000_000),
                "(".repeat(1_000_000), "a".repeat(65))) {
            assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse(input).status());
        }
        assertTrue(BoundedAnswerArithmetic.parse("a".repeat(64)).successful());
        assertEquals(Status.LIMIT_EXCEEDED,
                BoundedAnswerArithmetic.parse("a").evaluate(Map.of("a", "1".repeat(1_000_000)), 0).status());
        assertTrue(BoundedAnswerArithmetic.parse(" ".repeat(4_095) + "1").successful());
        assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse(" ".repeat(4_096) + "1").status());
    }

    @Test
    void syntacticAndExpandedTreeDepthAreBounded() {
        assertTrue(BoundedAnswerArithmetic.parse("-".repeat(15) + "a").successful());
        assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse("-".repeat(16) + "a").status());
        assertTrue(BoundedAnswerArithmetic.parse("(".repeat(15) + "a" + ")".repeat(15)).successful());
        assertEquals(Status.LIMIT_EXCEEDED,
                BoundedAnswerArithmetic.parse("(".repeat(16) + "a" + ")".repeat(16)).status());
        assertTrue(BoundedAnswerArithmetic.parse("a" + " + a".repeat(15)).successful());
        assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse("a" + " + a".repeat(16)).status());
    }

    @Test
    void nodeBudgetIncludesRepeatedSquareExpansionAndTheEntireExpression() {
        String thirtyTwoLeaves = balanced("a", 32, "+");
        assertTrue(BoundedAnswerArithmetic.parse(thirtyTwoLeaves).successful());
        assertTrue(BoundedAnswerArithmetic.parse("+" + thirtyTwoLeaves).successful());
        assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse("++" + thirtyTwoLeaves).status());
        assertTrue(BoundedAnswerArithmetic.parse("a" + " squared".repeat(5)).successful());
        assertEquals(Status.LIMIT_EXCEEDED, BoundedAnswerArithmetic.parse("a" + " squared".repeat(6)).status());
        assertTrue(BoundedAnswerArithmetic.parse("-a" + " squared".repeat(5)).successful());
        assertEquals(Status.LIMIT_EXCEEDED,
                BoundedAnswerArithmetic.parse("a + a" + " squared".repeat(5)).status());
    }

    @Test
    void intermediateNumeratorsAndDenominatorsStayWithinTheBitBudget() {
        String largeLiteral = "9".repeat(32) + "e18";
        var admitted = BoundedAnswerArithmetic.parse(balanced(largeLiteral, 24, "*"));
        var value = admitted.evaluate(Map.of(), 0);
        assertTrue(value.successful());
        assertTrue(value.numerator().bitLength() <= 4_096);
        assertEquals(BigInteger.ONE, value.denominator());
        var excessive = BoundedAnswerArithmetic.parse(balanced(largeLiteral, 25, "*"));
        assertTrue(excessive.successful());
        assertEquals(Status.LIMIT_EXCEEDED, excessive.evaluate(Map.of(), 0).status());
        var denominator = BoundedAnswerArithmetic.parse("1 / " + balanced(largeLiteral, 25, "*"));
        assertTrue(denominator.successful());
        assertEquals(Status.LIMIT_EXCEEDED, denominator.evaluate(Map.of(), 0).status());
    }

    @Test
    void roundingCannotAllocateAnIntermediateBeyondTheBitBudget() {
        String largeLiteral = "9".repeat(32) + "e18";
        String scale = "1" + "0".repeat(31) + "e2";
        var parsed = BoundedAnswerArithmetic.parse("(" + balanced(largeLiteral, 24, "*") + " * " + scale + ")");
        assertTrue(parsed.successful());
        var whole = parsed.evaluate(Map.of(), 0);
        assertTrue(whole.successful());
        assertEquals(4_096, whole.numerator().bitLength());
        assertEquals(Status.LIMIT_EXCEEDED, parsed.evaluate(Map.of(), 1).status());
    }

    private static void assertResult(String expression, Map<String, String> values, int places,
                                     String exact, String rounded) {
        var parsed = BoundedAnswerArithmetic.parse(expression);
        assertEquals(Status.SUCCESS, parsed.status(), expression);
        var result = parsed.evaluate(values, places);
        assertEquals(Status.SUCCESS, result.status(), expression);
        assertEquals(exact, result.exact(), expression);
        assertEquals(rounded, result.rounded(), expression);
    }

    private static String balanced(String leaf, int leaves, String operator) {
        if (leaves == 1) return leaf;
        int left = leaves / 2;
        return "(" + balanced(leaf, left, operator) + " " + operator + " "
                + balanced(leaf, leaves - left, operator) + ")";
    }
}
