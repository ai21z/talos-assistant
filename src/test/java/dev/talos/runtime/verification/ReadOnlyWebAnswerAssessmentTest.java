package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Disposition;
import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Reason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyWebAnswerAssessmentTest {
    private static final List<String> READS = List.of("README.md", "index.html", "script.js", "scripts.js");
    private static final String BASE = "`#calc` does not exist in index.html. Use `#calculate`.\n\n";

    @Test
    void dispositionAndReasonAreKeptConsistent() {
        for (Disposition disposition : Disposition.values()) {
            for (Reason reason : Reason.values()) {
                boolean compatible = switch (disposition) {
                    case NOT_APPLICABLE -> reason == Reason.NOT_APPLICABLE;
                    case PRESERVED -> reason == Reason.PRESERVED;
                    case RUNTIME_DERIVED -> reason == Reason.RUNTIME_DERIVED;
                    case LIMITED -> reason != Reason.NOT_APPLICABLE && reason != Reason.PRESERVED && reason != Reason.RUNTIME_DERIVED;
                };
                if (compatible) {
                    var assessment = new ReadOnlyWebAnswerAssessment(disposition, reason);
                    assertEquals(disposition == Disposition.PRESERVED, assessment.preserved());
                    assertEquals(disposition == Disposition.LIMITED, assessment.limited());
                } else {
                    assertThrows(IllegalArgumentException.class,
                            () -> new ReadOnlyWebAnswerAssessment(disposition, reason));
                }
            }
        }
        assertThrows(NullPointerException.class, () -> new ReadOnlyWebAnswerAssessment(null, Reason.PRESERVED));
        assertThrows(NullPointerException.class, () -> new ReadOnlyWebAnswerAssessment(Disposition.PRESERVED, null));
        assertEquals(new ReadOnlyWebAnswerAssessment(Disposition.NOT_APPLICABLE, Reason.NOT_APPLICABLE),
                ReadOnlyWebAnswerAssessment.notApplicable());
        assertEquals(new ReadOnlyWebAnswerAssessment(Disposition.PRESERVED, Reason.PRESERVED),
                ReadOnlyWebAnswerAssessment.preservedAnswer());
    }

    @Test
    void invalidInputsAreLimitedBeforeTextInspection() {
        var facts = facts(Set.of("calc"));
        var diagnostics = facts.selectorDiagnostics();
        for (String answer : Arrays.asList(null, "", " ", "x".repeat(65_537))) {
            assertLimited(Reason.INVALID_INPUT,
                    ReadOnlyWebDiagnosticAnswer.assess(answer, READS, facts, diagnostics));
        }
        assertLimited(Reason.INVALID_INPUT, ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, null, diagnostics));
        assertLimited(Reason.INVALID_INPUT, ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, null));
        assertLimited(Reason.INVALID_INPUT, ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, List.of()));
    }

    @Test
    void diagnosticEligibilityIsBoundToItsKindRatherThanEnglishText() {
        var facts = facts(Set.of("calc"));
        var diagnostic = new StaticWebDiagnostic(StaticWebDiagnostic.Kind.JAVASCRIPT_MISSING_ID,
                "script.js", "index.html", List.of("calc"), "A selector mismatch was observed.");
        assertTrue(ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, List.of(diagnostic)).preserved());
        var unsupported = new StaticWebDiagnostic(StaticWebDiagnostic.Kind.OTHER,
                "script.js", "index.html", List.of("calc"), "JavaScript references missing IDs: `#calc`");
        assertLimited(Reason.UNSUPPORTED_DIAGNOSTIC,
                ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, List.of(unsupported)));
        assertLimited(Reason.UNSUPPORTED_DIAGNOSTIC,
                ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, List.of(diagnostic, unsupported)));
        assertLimited(Reason.UNSUPPORTED_DIAGNOSTIC,
                ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, facts, Arrays.asList(diagnostic, null)));
    }

    @Test
    void existingTopLevelRestrictionsAreReportedSeparately() {
        assertLimited(Reason.UNSUPPORTED_ASSERTION, assess(BASE + "Everything works correctly."));
        assertLimited(Reason.EXECUTION_CLAIM_WITHOUT_EVIDENCE, assess(BASE + "The tests passed."));
        assertLimited(Reason.SELECTOR_CONTRADICTION, assess(BASE + "`#calculate` is missing."));
        assertLimited(Reason.UNREAD_PATH, assess(BASE + "The details are in unread.js."));
        assertLimited(Reason.SELECTOR_RELATION_NOT_ESTABLISHED, assess("The selector is `#calc`."));
        assertLimited(Reason.UNOBSERVED_ID_SELECTOR, assess(BASE + "The alternative selector is `#invented`."));
        assertLimited(Reason.UNOBSERVED_CLASS_SELECTOR, assess(BASE + "The alternative selector is `.invented`."));
    }

    @Test
    void selectorBudgetsAndAbsentMismatchesRemainLimited() {
        var noMismatch = facts(Set.of("calculate"));
        assertLimited(Reason.NO_MISSING_SELECTOR,
                ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, noMismatch, facts(Set.of("calc")).selectorDiagnostics()));
        Set<String> selectors = new LinkedHashSet<>();
        for (int index = 0; index < 33; index++) selectors.add("missing" + index);
        var tooMany = facts(selectors);
        assertLimited(Reason.SELECTOR_LIMIT_EXCEEDED,
                ReadOnlyWebDiagnosticAnswer.assess(BASE, READS, tooMany, tooMany.selectorDiagnostics()));
    }

    @Test
    void firstApplicableRestrictionIsRetained() {
        assertLimited(Reason.UNSUPPORTED_ASSERTION,
                assess(BASE + "Everything works correctly. The tests passed. The details are in unread.js."));
        assertLimited(Reason.EXECUTION_CLAIM_WITHOUT_EVIDENCE,
                assess(BASE + "The tests passed. `#calculate` is missing. The details are in unread.js."));
        assertLimited(Reason.SELECTOR_CONTRADICTION,
                assess(BASE + "`#calculate` is missing. The details are in unread.js."));
        assertLimited(Reason.UNREAD_PATH, assess("The selector is `#calc`. The details are in unread.js."));
    }

    @Test
    void earlierRecordedAnswerIsStillPreserved() throws Exception {
        String answer = earlierAnswer();
        assertEquals(596, answer.length());
        assertEquals(598, answer.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("ec222de0ce8adc90b574833fcce563b1024c0123770b74748815ea2c69c8e474", sha256(answer));
        assertEquals(ReadOnlyWebAnswerAssessment.preservedAnswer(), assess(answer));
    }

    @Test
    void latestRecordedParentheticalRelationRemainsUnestablishedRatherThanDeclaredFalse() throws Exception {
        String answer = latestAnswer();
        assertEquals(681, answer.length());
        assertEquals(683, answer.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("40f24fae74a62a48adbc235b59c79f2ada1cbbbc968d321e20405768af518801", sha256(answer));
        assertLimited(Reason.SELECTOR_RELATION_NOT_ESTABLISHED, assess(answer));
        assertLimited(Reason.SELECTOR_RELATION_NOT_ESTABLISHED, assess(answer.replace("22.19", "22.22")));
    }

    @Test
    void arithmeticIsNotAssessedByTheSelectorPreservationGate() {
        assertTrue(assess(earlierAnswer()).preserved());
        assertTrue(assess(earlierAnswer().replace("22.2", "22.19")).preserved());
        assertTrue(assess(BASE + "72 divided by 3.24 equals 22.22.").preserved());
        assertTrue(assess(BASE + "72 divided by 3.24 equals 900.").preserved());
    }

    @Test
    void compatibilityWrapperUsesTheSameAssessment() {
        var facts = facts(Set.of("calc"));
        for (String answer : List.of(BASE, earlierAnswer(), latestAnswer(), BASE + "The tests passed.")) {
            assertEquals(ReadOnlyWebDiagnosticAnswer.assess(answer, READS, facts, facts.selectorDiagnostics()).preserved(),
                    ReadOnlyWebDiagnosticAnswer.canPreserve(answer, READS, facts, facts.selectorDiagnostics()));
        }
    }

    private static void assertLimited(Reason reason, ReadOnlyWebAnswerAssessment assessment) {
        assertEquals(Disposition.LIMITED, assessment.disposition());
        assertEquals(reason, assessment.reason());
        assertTrue(assessment.limited());
        assertFalse(assessment.preserved());
    }

    private static ReadOnlyWebAnswerAssessment assess(String answer) {
        var facts = facts(Set.of("calc"));
        return ReadOnlyWebDiagnosticAnswer.assess(answer, READS, facts, facts.selectorDiagnostics());
    }

    private static StaticWebSelectorAnalyzer.Facts facts(Set<String> jsIds) {
        return new StaticWebSelectorAnalyzer.Facts(
                "index.html", "", "script.js", Set.of(), Set.of("weight", "height", "calculate", "result"),
                List.of("weight", "height", "calculate", "result"), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), jsIds, Set.of(), Set.of("script.js"), List.of(), List.of("script.js"),
                "<button id=\"calculate\">Calculate</button><script src=\"script.js\"></script>", "",
                "const button = document.querySelector('#calc');", Set.copyOf(READS));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String earlierAnswer() {
        return "The Calculate button fails because the JavaScript code in `script.js` is using the incorrect "
                + "selector `#calc` instead of `#calculate`. The correct selector should be `#calculate` to match the ID "
                + "of the Calculate button in `index.html`.\n\n"
                + "To calculate the BMI for 72 kg and 1.8 m, you would use the formula: BMI = weight (kg) / (height (m) ^ 2). "
                + "Plugging in the given values:\n\n"
                + "BMI = 72 / (1.8 * 1.8) = 72 / 3.24 \u2248 22.2\n\n"
                + "The `script.js` file is the one that is actually being used by `index.html` to handle the BMI calculation. "
                + "The `scripts.js` file is an unrelated sentinel and must stay unchanged.";
    }

    private static String latestAnswer() {
        return "The Calculate button fails because the JavaScript code in `script.js` is using an incorrect selector "
                + "(`#calc`) for the button, which does not match the actual button ID (`#calculate`) in `index.html`.\n\n"
                + "The correct selector for the Calculate button in `script.js` should be `#calculate`.\n\n"
                + "To calculate the BMI for 72 kg and 1.8 m:\n"
                + "- BMI = weight / (height * height)\n"
                + "- BMI = 72 / (1.8 * 1.8)\n"
                + "- BMI = 72 / 3.24\n"
                + "- BMI \u2248 22.19\n\n"
                + "The two JavaScript files are:\n"
                + "1. `script.js`: This file is correctly linked in `index.html` and contains the logic for calculating the BMI.\n"
                + "2. `scripts.js`: This file is not linked in `index.html` and serves as an unrelated sentinel that must stay unchanged.";
    }
}
