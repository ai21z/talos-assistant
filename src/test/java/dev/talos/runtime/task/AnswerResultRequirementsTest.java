package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import static dev.talos.runtime.task.AnswerResultRequirements.Role.*;
import static org.junit.jupiter.api.Assertions.*;

class AnswerResultRequirementsTest {
    private static final String PREFIX = "Read README.md, index.html, script.js and scripts.js. ";
    private static final String C1 = PREFIX + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String C3 = PREFIX + "Explain why the Calculate button fails and give the correct selector. "
            + "For span 12 m and rise 2.5 m, give the panel area according to README.md and the mathematical value of the expression in script.js separately. "
            + "Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";

    @Test
    void singleDefinitionResultIsSeparatedFromTheOtherRequiredOutputs() {
        var ledger = AnswerResultRequirements.fromRequest(C1);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(DIAGNOSIS, SELECTOR, SOURCE_DEFINITION, SCRIPT_DISTINCTION), roles(ledger));
        var calculation = ledger.slots().get(2);
        assertEquals("the BMI for 72 kg and 1.8 m", C1.substring(calculation.start(), calculation.end()));
        assertEquals("72 kg and 1.8 m", C1.substring(calculation.operandStart(), calculation.operandEnd()));
        assertEquals("BMI", calculation.quantity());
        assertEquals("", calculation.sourcePath());
        assertEquals(-1, calculation.expressionStart());
        assertEquals(-1, calculation.expressionEnd());
        assertTrue(calculation.calculation());
    }

    @Test
    void documentedImplementationAndComparisonResultsAreThreeDistinctObligations() {
        var ledger = AnswerResultRequirements.fromRequest(C3);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(DIAGNOSIS, SELECTOR, SOURCE_DEFINITION, IMPLEMENTATION, COMPARISON, SCRIPT_DISTINCTION), roles(ledger));
        var definition = ledger.slots().get(2);
        var implementation = ledger.slots().get(3);
        var comparison = ledger.slots().get(4);
        assertEquals("give the panel area according to README.md", C3.substring(definition.start(), definition.end()));
        assertEquals("the mathematical value of the expression in script.js separately", C3.substring(implementation.start(), implementation.end()));
        assertEquals("README.md", definition.sourcePath());
        assertEquals("script.js", implementation.sourcePath());
        assertEquals("span 12 m and rise 2.5 m", C3.substring(definition.operandStart(), definition.operandEnd()));
        assertEquals(definition.operandStart(), implementation.operandStart());
        assertEquals(definition.operandEnd(), implementation.operandEnd());
        assertEquals(List.of(definition.id(), implementation.id()), comparison.peerSlotIds());
    }

    @Test
    void quantitiesFilesAndValuesAreNotFixtureVocabulary() {
        String request = "For distance 39 km and elapsed 3 h, determine speed as documented in manual.txt "
                + "and the result of the formula in src/travel.js. Compare these values.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(SOURCE_DEFINITION, IMPLEMENTATION, COMPARISON), roles(ledger));
        assertEquals("speed", ledger.slots().getFirst().quantity());
        assertEquals("manual.txt", ledger.slots().getFirst().sourcePath());
        assertEquals("src/travel.js", ledger.slots().get(1).sourcePath());
    }

    @Test
    void sameSharedInputsCanSupportCoordinatedDistinctQuantities() {
        String request = "For width 7 cm and height 4 cm, calculate the area and the perimeter.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(SOURCE_DEFINITION, SOURCE_DEFINITION), roles(ledger));
        assertEquals(List.of("area", "perimeter"), ledger.slots().stream().map(AnswerResultRequirements.Slot::quantity).toList());
        assertEquals(ledger.slots().getFirst().operandStart(), ledger.slots().getLast().operandStart());
    }

    @Test
    void nestedOrUnsplitQuantityCoordinationCannotCountAsOneCompleteResult() {
        for (String request : List.of("Give area and perimeter for width 7 cm and height 4 cm.",
                "For width 7 cm and height 4 cm, give the sum of lengths and widths.",
                "Give area or volume according to guide.txt for width 7 cm and height 4 cm.")) {
            var ledger = AnswerResultRequirements.fromRequest(request);
            assertFalse(ledger.complete(), request);
            assertTrue(ledger.slots().stream().anyMatch(slot -> slot.role() == UNKNOWN), request);
        }
    }

    @Test
    void separateInputScopesAreNeverSilentlyCombinedForComparison() {
        String request = "Give area according to guide.txt for width 7 cm and height 4 cm "
                + "and the value of the expression in calc.js for width 9 cm and height 5 cm. Explain the difference.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertFalse(ledger.complete());
        assertEquals(List.of(SOURCE_DEFINITION, IMPLEMENTATION, UNKNOWN), roles(ledger));
        var first = ledger.slots().getFirst();
        var second = ledger.slots().get(1);
        assertNotEquals(first.operandStart(), second.operandStart());
        assertEquals("width 7 cm and height 4 cm", request.substring(first.operandStart(), first.operandEnd()));
        assertEquals("width 9 cm and height 5 cm", request.substring(second.operandStart(), second.operandEnd()));
        assertTrue(ledger.unresolved().contains("AMBIGUOUS_COMPARISON_SCOPE"));
    }

    @Test
    void repeatedIdenticalClausesRetainDistinctOccurrenceIds() {
        String clause = "Calculate area for width 7 cm and height 4 cm";
        String request = clause + ". " + clause + ".";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(2, ledger.slots().size());
        var first = ledger.slots().getFirst();
        var second = ledger.slots().getLast();
        assertEquals(clause, request.substring(first.start(), first.end()));
        assertEquals(clause, request.substring(second.start(), second.end()));
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.start(), second.start());
    }

    @Test
    void originalRequestOrderIsRetainedWithoutSortingByRole() {
        String request = "For width 7 cm and height 4 cm, give the value of the expression in calc.js "
                + "and the area according to guide.txt. Explain why these values differ.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(IMPLEMENTATION, SOURCE_DEFINITION, COMPARISON), roles(ledger));
        assertEquals(List.of(ledger.slots().get(1).id(), ledger.slots().getFirst().id()), ledger.slots().getLast().peerSlotIds());
    }

    @Test
    void unexplainedResultRequestsAndActionableSuffixesRemainRequired() {
        for (String suffix : List.of("Assess whether the page is accessible", "Give the volume without inputs",
                "Explain its performance impact", "Also check the archived behavior")) {
            String request = C1 + " " + suffix + ".";
            var ledger = AnswerResultRequirements.fromRequest(request);
            assertFalse(ledger.complete(), suffix);
            var last = ledger.slots().getLast();
            assertEquals(UNKNOWN, last.role(), suffix);
            assertEquals(suffix, request.substring(last.start(), last.end()));
        }
    }

    @Test
    void unsupportedLegacyDistinctionCannotBecomeAResolvedLedgerSlot() {
        String request = PREFIX + "Compare whichever scripts appear relevant.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertFalse(ledger.complete());
        assertEquals(List.of(UNKNOWN), roles(ledger));
        assertTrue(ledger.unresolved().contains("UNSUPPORTED_SCRIPT_DISTINCTION"));
    }

    @Test
    void quotedConditionalAndNegatedCalculationsDoNotCreatePositiveSlots() {
        for (String request : List.of("\"Calculate area for width 7 cm and height 4 cm.\"",
                "If asked, calculate area for width 7 cm and height 4 cm.",
                "Do not calculate area for width 7 cm and height 4 cm and give the area.",
                "> Calculate area for width 7 cm and height 4 cm.")) {
            var ledger = AnswerResultRequirements.fromRequest(request);
            assertFalse(ledger.complete(), request);
            assertEquals(List.of(UNKNOWN), roles(ledger), request);
        }
    }

    @Test
    void requestExpressionAndLocalPrecisionRemainInsideOriginalScopes() {
        String request = "Evaluate `a + b` for a = 2 and b = 3 to 2 decimal places.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        var slot = ledger.slots().getFirst();
        assertEquals(REQUEST_EXPRESSION, slot.role());
        assertEquals("a + b", slot.quantity());
        assertEquals("a + b", request.substring(slot.expressionStart(), slot.expressionEnd()));
        assertEquals('`', request.charAt(slot.expressionStart() - 1));
        assertEquals('`', request.charAt(slot.expressionEnd()));
        assertEquals("a = 2 and b = 3 to 2 decimal places", request.substring(slot.operandStart(), slot.operandEnd()));
    }

    @Test
    void repeatedSingleVariableExpressionsHaveDirectOccurrenceSpans() {
        String request = "Evaluate a for a = 2. Evaluate a for a = 2.";
        var ledger = AnswerResultRequirements.fromRequest(request);
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertEquals(List.of(REQUEST_EXPRESSION, REQUEST_EXPRESSION), roles(ledger));
        for (var slot : ledger.slots()) {
            assertEquals("a", request.substring(slot.expressionStart(), slot.expressionEnd()));
            assertEquals(slot.start() + "Evaluate ".length(), slot.expressionStart());
            assertTrue(slot.expressionEnd() < slot.operandStart());
            assertEquals("a = 2", request.substring(slot.operandStart(), slot.operandEnd()));
        }
        assertNotEquals(ledger.slots().getFirst().expressionStart(), ledger.slots().getLast().expressionStart());
        assertNotEquals(ledger.slots().getFirst().id(), ledger.slots().getLast().id());
    }

    @Test
    void missingConflictingAndUnboundedInputsStayUnresolved() {
        for (String request : List.of("Give area according to guide.txt.",
                "For width 7 cm and width 4 cm, give area according to guide.txt.",
                "Calculate area for width 1e19 cm and height 4 cm.",
                "Calculate area for width 7 cm and height 4 cm to 13 decimal places.",
                "Calculate area for width 7 cm and height 4 cm with hidden defaults.")) {
            var ledger = AnswerResultRequirements.fromRequest(request);
            assertFalse(ledger.complete(), request);
            assertTrue(ledger.slots().stream().anyMatch(slot -> slot.role() == UNKNOWN), request);
        }
    }

    @Test
    void comparisonCannotBeBoundToOneDuplicatedOrAmbiguousResultRole() {
        for (String request : List.of("Calculate area for width 7 cm and height 4 cm. Explain any disagreement.",
                "For width 7 cm and height 4 cm, give area according to guide.txt and perimeter according to guide.txt. Explain the difference.",
                "For width 7 cm and height 4 cm, give area according to guide.txt and the value of the expression in calc.js "
                        + "and the value of the expression in other.js. Compare these results.")) {
            var ledger = AnswerResultRequirements.fromRequest(request);
            assertFalse(ledger.complete(), request);
            assertEquals(UNKNOWN, ledger.slots().getLast().role());
            assertTrue(ledger.unresolved().contains("AMBIGUOUS_COMPARISON_SCOPE"));
        }
    }

    @Test
    void readInstructionsAndOrdinaryRestrictionsDoNotCreateOutputSlots() {
        var ledger = AnswerResultRequirements.fromRequest(PREFIX + "Do not edit files or claim tests were run.");
        assertTrue(ledger.complete(), ledger.unresolved().toString());
        assertTrue(ledger.slots().isEmpty());
        assertTrue(AnswerResultRequirements.fromRequest(null).slots().isEmpty());
    }

    @Test
    void boundsPreserveAnExplicitUnknownRegion() {
        var requestBound = AnswerResultRequirements.fromRequest("x".repeat(AnswerResultRequirements.MAX_REQUEST_CHARS + 1));
        assertFalse(requestBound.complete());
        assertEquals(1, requestBound.slots().size());
        assertEquals(AnswerResultRequirements.MAX_REQUEST_CHARS + 1, requestBound.slots().getFirst().end());
        String request = "Calculate area for width 7 cm and height 4 cm. ".repeat(AnswerResultRequirements.MAX_SLOTS + 1);
        var countBound = AnswerResultRequirements.fromRequest(request);
        assertFalse(countBound.complete());
        assertEquals(AnswerResultRequirements.MAX_SLOTS, countBound.slots().size());
        assertEquals(UNKNOWN, countBound.slots().getLast().role());
        assertEquals(request.length(), countBound.slots().getLast().end());
    }

    @Test
    void requestBindingAndLedgerCollectionsAreStableAndImmutable() throws Exception {
        var ledger = AnswerResultRequirements.fromRequest(C3);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(C3.getBytes(StandardCharsets.UTF_8))), ledger.requestSha256());
        assertEquals(ledger.slots(), AnswerResultRequirements.fromRequest(C3).slots());
        assertNotEquals(ledger.requestSha256(), AnswerResultRequirements.fromRequest(C3 + " ").requestSha256());
        assertEquals(ledger.slots().size(), new HashSet<>(ledger.slots().stream().map(AnswerResultRequirements.Slot::id).toList()).size());
        assertThrows(UnsupportedOperationException.class, () -> ledger.slots().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.unresolved().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> ledger.slots().get(4).peerSlotIds().clear());
    }

    private static List<AnswerResultRequirements.Role> roles(AnswerResultRequirements ledger) {
        return ledger.slots().stream().map(AnswerResultRequirements.Slot::role).toList();
    }
}
