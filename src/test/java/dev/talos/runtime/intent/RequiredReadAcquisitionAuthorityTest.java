package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acquisition authority only, independent of task classification and answer admission. */
class RequiredReadAcquisitionAuthorityTest {
    @Test void retainedRequestBindsAllThreeExplicitReadOperands() {
        String request = "Hey, this little local label project seems out of sync. "
                + "Read README.md, settings.json and label.py. Tell me what label the code would return, "
                + "what label is expected, and suggest the smallest change if needed. Don't change files or run anything.";

        assertEquals(Set.of("README.md", "settings.json", "label.py"),
                TaskIntentResolver.requiredReadTargetsForAcquisition(request));
    }

    @ParameterizedTest
    @MethodSource("directReadClauses")
    void directReadOperandsAreRequired(String request, Set<String> expected) {
        assertEquals(expected, TaskIntentResolver.requiredReadTargetsForAcquisition(request), request);
    }

    private static Stream<Arguments> directReadClauses() {
        var all = Set.of("README.md", "settings.json", "label.py");
        return Stream.of(
                Arguments.of("Please read README.md.", Set.of("README.md")),
                Arguments.of("Read `README.md`, \"settings.json\" and 'label.py'.", all),
                Arguments.of("Read README.md AND settings.json AND label.py.", all),
                Arguments.of("Read README.md, settings.json, label.py.", all),
                Arguments.of("Read README.md, settings.json, and label.py.", all),
                Arguments.of("Read README.md. Read settings.json. Read label.py.", all),
                Arguments.of("Read README.md. Explain how label.py uses settings.json.", Set.of("README.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "README.md, settings.json and label.py are relevant to this project.",
            "Tell me whether README.md and settings.json agree.",
            "\"Read README.md and settings.json.\"",
            "'Read README.md and settings.json.'",
            "`Read README.md and settings.json.`",
            "```text\nRead README.md and settings.json.\n```",
            "Optionally read README.md and settings.json.",
            "Read README.md and settings.json if needed.",
            "If needed, read README.md and settings.json.",
            "If README.md exists, read settings.json.",
            "The reviewer says read README.md and settings.json.",
            "The report states: read README.md and settings.json.",
            "The proposal is: read README.md and settings.json.",
            "Suggestion: read README.md and settings.json.",
            "Do not read README.md or settings.json.",
            "Don't read README.md and settings.json.",
            "Never read README.md and settings.json.",
            "Read README.md or settings.json.",
            "Read README.md and/or settings.json.",
            "Read README.md and / or settings.json.",
            "Read README.md, or settings.json.",
            "Read README.md or another source.",
            "Read README.md and/or another source.",
            "Read README.md, settings.json or another source."
    })
    void nonRequiredOrAmbiguousInstructionsDoNotMintReadObligations(String request) {
        assertTrue(TaskIntentResolver.requiredReadTargetsForAcquisition(request).isEmpty(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Read README.md or settings.json. Read label.py.",
            "Read README.md and/or settings.json. Read label.py.",
            "Read README.md or another source. Read label.py.",
            "Optionally read README.md and settings.json. Read label.py.",
            "The reviewer says read README.md and settings.json. Read label.py.",
            "\"Read README.md and settings.json.\" Read label.py.",
            "Do not read README.md or settings.json. Read label.py."
    })
    void anIndependentLaterDirectClauseRetainsItsOwnAuthority(String request) {
        assertEquals(Set.of("label.py"), TaskIntentResolver.requiredReadTargetsForAcquisition(request), request);
    }

    @Test void absentRequestHasNoReadAuthority() {
        assertTrue(TaskIntentResolver.requiredReadTargetsForAcquisition(null).isEmpty());
        assertTrue(TaskIntentResolver.requiredReadTargetsForAcquisition("").isEmpty());
        assertTrue(TaskIntentResolver.requiredReadTargetsForAcquisition(" \n\t ").isEmpty());
    }

    @Test void unestablishedCoordinatedScopeIsNotPromotedIntoRequiredReads() {
        assertTrue(TaskIntentResolver.requiredReadTargetsForAcquisition(
                "First read README.md, then read settings.json.").isEmpty());
    }
}
