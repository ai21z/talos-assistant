package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.talos.runtime.task.AnswerRequirements.Kind.CALCULATION;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerRequirementsScopeTest {
    private static final String PREFIX = "Read notes.md, page.html, calculator.js and archived.js. "
            + "Explain why the Measure button fails. ";

    @Test
    void separateCalculationsRetainTheirOwnValuesPrecisionAndOriginalCoordinates() {
        String first = "Calculate area for width 12 m and height 2.5 m to 1 decimal place";
        String second = "Calculate area for width 8 m and height 3.5 m to 3 decimal places";
        String request = PREFIX + first + ". " + second + ".";
        List<AnswerRequirements.Requirement> requirements = calculations(request);

        assertEquals(2, requirements.size());
        AnswerRequirements firstScope = scope(request, requirements.getFirst());
        AnswerRequirements secondScope = scope(request, requirements.get(1));

        assertEquals(List.of("12", "2.5"), values(firstScope));
        assertEquals(List.of("8", "3.5"), values(secondScope));
        assertEquals(1, firstScope.precision());
        assertEquals(3, secondScope.precision());
        assertEquals(List.of("width 12 m", "height 2.5 m"), operandQuotes(request, firstScope));
        assertEquals(List.of("width 8 m", "height 3.5 m"), operandQuotes(request, secondScope));
        assertTrue(firstScope.unresolved().isEmpty(), firstScope.unresolved().toString());
        assertTrue(secondScope.unresolved().isEmpty(), secondScope.unresolved().toString());
        assertScope(request, firstScope, first);
        assertScope(request, secondScope, second);
    }

    @Test
    void repeatedVariableNamesAndLiteralValuesRemainDistinctByScope() {
        String first = "Evaluate a + b for a = 4 and b = 2 to 1 decimal place";
        String second = "Evaluate a / b for a = 4 and b = 2 to 3 decimal places";
        String request = PREFIX + first + ". " + second + ".";
        List<AnswerRequirements.Requirement> requirements = calculations(request);
        AnswerRequirements firstScope = scope(request, requirements.getFirst());
        AnswerRequirements secondScope = scope(request, requirements.get(1));

        assertEquals("a + b", firstScope.expression());
        assertEquals("a / b", secondScope.expression());
        assertEquals(List.of("4", "2"), values(firstScope));
        assertEquals(values(firstScope), values(secondScope));
        assertEquals(List.of("a = 4", "b = 2"), operandQuotes(request, firstScope));
        assertEquals(operandQuotes(request, firstScope), operandQuotes(request, secondScope));
        assertTrue(firstScope.operands().getLast().end() < secondScope.operands().getFirst().start());
        assertTrue(firstScope.unresolved().isEmpty(), firstScope.unresolved().toString());
        assertTrue(secondScope.unresolved().isEmpty(), secondScope.unresolved().toString());
    }

    @Test
    void implicitCalculationAfterSelectorDoesNotNeedAnInsertedVerb() {
        String clause = "the BMI for 72 kg and 1.8 m to 2 decimal places";
        String request = PREFIX + "Give the correct selector and " + clause + ".";
        AnswerRequirements.Requirement requirement = calculations(request).getFirst();
        AnswerRequirements result = scope(request, requirement);

        assertEquals("BMI", result.quantity());
        assertEquals(List.of("72", "1.8"), values(result));
        assertEquals(2, result.precision());
        assertEquals(List.of("72 kg", "1.8 m"), operandQuotes(request, result));
        assertScope(request, result, clause);
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
    }

    @Test
    void trimmingAScopePreservesSignedLiteralsAndRequestCoordinates() {
        String clause = "Evaluate a + b for a = -2.50 and b = +3e1";
        String request = PREFIX + "  " + clause + "  .";
        int start = PREFIX.length();
        AnswerRequirements result = AnswerRequirements.fromCalculationClause(request, start, request.length() - 1);

        assertEquals(List.of("-2.50", "+3e1"), values(result));
        assertEquals(List.of("a = -2.50", "b = +3e1"), operandQuotes(request, result));
        assertScope(request, result, clause);
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
    }

    @Test
    void localOperandAndPrecisionFailuresDoNotContaminateASiblingScope() {
        String first = "Evaluate a + b for a = 1 and a = 2 to 13 decimal places";
        String second = "Evaluate a + b for a = 3 and b = 4 to 2 decimal places";
        String request = PREFIX + first + ". " + second + ".";
        List<AnswerRequirements.Requirement> requirements = calculations(request);
        AnswerRequirements firstScope = scope(request, requirements.getFirst());
        AnswerRequirements secondScope = scope(request, requirements.get(1));

        assertTrue(firstScope.unresolved().contains("DUPLICATE_OPERAND_NAME"));
        assertTrue(firstScope.unresolved().contains("PRECISION_BOUND_EXCEEDED"));
        assertEquals(List.of("3", "4"), values(secondScope));
        assertEquals(2, secondScope.precision());
        assertTrue(secondScope.unresolved().isEmpty(), secondScope.unresolved().toString());
    }

    @Test
    void invalidScopeRangesAndOversizedRequestsAreRejected() {
        String request = "Evaluate a + b for a = 1 and b = 2";
        String oversized = request + " ".repeat(AnswerRequirements.MAX_REQUEST_CHARS);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AnswerRequirements.fromCalculationClause(null, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AnswerRequirements.fromCalculationClause(request, -1, request.length())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AnswerRequirements.fromCalculationClause(request, 2, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AnswerRequirements.fromCalculationClause(request, 0, request.length() + 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AnswerRequirements.fromCalculationClause(oversized, 0, request.length())));
    }

    @Test
    void anEmptyScopeDoesNotInventACalculationOrBorrowAdjacentOperands() {
        String request = PREFIX + "Evaluate a + b for a = 1 and b = 2.";
        AnswerRequirements result = AnswerRequirements.fromCalculationClause(request, PREFIX.length(), PREFIX.length());

        assertTrue(result.requirements().isEmpty());
        assertTrue(result.operands().isEmpty());
        assertTrue(result.expression().isEmpty());
        assertNull(result.precision());
    }

    @Test
    void standalonePrecisionIgnoresDifferentCalculationLocalPrecisions() {
        String first = "Calculate area for width 12 m and height 2.5 m to 1 decimal place";
        String second = "Calculate area for width 8 m and height 3.5 m to 3 decimal places";
        String request = PREFIX + first + ". " + second + ".";
        AnswerRequirements withoutDefault = AnswerRequirements.standalonePrecision(request);
        AnswerRequirements withDefault = AnswerRequirements.standalonePrecision(request + " Use 2 decimal places.");

        assertNull(withoutDefault.precision());
        assertTrue(withoutDefault.unresolved().isEmpty());
        assertEquals(2, withDefault.precision());
        assertTrue(withDefault.unresolved().isEmpty(), withDefault.unresolved().toString());
        assertTrue(withDefault.requirements().isEmpty());
        assertTrue(withDefault.operands().isEmpty());
        assertEquals(1, scope(request, calculations(request).getFirst()).precision());
        assertEquals(3, scope(request, calculations(request).get(1)).precision());
    }

    @Test
    void standalonePrecisionRetainsJoinedUseClausesAcceptedByTheExistingGrammar() {
        for (String join : List.of(" and ", ", ", ", and ")) {
            String request = PREFIX + "Calculate area for width 12 m and height 2.5 m"
                    + join + "use 3 decimal places.";
            AnswerRequirements aggregate = AnswerRequirements.fromRequest(request);
            AnswerRequirements defaults = AnswerRequirements.standalonePrecision(request);

            assertEquals(3, aggregate.precision(), request);
            assertEquals(3, defaults.precision(), request);
            assertTrue(defaults.unresolved().isEmpty(), request);
            assertNull(scope(request, calculations(request).getFirst()).precision(), request);
        }
    }

    @Test
    void repeatedEqualDefaultsRemainValidButConflictingDefaultsStayUnresolved() {
        AnswerRequirements repeated = AnswerRequirements.standalonePrecision(
                "Use 2 decimal places. Use 2 decimal places.");
        assertEquals(2, repeated.precision());
        assertTrue(repeated.unresolved().isEmpty());

        for (String request : List.of("Use 2 decimal places. Use 3 decimal places.",
                "Use 2 decimal places and use 3 decimal places.")) {
            AnswerRequirements result = AnswerRequirements.standalonePrecision(request);
            assertTrue(result.unresolved().contains("CONFLICTING_REQUEST_PRECISION"), request);
        }
    }

    @Test
    void standaloneDefaultsRetainPrecisionBoundsAndBulletSyntax() {
        AnswerRequirements zero = AnswerRequirements.standalonePrecision("- Use 0 decimal places\n");
        AnswerRequirements twelve = AnswerRequirements.standalonePrecision("1. Use 12 decimal places\n");

        assertEquals(0, zero.precision());
        assertEquals(12, twelve.precision());
        assertTrue(zero.unresolved().isEmpty());
        assertTrue(twelve.unresolved().isEmpty());
        for (String value : List.of("13", "999999999999999999999")) {
            AnswerRequirements invalid = AnswerRequirements.standalonePrecision("Use " + value + " decimal places.");
            assertNull(invalid.precision());
            assertTrue(invalid.unresolved().contains("PRECISION_BOUND_EXCEEDED"), value);
        }
    }

    @Test
    void quotedConditionalAndNegatedClausesCannotCreateStandaloneDefaults() {
        for (String request : List.of("\"Use 3 decimal places.\"", "`Use 3 decimal places`",
                "> Use 3 decimal places", "If needed, use 3 decimal places.",
                "Suppose we calculate area and use 3 decimal places.",
                "Do not calculate area and use 3 decimal places.",
                "Don't calculate area and use 3 decimal places.")) {
            AnswerRequirements result = AnswerRequirements.standalonePrecision(request);
            assertNull(result.precision(), request);
            assertFalse(result.unresolved().contains("CONFLICTING_REQUEST_PRECISION"), request);
        }
    }

    private static List<AnswerRequirements.Requirement> calculations(String request) {
        return AnswerRequirements.fromRequest(request).requirements().stream()
                .filter(requirement -> requirement.kind() == CALCULATION).toList();
    }

    private static AnswerRequirements scope(String request, AnswerRequirements.Requirement requirement) {
        return AnswerRequirements.fromCalculationClause(request, requirement.start(), requirement.end());
    }

    private static List<String> values(AnswerRequirements requirements) {
        return requirements.operands().stream().map(AnswerRequirements.Operand::value).toList();
    }

    private static List<String> operandQuotes(String request, AnswerRequirements requirements) {
        return requirements.operands().stream().map(operand -> request.substring(operand.start(), operand.end())).toList();
    }

    private static void assertScope(String request, AnswerRequirements requirements, String clause) {
        assertEquals(1, requirements.requirements().size());
        AnswerRequirements.Requirement requirement = requirements.requirements().getFirst();
        assertEquals(CALCULATION, requirement.kind());
        assertEquals(clause, request.substring(requirement.start(), requirement.end()));
        for (AnswerRequirements.Operand operand : requirements.operands()) {
            assertTrue(operand.start() >= requirement.start());
            assertTrue(operand.end() <= requirement.end());
        }
    }
}
