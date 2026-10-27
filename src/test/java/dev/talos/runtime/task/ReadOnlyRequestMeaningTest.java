package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;

import static dev.talos.runtime.task.AnswerResultRequirements.Role.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyRequestMeaningTest {
    private static final String READ = "Read guide.md, index.html, engine.js and spare.js. ";
    private static final String FAILURE = "Explain exactly why the Compute button is broken. ";
    private static final String C2 = "Please read README.md, index.html, script.js, scripts.js and notes.md. "
            + "Explain exactly why the Calculate button is broken. Identify the selector that should be used, "
            + "calculate the BMI for 72 kg and 1.8 m, and explain how script.js differs from scripts.js. "
            + "Do not edit files or claim tests were run.";

    @Test
    void originalOrdinaryRequestHasUsefulSuccessorsWithoutNarrowingItsBroadComparison() {
        var meaning = ReadOnlyRequestMeaning.compile(C2);
        assertEquals(C2, meaning.request());
        assertEquals(List.of("README.md", "index.html", "script.js", "scripts.js", "notes.md"),
                meaning.readReferences().stream().map(ReadOnlyRequestMeaning.ReadReference::path).toList());
        assertEquals(List.of(DIAGNOSIS, SELECTOR, SOURCE_DEFINITION, UNKNOWN), roles(meaning));
        assertEquals("Calculate", meaning.target());
        assertFalse(meaning.targetIsId());
        assertFalse(meaning.requirements().complete());
        assertEquals(4, meaning.original().slots().stream().filter(slot -> slot.role() == UNKNOWN).count());
        assertEquals(1, meaning.regions().stream().filter(region -> region.disposition() == ReadOnlyRequestMeaning.Disposition.CONSTRAINT).count());
        assertTrue(meaning.requirements().slots().stream().filter(slot -> slot.role() == UNKNOWN)
                .anyMatch(slot -> C2.substring(slot.start(), slot.end()).equals("explain how script.js differs from scripts.js")));
        assertEquals(meaning.original().slots().stream().filter(AnswerResultRequirements.Slot::calculation).toList(),
                meaning.requirements().slots().stream().filter(AnswerResultRequirements.Slot::calculation).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Compare engine.js and spare.js in terms of HTML linkage",
            "Please distinguish engine.js and spare.js by their page linkage",
            "Explain how engine.js differs from spare.js in HTML linkage",
            "Identify which of engine.js and spare.js is linked by the HTML",
            "State which of engine.js and spare.js is loaded in the page"})
    void onlyExplicitLinkageConstructionsAcceptTheScriptRole(String linkage) {
        var meaning = ReadOnlyRequestMeaning.compile(READ + FAILURE + "Give the exact selector. " + linkage + ".");
        assertTrue(meaning.requirements().complete(), meaning.requirements().unresolved().toString());
        assertEquals(List.of(DIAGNOSIS, SELECTOR, SCRIPT_DISTINCTION), roles(meaning));
        assertEquals(List.of("engine.js", "spare.js"), meaning.scriptTargets());
        assertTrue(meaning.regions().stream().anyMatch(region -> region.ruleId().equals("explicit-script-linkage")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Compare engine.js and spare.js", "Distinguish the two JavaScript files",
            "Explain how engine.js differs from spare.js", "Compare whichever scripts appear relevant",
            "Compare engine.js and spare.js in terms of performance", "Identify which of engine.js and spare.js is linked by other.html"})
    void broadScriptRequestsAndUnrepresentedHtmlOwnersStayUnresolved(String question) {
        var meaning = ReadOnlyRequestMeaning.compile(READ + FAILURE + question + ".");
        assertFalse(meaning.requirements().complete());
        assertEquals(UNKNOWN, meaning.requirements().slots().getLast().role());
        assertTrue(meaning.scriptTargets().isEmpty());
    }

    @Test
    void twoResultsInsideOneOriginalUnknownHaveSeparateNoncollidingSuccessors() {
        String request = "Please describe why the Launch button is broken and would you please state the selector that should be used.";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(1, meaning.original().slots().size());
        assertEquals(UNKNOWN, meaning.original().slots().getFirst().role());
        assertEquals(List.of(DIAGNOSIS, SELECTOR), roles(meaning));
        assertTrue(meaning.requirements().complete());
        assertEquals(2, new HashSet<>(meaning.requirements().slots().stream().map(AnswerResultRequirements.Slot::id).toList()).size());
        assertTrue(meaning.requirements().slots().stream().allMatch(slot -> slot.id().startsWith("meaning-")));
        assertTrue(meaning.regions().stream().allMatch(region -> region.originalSlotId().equals(meaning.original().slots().getFirst().id())));
    }

    @Test
    void readAcceptanceCannotEraseAnotherRegionWithTheSameReasonOrItsOwnRemainder() {
        String request = "Please inspect records.md and describe the page appearance. Please explain its performance impact.";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(List.of("records.md"), meaning.readReferences().stream().map(ReadOnlyRequestMeaning.ReadReference::path).toList());
        assertEquals(2, meaning.requirements().slots().size());
        assertEquals(List.of(UNKNOWN, UNKNOWN), roles(meaning));
        assertFalse(meaning.requirements().complete());
        assertEquals(2, meaning.requirements().regionReasons().size());
        assertTrue(meaning.requirements().slots().stream().anyMatch(slot -> request.substring(slot.start(), slot.end()).equals("describe the page appearance")));
    }

    @Test
    void modalReadsRetainExactOriginalLiteralOffsetsAndDoNotInventAnAnswerResult() {
        String request = "Could you please inspect `src/notes.md` and manual.txt?";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertTrue(meaning.requirements().complete(), meaning.requirements().unresolved().toString());
        assertTrue(meaning.requirements().slots().isEmpty());
        assertEquals(List.of("src/notes.md", "manual.txt"), meaning.readReferences().stream().map(ReadOnlyRequestMeaning.ReadReference::path).toList());
        for (var read : meaning.readReferences()) {
            assertEquals(read.path(), request.substring(read.start(), read.end()).replace("`", ""));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain exactly why the Compute button is red.",
            "Do not explain exactly why the Compute button is broken.",
            "If asked, please explain exactly why the Compute button is broken.",
            "Please explain exactly why the Compute button is broken if needed.",
            "The guide says please explain exactly why the Compute button is broken.",
            "\"Please explain exactly why the Compute button is broken.\"",
            "Please describe why the Compute or Submit button is broken.",
            "Please describe why it is broken.",
            "Please magically explain why the Compute button is broken.",
            "Please explain why the `#compute button is broken",
            "Please explain why the #compute` button is broken",
            "Please explain why the Compute button is broken and fix it."})
    void excludedScopeAndUnconsumedMeaningCannotBecomeComplete(String request) {
        var meaning = ReadOnlyRequestMeaning.compile(READ + request);
        assertFalse(meaning.requirements().complete(), request);
        assertTrue(meaning.requirements().slots().stream().anyMatch(slot -> slot.role() == UNKNOWN), request);
    }

    @Test
    void anExistingKnownClauseCannotEscapeAnExcludedSentenceEnvelope() {
        var meaning = ReadOnlyRequestMeaning.compile(READ
                + "Never explain why the Compute button fails and explain why the Reset button fails.");
        assertFalse(meaning.requirements().complete());
        assertTrue(meaning.requirements().slots().stream().allMatch(slot -> slot.role() == UNKNOWN));
        assertEquals("", meaning.target());
    }

    @Test
    void explicitAndImplicitSelectorsUseOnlyUniqueRequestTargets() {
        var explicit = ReadOnlyRequestMeaning.compile(READ + "Please identify the exact selector for the #launch button.");
        assertEquals("launch", explicit.target());
        assertTrue(explicit.targetIsId());
        assertTrue(explicit.requirements().complete());
        var absent = ReadOnlyRequestMeaning.compile(READ + "Please state the exact selector.");
        assertEquals(List.of(UNKNOWN), roles(absent));
        assertTrue(absent.requirements().unresolved().contains("UNBOUND_SELECTOR_TARGET"));
        var ambiguous = ReadOnlyRequestMeaning.compile(READ + FAILURE + "Explain why the Submit button fails. Give the exact selector.");
        assertTrue(ambiguous.targetAmbiguous());
        assertEquals("", ambiguous.target());
        assertEquals(List.of(UNKNOWN, UNKNOWN, UNKNOWN), roles(ambiguous));
    }

    @Test
    void conflictingScriptPairsNeverAcquireOneArbitraryOwner() {
        var meaning = ReadOnlyRequestMeaning.compile(READ + FAILURE
                + "Identify which of engine.js and spare.js is linked by the HTML. "
                + "Identify which of engine.js and other.js is linked by the HTML.");
        assertTrue(meaning.scriptsAmbiguous());
        assertTrue(meaning.scriptTargets().isEmpty());
        assertEquals(List.of(DIAGNOSIS, UNKNOWN, UNKNOWN), roles(meaning));
    }

    @Test
    void numericalSlotsAndComparisonPeersAreNotReinterpretedOrRenumbered() {
        String request = READ + FAILURE + "For span 12 m and rise 2.5 m, give the panel area according to guide.md "
                + "and the mathematical value of the expression in engine.js separately. Explain any disagreement. "
                + "Calculate area for width 1e19 cm and height 4 cm. Please inspect notes.md.";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        for (var slot : meaning.original().slots()) {
            if (slot.calculation() || slot.role() == COMPARISON || meaning.original().regionReasons()
                    .getOrDefault(slot.id(), List.of()).contains("UNSUPPORTED_RESULT_INPUTS")) {
                assertTrue(meaning.requirements().slots().contains(slot), slot.toString());
            }
        }
        assertTrue(meaning.requirements().unresolved().contains("UNSUPPORTED_RESULT_INPUTS"));
        assertFalse(meaning.requirements().complete());
    }

    @Test
    void exactNonnumericRolesCanDisambiguateLegacyCalculationFallbackWithoutRepairingNumericInputs() {
        String request = READ + "Give the correct selector for the Compute button. " + FAILURE
                + "Give flow according to guide.md for volume 24 L and elapsed 6 s, "
                + "and identify which of engine.js and spare.js is linked by the HTML. "
                + "Calculate area for width 1e19 cm and height 4 cm.";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(List.of(SELECTOR, DIAGNOSIS, SOURCE_DEFINITION, SCRIPT_DISTINCTION, UNKNOWN), roles(meaning));
        assertTrue(meaning.original().regionReasons().values().stream().anyMatch(reasons -> reasons.contains("UNSUPPORTED_RESULT_REQUEST")));
        assertEquals(2, meaning.original().regionReasons().values().stream().filter(reasons -> reasons.contains("UNSUPPORTED_RESULT_INPUTS")).count());
        assertEquals(1, meaning.requirements().regionReasons().values().stream().filter(reasons -> reasons.contains("UNSUPPORTED_RESULT_INPUTS")).count());
        assertFalse(meaning.requirements().complete());
        assertEquals(meaning.original().slots().stream().filter(AnswerResultRequirements.Slot::calculation).toList(),
                meaning.requirements().slots().stream().filter(AnswerResultRequirements.Slot::calculation).toList());
    }

    @Test
    void unchangedCanonicalDiagnosisAndSelectorSlotsRemainIdentical() {
        String request = READ + "Explain why the Compute button does not work and give the correct selector. "
                + "Give flow according to guide.md for volume 24 L and elapsed 6 s.";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(meaning.original().slots(), meaning.requirements().slots());
        assertTrue(meaning.requirements().complete());
    }

    @Test
    void boundsInvalidUnicodeAndRepeatedOccurrencesRetainTheirOwnership() {
        String oversized = "x".repeat(AnswerResultRequirements.MAX_REQUEST_CHARS + 1);
        var bounded = ReadOnlyRequestMeaning.compile(oversized);
        assertEquals(bounded.original().slots(), bounded.requirements().slots());
        assertTrue(bounded.requirements().unresolved().contains("REQUEST_BOUND_EXCEEDED"));
        assertFalse(ReadOnlyRequestMeaning.compile("Please explain why the Com\u200bpute button is broken.").requirements().complete());
        var repeated = ReadOnlyRequestMeaning.compile(FAILURE + FAILURE);
        assertEquals(List.of(DIAGNOSIS, DIAGNOSIS), roles(repeated));
        assertNotEquals(repeated.requirements().slots().getFirst().id(), repeated.requirements().slots().getLast().id());
        String overflow = ("Please describe why the Compute button is broken "
                + "and would you please state the selector that should be used. ").repeat(AnswerResultRequirements.MAX_SLOTS);
        var many = ReadOnlyRequestMeaning.compile(overflow);
        assertTrue(many.requirements().slots().size() <= AnswerResultRequirements.MAX_SLOTS);
        assertFalse(many.requirements().complete());
        assertTrue(many.requirements().unresolved().contains("MEANING_SLOT_BOUND_EXCEEDED"));
    }

    @Test
    void malformedLegacyReadCannotOverflowAFullResultLedger() {
        String request = "Explain why the Compute button fails. ".repeat(AnswerResultRequirements.MAX_SLOTS)
                + "Read notes.md`";
        var meaning = ReadOnlyRequestMeaning.compile(request);
        assertEquals(AnswerResultRequirements.MAX_SLOTS, meaning.original().slots().size());
        assertEquals(AnswerResultRequirements.MAX_SLOTS, meaning.requirements().slots().size());
        assertFalse(meaning.requirements().complete());
        assertTrue(meaning.requirements().unresolved().contains("UNSUPPORTED_READ_REGION"));
        assertTrue(meaning.regions().stream().anyMatch(region -> region.reasons().contains("UNSUPPORTED_READ_REGION")
                && region.successorSlotIds().isEmpty()));
    }

    @Test
    void planAndRegionReasonCollectionsAreImmutableAndIdentityBindsTheRequest() {
        var first = ReadOnlyRequestMeaning.compile(C2);
        var second = ReadOnlyRequestMeaning.compile(C2);
        assertEquals(first.identity(), second.identity());
        assertEquals(first.regions(), second.regions());
        assertNotEquals(first.identity(), ReadOnlyRequestMeaning.compile(C2 + " ").identity());
        assertThrows(UnsupportedOperationException.class, () -> first.regions().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.readReferences().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.original().regionReasons().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.original().regionReasons().values().iterator().next().clear());
        assertTrue(java.util.Arrays.stream(ReadOnlyRequestMeaning.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(ReadOnlyRequestMeaning.compile(null).requirements().slots().isEmpty());
    }

    private static List<AnswerResultRequirements.Role> roles(ReadOnlyRequestMeaning meaning) {
        return meaning.requirements().slots().stream().map(AnswerResultRequirements.Slot::role).toList();
    }
}
