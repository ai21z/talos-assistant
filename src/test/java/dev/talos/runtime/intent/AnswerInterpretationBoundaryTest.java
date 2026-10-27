package dev.talos.runtime.intent;

import dev.talos.runtime.task.AnswerRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerInterpretationBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Explain why the Submit button fails",
            "Explain exactly why the Submit button is broken",
            "Please explain the Submit button behavior",
            "Could you describe the Submit button behavior",
            "Explain why the Submit button is red",
            "Describe another unsupported topic",
            "Explain the notifier behavior",
            "Explain the Submit button behavior\r\nwith the retained source"
    })
    void structuralEligibilityDoesNotEstablishMeaning(String request) {
        assertTrue(AnswerInterpretationBoundary.eligible(request, 0, request.length()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not explain the Submit button",
            "Do NOT explain the Submit button",
            "Never explain the Submit button",
            "Explain the Submit button without a diagnosis",
            "Explain something instead of the Submit button",
            "If the Submit button failed, explain it",
            "Explain the Submit button when it fails",
            "Assuming the Submit button fails, explain it",
            "Suppose the Submit button fails",
            "Imagine the Submit button is broken",
            "A hypothetical Submit button failure",
            "An example says explain the Submit button",
            "Someone requested an explanation of the Submit button",
            "Someone asks for an explanation of the Submit button",
            "Someone reported an explanation of the Submit button",
            "Someone wrote an explanation of the Submit button",
            "Follow the instructions about the Submit button",
            "The Submit button isn't broken",
            "The Submit button isn\u2019t broken",
            "Don't explain the Submit button",
            "The Submit button cannot be diagnosed"
    })
    void visibleNegativeConditionalAndReportingMarkersAreExcluded(String request) {
        assertFalse(AnswerInterpretationBoundary.eligible(request, 0, request.length()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"Explain the Submit button\"",
            "'Explain the Submit button'",
            "`Explain the Submit button`",
            "```Explain the Submit button```",
            "~~~Explain the Submit button~~~",
            "\u201cExplain the Submit button\u201d",
            "> Explain the Submit button",
            "(Explain the Submit button)",
            "[Explain the Submit button]",
            "{Explain the Submit button}",
            "Explain the Submit button (details)",
            "Explain the Submit button (",
            "Explain the Submit button ]",
            "\"Explain the Submit button"
    })
    void quotedNestedAndMalformedEnvelopesAreExcluded(String request) {
        int start = request.indexOf("Explain");
        int end = start + "Explain the Submit button".length();
        assertFalse(AnswerInterpretationBoundary.eligible(request, start, end));
        assertFalse(AnswerInterpretationBoundary.eligible(request, 0, request.length()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "If anything fails, Explain the Submit button",
            "Someone said, Explain the Submit button",
            "Do not Explain the Submit button",
            "Explain the Submit button, assuming failure",
            "Explain the Submit button and do not diagnose it"
    })
    void selectedSubstringCannotDiscardSentenceRestrictions(String request) {
        int start = request.indexOf("Explain");
        int end = start + "Explain the Submit button".length();
        assertFalse(AnswerInterpretationBoundary.eligible(request, start, end));
    }

    @Test
    void independentlyBoundedSentenceDoesNotInheritAdjacentSentenceRestriction() {
        String request = "Do not edit files. Explain the Submit button behavior. Do not run tests.";
        int start = request.indexOf("Explain");
        int end = request.indexOf('.', start);
        assertTrue(AnswerInterpretationBoundary.eligible(request, start, end));
    }

    @Test
    void controlsAndMalformedUnicodeAreRejectedBeforeStructuralMatching() {
        for (String control : new String[]{"\u0000", "\u0008", "\u000b", "\u001b", "\u007f", "\u0085", "\u009f", "\u200b", "\u202e", "\r", "\ud800", "\udc00"}) {
            String request = "Explain the Submit" + control + " button";
            assertFalse(AnswerInterpretationBoundary.eligible(request, 0, request.length()));
        }
    }

    @Test
    void emptyOutOfRangeAndOversizedInputsAreRejected() {
        assertFalse(AnswerInterpretationBoundary.eligible(null, 0, 1));
        assertFalse(AnswerInterpretationBoundary.eligible("", 0, 0));
        assertFalse(AnswerInterpretationBoundary.eligible(" ", 0, 1));
        String request = "Explain the Submit button";
        assertFalse(AnswerInterpretationBoundary.eligible(request, -1, request.length()));
        assertFalse(AnswerInterpretationBoundary.eligible(request, 0, request.length() + 1));
        assertFalse(AnswerInterpretationBoundary.eligible(request, 5, 5));
        assertFalse(AnswerInterpretationBoundary.eligible(request, 8, 2));
        assertFalse(AnswerInterpretationBoundary.eligible(request + "!", request.length(), request.length() + 1));
        String bounded = "A".repeat(AnswerRequirements.MAX_REQUEST_CHARS);
        assertTrue(AnswerInterpretationBoundary.eligible(bounded, 0, bounded.length()));
        assertFalse(AnswerInterpretationBoundary.eligible(bounded + "A", 0, bounded.length()));
    }

    @Test
    void partialTokensAndSurrogateBoundariesAreRejected() {
        String request = "NotExplain the Submit button";
        assertFalse(AnswerInterpretationBoundary.eligible(request, 3, request.length()));
        assertFalse(AnswerInterpretationBoundary.eligible(request, 0, request.length() - 1));
        String supplementary = "Explain \ud83d\ude00 button";
        int high = supplementary.indexOf('\ud83d');
        assertTrue(AnswerInterpretationBoundary.eligible(supplementary, 0, supplementary.length()));
        assertFalse(AnswerInterpretationBoundary.eligible(supplementary, high + 1, supplementary.length()));
        assertFalse(AnswerInterpretationBoundary.eligible(supplementary, 0, high + 1));
    }

    @Test
    void repeatedOccurrencesRemainSeparatelyAddressable() {
        String first = "Explain the Submit button";
        String request = first + ". " + first + ".";
        assertTrue(AnswerInterpretationBoundary.eligible(request, 0, first.length()));
        int second = request.lastIndexOf(first);
        assertTrue(AnswerInterpretationBoundary.eligible(request, second, second + first.length()));
    }
}
