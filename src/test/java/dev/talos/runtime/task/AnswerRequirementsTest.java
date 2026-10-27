package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.talos.runtime.task.AnswerRequirements.Kind.CALCULATION;
import static dev.talos.runtime.task.AnswerRequirements.Kind.DIAGNOSIS;
import static dev.talos.runtime.task.AnswerRequirements.Kind.SCRIPT_DISTINCTION;
import static dev.talos.runtime.task.AnswerRequirements.Kind.SELECTOR;
import static dev.talos.runtime.task.AnswerRequirements.Kind.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerRequirementsTest {
    private static final String HISTORICAL = "Read README.md, index.html, script.js and scripts.js. "
            + "Explain why the Calculate button fails, give the correct selector and the BMI for 72 kg and 1.8 m, "
            + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
    private static final String PREFIX = "Read notes.md, page.html, calculator.js and archived.js. "
            + "Explain why the Measure button fails. ";
    private static final String NAMED = "Calculate area for width 12 m and height 2.5 m.";

    @Test
    void capturesActualJoinedRequestAndExactOriginalSpans() {
        AnswerRequirements result = AnswerRequirements.fromRequest(HISTORICAL);

        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals(List.of(DIAGNOSIS, SELECTOR, CALCULATION, SCRIPT_DISTINCTION),
                result.requirements().stream().map(AnswerRequirements.Requirement::kind).toList());
        assertEquals(List.of("Explain why the Calculate button fails", "give the correct selector",
                        "the BMI for 72 kg and 1.8 m", "distinguish the two JavaScript files"),
                result.requirements().stream().map(item -> HISTORICAL.substring(item.start(), item.end())).toList());
        assertEquals("Calculate", result.target());
        assertFalse(result.targetIsId());
        assertEquals("BMI", result.quantity());
        assertEquals("", result.expression());
        assertEquals(List.of("72", "1.8"), result.operands().stream().map(AnswerRequirements.Operand::value).toList());
        assertEquals(List.of("", ""), result.operands().stream().map(AnswerRequirements.Operand::name).toList());
        assertEquals(List.of("kg", "m"), result.operands().stream().map(AnswerRequirements.Operand::unit).toList());
        assertEquals(List.of("72 kg", "1.8 m"), result.operands().stream()
                .map(item -> HISTORICAL.substring(item.start(), item.end())).toList());
        assertEquals(List.of("script.js", "scripts.js"), result.scriptTargets());
        assertNull(result.precision());
    }

    @Test
    void renamedQuantityAndNamedOperandsUseTheSameGrammar() {
        String request = "Read notes.md, page.html, calculator.js and archived.js. "
                + "Explain why the Measure button fails, give the correct selector and the area for width 12 m and height 2.5 m, "
                + "and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);

        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals("Measure", result.target());
        assertEquals("area", result.quantity());
        assertEquals(List.of("width", "height"), result.operands().stream().map(AnswerRequirements.Operand::name).toList());
        assertEquals(List.of("width 12 m", "height 2.5 m"), result.operands().stream()
                .map(item -> request.substring(item.start(), item.end())).toList());
    }

    @Test
    void independentlyOrderedSentencesAndBulletsRetainRequirements() {
        String request = "1. Compare the two JavaScript files.\n"
                + "2. Calculate area for width 12 m and height 2.5 m to 3 decimal places.\n"
                + "- Identify the correct selector\n"
                + "* Explain why the #measure button does not work\n"
                + "Inspect notes.md, page.html, calculator.js and archived.js.\n"
                + "Do not edit files or claim tests were run.";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);

        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals("measure", result.target());
        assertTrue(result.targetIsId());
        assertEquals(3, result.precision());
        assertEquals(4, result.requirements().size());
    }

    @Test
    void explicitRequestExpressionBindsNamedValuesWithoutHtmlUnits() {
        String request = PREFIX + "Evaluate (distance + reserve) / elapsed for distance = 38.5, reserve = -2 and elapsed = 4 "
                + "to 2 decimal places.";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);

        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals("(distance + reserve) / elapsed", result.expression());
        assertEquals("", result.quantity());
        assertEquals(List.of("distance", "reserve", "elapsed"), result.operands().stream().map(AnswerRequirements.Operand::name).toList());
        assertEquals(List.of("38.5", "-2", "4"), result.operands().stream().map(AnswerRequirements.Operand::value).toList());
        assertEquals(List.of("", "", ""), result.operands().stream().map(AnswerRequirements.Operand::unit).toList());
        assertEquals(2, result.precision());
    }

    @Test
    void explicitExpressionMayBeCodeDelimited() {
        AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX
                + "Calculate the expression `a divided by b squared` with a = 83 and b = 1.7.");

        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals("a divided by b squared", result.expression());
    }

    @Test
    void extraSubstantiveClausesRemainVisibleAndUnresolved() {
        String extra = "Also assess whether the page is accessible";
        String request = HISTORICAL + " " + extra + ".";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);

        assertTrue(result.applicable());
        assertTrue(result.unresolved().contains("UNRECOGNIZED_REQUEST_CLAUSE"));
        AnswerRequirements.Requirement unknown = result.requirements().stream().filter(item -> item.kind() == UNKNOWN).findFirst().orElseThrow();
        assertEquals(extra, request.substring(unknown.start(), unknown.end()));
    }

    @Test
    void joinedUnrecognizedSuffixCannotDisappear() {
        AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX + NAMED
                + " Give the correct selector and explain its performance impact.");

        assertTrue(result.applicable());
        assertTrue(result.has(SELECTOR));
        assertTrue(result.has(UNKNOWN));
        assertFalse(result.unresolved().isEmpty());
    }

    @Test
    void quotedHypotheticalAndNegatedCommandsMintNoAffirmativeObligations() {
        for (String text : List.of(
                "\"Calculate area for width 12 m and height 2.5 m. Distinguish the two JavaScript files.\"",
                "'Calculate area for width 12 m and height 2.5 m.'",
                "If asked, calculate area for width 12 m and height 2.5 m",
                "Suppose we calculate area for width 12 m and height 2.5 m",
                "Do not calculate area for width 12 m and height 2.5 m and distinguish the two JavaScript files",
                "Don't calculate area for width 12 m and height 2.5 m",
                "> Calculate area for width 12 m and height 2.5 m")) {
            AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX + text);
            assertTrue(result.applicable(), text);
            assertFalse(result.has(CALCULATION), text);
            assertFalse(result.has(SCRIPT_DISTINCTION), text);
            assertTrue(result.has(UNKNOWN), text);
        }
    }

    @Test
    void quotedOrConditionalDiagnosisDoesNotSelectTheRoute() {
        for (String diagnosis : List.of("\"Explain why the Measure button fails.\"",
                "If a customer asks, explain why the Measure button fails.",
                "Do not explain why the Measure button fails.")) {
            AnswerRequirements result = AnswerRequirements.fromRequest(
                    "Read notes.md, page.html, calculator.js and archived.js. " + diagnosis + " " + NAMED);
            assertFalse(result.applicable(), diagnosis);
            assertFalse(result.has(DIAGNOSIS), diagnosis);
        }
    }

    @Test
    void unsupportedCalculationAndDistinctionRemainRequired() {
        for (String suffix : List.of("Calculate whatever quantity makes sense.",
                "Give the volume without any operands.", "Compare whichever scripts appear relevant.")) {
            AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX + suffix);
            assertTrue(result.applicable(), suffix);
            assertFalse(result.unresolved().isEmpty(), suffix);
            assertTrue(result.has(CALCULATION) || result.has(SCRIPT_DISTINCTION), suffix);
        }
    }

    @Test
    void conflictingTargetsAndMultipleCalculationsStayLimited() {
        AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX + NAMED
                + " Explain why the Reset button fails. Calculate perimeter for width 12 m and height 2.5 m.");

        assertTrue(result.unresolved().contains("AMBIGUOUS_BUTTON_TARGET"));
        assertTrue(result.unresolved().contains("MULTIPLE_CALCULATION_REQUIREMENTS"));
        assertEquals("Measure", result.target());
        assertEquals("area", result.quantity());
    }

    @Test
    void exactScriptPathsAreRetainedAndMustBelongToReadTargets() {
        AnswerRequirements result = AnswerRequirements.fromRequest("Read notes.md, page.html, current/app.js and archive/app.js. "
                + "Explain why the Measure button fails. Compare current/app.js and archive/app.js.");
        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals(List.of("current/app.js", "archive/app.js"), result.scriptTargets());

        AnswerRequirements wrong = AnswerRequirements.fromRequest(PREFIX + "Compare other/calculator.js and archived.js.");
        assertTrue(wrong.unresolved().contains("SCRIPT_TARGET_NOT_EXPLICITLY_READ"));
    }

    @Test
    void scriptNamesThatResembleCommandsAreStillPathData() {
        AnswerRequirements result = AnswerRequirements.fromRequest("Read notes.md, page.html, read.js and calculate.js. "
                + "Explain why the Measure button fails. Compare read.js and calculate.js.");
        assertTrue(result.applicable());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertEquals(List.of("read.js", "calculate.js"), result.scriptTargets());
    }

    @Test
    void genericScriptDistinctionRequiresExactlyTwoReadScripts() {
        AnswerRequirements result = AnswerRequirements.fromRequest("Read notes.md, page.html, one.js, two.js and three.js. "
                + "Explain why the Measure button fails. Distinguish the two JavaScript files.");
        assertTrue(result.applicable());
        assertTrue(result.unresolved().contains("AMBIGUOUS_SCRIPT_TARGETS"));
    }

    @Test
    void unsupportedTrailingOperandTextIsRetainedByTheCalculationSpan() {
        String calculation = "Calculate area for width 12 m and height 2.5 m using whatever hidden defaults are necessary";
        String request = PREFIX + calculation + ".";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);
        assertTrue(result.applicable());
        assertTrue(result.unresolved().contains("UNSUPPORTED_OPERAND_BINDING"));
        AnswerRequirements.Requirement item = result.requirements().stream().filter(value -> value.kind() == CALCULATION).findFirst().orElseThrow();
        assertEquals(calculation, request.substring(item.start(), item.end()));
    }

    @Test
    void duplicateOrUnnamedExplicitBindingsRemainUnresolved() {
        AnswerRequirements duplicate = AnswerRequirements.fromRequest(PREFIX + "Evaluate a + b for a = 1 and a = 2.");
        assertTrue(duplicate.unresolved().contains("DUPLICATE_OPERAND_NAME"));
        AnswerRequirements unnamed = AnswerRequirements.fromRequest(PREFIX + "Evaluate a + b for 1 m and 2 m.");
        assertTrue(unnamed.unresolved().contains("OPERAND_NAME_OR_UNIT_REQUIRED"));
    }

    @Test
    void literalBoundsAreAppliedWithoutCalculating() {
        for (String literal : List.of("1e19", "1e-19", "1e999999999999999999999999999999", "9".repeat(33), "0".repeat(64) + "1")) {
            AnswerRequirements result = AnswerRequirements.fromRequest(PREFIX + "Calculate area for width " + literal + " m and height 2 m.");
            assertTrue(result.applicable(), literal);
            assertTrue(result.unresolved().contains("DECIMAL_LITERAL_BOUND_EXCEEDED"), literal);
        }
        AnswerRequirements boundary = AnswerRequirements.fromRequest(PREFIX + "Calculate area for width 1e18 m and height 1e-18 m.");
        assertTrue(boundary.unresolved().isEmpty(), boundary.unresolved().toString());
    }

    @Test
    void precisionBoundsAndConflictsCannotBeDropped() {
        AnswerRequirements zero = AnswerRequirements.fromRequest(PREFIX + "Calculate area for width 2 m and height 3 m to 0 decimal places.");
        assertEquals(0, zero.precision());
        assertTrue(zero.unresolved().isEmpty());
        AnswerRequirements twelve = AnswerRequirements.fromRequest(PREFIX + NAMED + " Use 12 fractional digits.");
        assertEquals(12, twelve.precision());
        AnswerRequirements excessive = AnswerRequirements.fromRequest(PREFIX + NAMED + " Use 13 decimal places.");
        assertTrue(excessive.unresolved().contains("PRECISION_BOUND_EXCEEDED"));
        AnswerRequirements conflict = AnswerRequirements.fromRequest(PREFIX + NAMED + " Use 2 decimals. Use 3 decimals.");
        assertTrue(conflict.unresolved().contains("CONFLICTING_REQUEST_PRECISION"));
    }

    @Test
    void requirementLimitRetainsAnUnknownSpanForTheUnrepresentedSuffix() {
        String request = PREFIX + NAMED + " Give the correct selector.".repeat(31);
        AnswerRequirements result = AnswerRequirements.fromRequest(request);
        assertEquals(AnswerRequirements.MAX_REQUIREMENTS, result.requirements().size());
        assertTrue(result.unresolved().contains("REQUIREMENT_BOUND_EXCEEDED"));
        AnswerRequirements.Requirement last = result.requirements().get(result.requirements().size() - 1);
        assertEquals(UNKNOWN, last.kind());
        assertEquals(request.length(), last.end());
        AnswerRequirements boundary = AnswerRequirements.fromRequest(PREFIX + NAMED + " Give the correct selector.".repeat(30));
        assertEquals(AnswerRequirements.MAX_REQUIREMENTS, boundary.requirements().size());
        assertTrue(boundary.unresolved().isEmpty(), boundary.unresolved().toString());
    }

    @Test
    void requestAdmissionBoundNeverSilentlyTruncatesWholeCoverage() {
        String request = PREFIX + NAMED + " ".repeat(AnswerRequirements.MAX_REQUEST_CHARS);
        AnswerRequirements result = AnswerRequirements.fromRequest(request);
        assertTrue(result.applicable());
        assertTrue(result.unresolved().contains("REQUEST_BOUND_EXCEEDED"));
        AnswerRequirements.Requirement last = result.requirements().get(result.requirements().size() - 1);
        assertEquals(UNKNOWN, last.kind());
        assertEquals(request.length(), last.end());
    }

    @Test
    void ordinaryLegacyRequestsRemainOutsideTheRoute() {
        for (String request : List.of("", "Explain the code", PREFIX, PREFIX + "Give the correct selector.",
                "Calculate area for width 12 m and height 2.5 m.", "Read page.html and calculator.js. Explain why the button fails.")) {
            assertFalse(AnswerRequirements.fromRequest(request).applicable(), request);
        }
        assertFalse(AnswerRequirements.fromRequest(null).applicable());
    }

    @Test
    void ordinaryFileProhibitionsDoNotActivateTheComponentRouteForLegacyDiagnosis() {
        for (String negative : List.of("Do not", "Don't")) {
            for (String verb : List.of("edit", "modify", "change")) {
                for (String target : List.of("", " files", " any files")) {
                    for (String tests : List.of("", " or claim tests were run", " or claim that tests were run", " or run tests")) {
                        String request = PREFIX + "Give the correct selector. " + negative + " " + verb + target + tests + ".";
                        AnswerRequirements result = AnswerRequirements.fromRequest(request);
                        assertFalse(result.applicable(), request);
                        assertTrue(result.unresolved().isEmpty(), request);
                        assertFalse(result.has(UNKNOWN), request);
                        assertEquals(List.of(DIAGNOSIS, SELECTOR),
                                result.requirements().stream().map(AnswerRequirements.Requirement::kind).toList());
                    }
                }
            }
        }
    }

    @Test
    void recognizedFileProhibitionsPreserveTheFullRequestedAnswerComponents() {
        for (String restriction : List.of("Do not edit", "Do not modify files", "Do not change any files",
                "Don't change or claim tests were run", "Do not edit files or run tests")) {
            AnswerRequirements result = AnswerRequirements.fromRequest(HISTORICAL.replace(
                    "Do not edit files or claim tests were run", restriction));
            assertTrue(result.applicable(), restriction);
            assertTrue(result.unresolved().isEmpty(), restriction);
            assertEquals(List.of(DIAGNOSIS, SELECTOR, CALCULATION, SCRIPT_DISTINCTION),
                    result.requirements().stream().map(AnswerRequirements.Requirement::kind).toList());
        }
    }

    @Test
    void unrecognizedProhibitionTailsRemainUnknownWithoutMintingNegatedCalculations() {
        for (String restriction : List.of("Do not edit files or delete backups",
                "Do not modify files except the archive", "Do not change files to hide accessibility issues",
                "Do not edit or calculate area for width 12 m and height 2.5 m",
                "Do not edit files or claim tests were run and calculate area for width 12 m and height 2.5 m")) {
            String request = PREFIX + "Give the correct selector. " + restriction + ".";
            AnswerRequirements result = AnswerRequirements.fromRequest(request);
            assertTrue(result.applicable(), restriction);
            assertTrue(result.unresolved().contains("UNRECOGNIZED_REQUEST_CLAUSE"), restriction);
            assertTrue(result.has(UNKNOWN), restriction);
            assertFalse(result.has(CALCULATION), restriction);
            assertFalse(result.has(SCRIPT_DISTINCTION), restriction);
            var unknown = result.requirements().stream().filter(item -> item.kind() == UNKNOWN).findFirst().orElseThrow();
            assertEquals(restriction, request.substring(unknown.start(), unknown.end()));
        }
    }

    @Test
    void ordinaryProhibitionDoesNotHideASeparateMeaningfulUnknownRequest() {
        String request = PREFIX + "Give the correct selector. Do not change files. Also explain accessibility issues.";
        AnswerRequirements result = AnswerRequirements.fromRequest(request);
        assertTrue(result.applicable());
        assertTrue(result.unresolved().contains("UNRECOGNIZED_REQUEST_CLAUSE"));
        assertEquals(List.of(DIAGNOSIS, SELECTOR, UNKNOWN),
                result.requirements().stream().map(AnswerRequirements.Requirement::kind).toList());
        var unknown = result.requirements().getLast();
        assertEquals("Also explain accessibility issues", request.substring(unknown.start(), unknown.end()));
    }

    @Test
    void requirementsAreImmutableAndCannotBeInjectedIntoTheContract() {
        AnswerRequirements source = AnswerRequirements.fromRequest(HISTORICAL);
        assertThrows(UnsupportedOperationException.class, () -> source.requirements().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.operands().clear());
        assertThrows(UnsupportedOperationException.class, () -> source.unresolved().add("INJECTED"));
        assertThrows(UnsupportedOperationException.class, () -> source.scriptTargets().clear());

        TaskContract seven = new TaskContract(TaskType.UNKNOWN, false, false, false, Set.of(), Set.of(), HISTORICAL);
        TaskContract eight = new TaskContract(TaskType.UNKNOWN, false, false, false, Set.of(), Set.of(), HISTORICAL, "reason");
        TaskContract nine = new TaskContract(TaskType.UNKNOWN, false, false, false, Set.of(), Set.of(), Set.of(), HISTORICAL, "reason");
        TaskContract ten = new TaskContract(TaskType.UNKNOWN, false, false, false, Set.of(), Set.of(), Set.of(), HISTORICAL, "reason", StaticWebRequirements.none());
        for (TaskContract contract : List.of(seven, eight, nine, ten)) {
            assertTrue(contract.answerRequirements().applicable());
            assertEquals(HISTORICAL, contract.originalUserRequest());
        }
        TaskContract injected = new TaskContract(TaskType.UNKNOWN, false, false, false, Set.of(), Set.of(), Set.of(),
                "Read page.html.", "reason", StaticWebRequirements.none(), source);
        assertFalse(injected.answerRequirements().applicable());
        assertFalse(TaskContract.unknown(null).answerRequirements().applicable());
    }

    @Test
    void independentlyCapturedRequirementsRetainStructuralTaskContractEquality() {
        AnswerRequirements first = AnswerRequirements.fromRequest(HISTORICAL);
        AnswerRequirements second = AnswerRequirements.fromRequest(HISTORICAL);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        TaskContract left = TaskContract.unknown(HISTORICAL);
        TaskContract right = TaskContract.unknown(HISTORICAL);
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals(TaskContractResolver.fromUserRequest(HISTORICAL), TaskContractResolver.fromUserRequest(HISTORICAL));
    }
}
