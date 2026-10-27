package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static dev.talos.runtime.intent.WorkStatusRequest.Coverage.*;
import static dev.talos.runtime.intent.WorkStatusRequest.Role.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkStatusRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a short handoff. What was denied earlier, what actually changed afterwards, and what remains unverified? Do not change files or run tests.",
            "Please summarize the handoff. Which changes were performed in this conversation? Keep the answer brief.",
            "What was blocked in this session? Give me a handoff. Is the work verified?",
            "Give me a handoff. Did any tests run? Is this ready for release?",
            "Give me a handoff. What remains unchecked?"
    })
    void explicitRuntimeQuestionsOwnTheFrameWithoutEstablishingEvidenceCompleteness(String text) {
        var request = WorkStatusRequest.parse(text);
        assertEquals(COVERED, request.coverage(), request.toString());
        assertTrue(request.runtimeOnly(), request.toString());
        assertTrue(request.components().stream().anyMatch(component -> request.domain(component)
                == WorkStatusRequest.EvidenceDomain.RUNTIME));
        assertTrue(request.components().stream().allMatch(component -> request.domain(component)
                != WorkStatusRequest.EvidenceDomain.PROJECT));
        assertTrue(CommandExecutionRequest.parse(text).commandClauses().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff.",
            "Give me a handoff. Keep it short. Don't change anything.",
            "Give me a handoff. What is known?",
            "Give me a handoff. What remains?",
            "Give me a handoff. What remains unknown?",
            "Give me a handoff. What was denied earlier? What should happen next?",
            "Give me a handoff. Is this ready for release? Why did the design fail?",
            "Give me a handoff. \"What was changed?\"",
            "Give me a handoff. What remains unverified? {"
    })
    void bareMixedAndUnsupportedRequestsDoNotLoseProjectOrUnresolvedRequirements(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected(), request.toString());
        assertFalse(request.runtimeOnly(), request.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Give me a handoff. What was denied earlier?",
            "Give me a handoff. What was rejected?", "Give me a handoff. What was blocked?",
            "Give me a handoff. What actually changed?", "What was done in this conversation?"})
    void runtimeOperationQuestionsRequireDetailsNotOnlyCounts(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.runtimeOnly(), request.toString());
        assertTrue(request.requiresOperationDetails(), request.toString());
    }

    @Test
    void deniedThenChangedHandoffRetainsHistoryAndDistributedProhibition() {
        String text = "Give me a short handoff. What was denied earlier, what actually changed afterwards, "
                + "and what remains unverified? Do not change files or run tests.";
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected());
        assertEquals(COVERED, request.coverage(), request.toString());
        assertTrue(request.roles().containsAll(Set.of(ACTIONS, VERIFICATION, UNCERTAINTY, PRESENTATION)));
        assertTrue(CommandExecutionRequest.parse(text).commandClauses().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What changed during this session? Include uncertainty.",
            "What changed during this session? Include uncertainty. Explain exponential backoff.",
            "Explain exponential backoff. What changed during this session? Include uncertainty.",
            "What actually changed in this conversation?",
            "What was changed for the current chat?",
            "What was denied earlier during this session?",
            "What was rejected in this conversation?",
            "What was blocked in the current session?"
    })
    void eventComponentsDoNotCreateRetainedStatusOwnership(String text) {
        var request = WorkStatusRequest.parse(text);
        assertFalse(request.selected(), request.toString());
        assertTrue(request.components().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. What changed during this session?",
            "What was denied earlier? Give me a handoff.",
            "Is the work verified? What actually changed in this conversation?",
            "What was blocked in this session? Is the work tested?",
            "What is known during this session?",
            "What was done in this conversation?",
            "What has been done in the current chat?",
            "What was performed for this session?",
            "Which changes were performed in this conversation?"
    })
    void independentStatusAndExistingScopedAnchorsStillOwnSupportedComponents(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected(), request.toString());
        assertEquals(COVERED, request.coverage(), request.toString());
        assertTrue(request.roles().contains(ACTIONS), request.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not edit files or execute commands.",
            "Don't inspect files or run tests or execute commands.",
            "Never change files or run tests.",
            "Please do not run commands or modify any files."
    })
    void distributedProhibitionsKeepRetainedStatusOwnership(String restriction) {
        var request = WorkStatusRequest.parse("Give me a handoff. " + restriction);
        assertTrue(request.selected(), restriction);
        assertEquals(COVERED, request.coverage(), request.toString());
    }

    private static final String RETAINED = "Let's stop here. Give me a short handoff. "
            + "What is known, what remains unverified, and what should happen next? "
            + "Can this be called tested and ready to ship? "
            + "Keep proposed work separate from anything actually done in this conversation. "
            + "Don't change or run anything.";

    @Test
    void retainedHandoffHasSeparateRequestedResultsAndOriginalOffsets() {
        var request = WorkStatusRequest.parse(RETAINED);
        assertTrue(request.selected());
        assertEquals(COVERED, request.coverage());
        assertEquals(RETAINED, request.originalRequest());
        assertEquals(Set.of(ACTIONS, VERIFICATION, UNCERTAINTY, TESTING, RELEASE,
                NEXT_STEPS, PROPOSALS, PROJECT_HANDOFF, PRESENTATION), request.roles());
        assertFalse(request.hasUnsupported());
        assertEquals(9, request.components().size());
        assertTrue(request.components().stream().anyMatch(component -> component.roles().equals(Set.of(TESTING))));
        assertTrue(request.components().stream().anyMatch(component -> component.roles().equals(Set.of(RELEASE))));
        int previousEnd = 0;
        for (var component : request.components()) {
            assertTrue(component.start() >= previousEnd);
            assertFalse(RETAINED.substring(component.start(), component.end()).isBlank());
            previousEnd = component.end();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Please give me a short handoff. Is this tested and ready for release?",
            "Can you give me a handoff? Can this be called ready to ship and tested?",
            "Could you please summarize the work status? What should happen next and what remains unverified?",
            "Give me a handoff; what is known; what remains unverified; what should happen next?",
            "Give me a handoff. Is this work tested, verified, and ready to ship?",
            "Give me a handoff. Don't inspect or read any files.",
            "Give me a handoff. Did tests run in this conversation?",
            "Is the work tested and ready to ship?",
            "Is the work verified?",
            "What remains unverified in this conversation?",
            "What is known during this session?"
    })
    void finiteCompositionSupportsPolitenessOrderAndSeparators(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected(), text);
        assertEquals(COVERED, request.coverage(), request.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. Do the current files agree?",
            "Give me a handoff and explain recursion.",
            "Give me a handoff for the previous session.",
            "Give me a handoff. What was done yesterday?",
            "Is this tested and ready to ship?",
            "Give me a handoff. Is the project tested?",
            "Give me a handoff. Was the work tested?",
            "Give me a handoff. Is this not tested?",
            "Summarize whether the files agree and whether this is tested.",
            "Give me a handoff with \"ready to ship\" in the title.",
            "Give me a handoff. \"Is this ready to ship?\"",
            "What changed? Is the work verified?",
            "Is the work verified? What changed?",
            "What changed? Is it tested?",
            "Is it tested? What changed?",
            "What changed? Is it ready to ship?",
            "Is it verified and tested?",
            "Is it verified and ready for release?",
            "Is it verified in this conversation?",
            "What remains unverified in this conversation and explain recursion?",
            "Give me a handoff. Please what remains unverified tomorrow?",
            "Give me a handoff. Is the work tested? Ready to ship?"
    })
    void uncertainMeaningAndUnconsumedRemaindersAreExplicit(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected(), text);
        assertEquals(PARTIAL, request.coverage(), request.toString());
        assertTrue(request.hasUnsupported());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Who are you?",
            "Which model is answering this session?",
            "Have you run tests?",
            "Have you inspected files? Don't run or change anything.",
            "What changed? Is it verified?",
            "Is it verified? What changed?",
            "Is it verified?",
            "Is it verified? {",
            "What remains unverified?",
            "Is it verified? What remains unverified?",
            "Please what remains unverified tomorrow?",
            "What is known?",
            "What should happen next?",
            "Explain recursion.",
            "What is 2 + 2?",
            "Write a poem about a handoff.",
            "Translate \"Is the work tested and ready to ship?\".",
            "`Give me a handoff. Is this ready to ship?`",
            "~~~\nGive me a handoff.\n~~~",
            "(Give me a handoff.)",
            "If someone asks for a handoff, explain the term.",
            "Do not give me a handoff."
    })
    void unrelatedAndExistingEvidenceRequestsAreNotDiverted(String text) {
        var request = WorkStatusRequest.parse(text);
        assertFalse(request.selected(), request.toString());
        assertTrue(request.components().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. Read README.md.",
            "Give me a handoff and inspect settings.properties.",
            "Give me a handoff or run tests.",
            "Give me a handoff, check the current files.",
            "Give me a handoff and can you test it?",
            "Give me a handoff. Please execute the command.",
            "Give me a handoff. Edit the configuration.",
            "Give me a handoff. Build the project.",
            "Give me a handoff. Don't run tests. Run tests.",
            "Give me a handoff. Don't run tests and run tests.",
            "Give me a handoff. Don't change anything, but edit README.md.",
            "Give me a handoff and update the checklist.",
            "Read README.md and give me a handoff.",
            "Give me a handoff. Is this tested? Then run tests.",
            "Give me a handoff. Review the files and tell me whether they agree.",
            "Give me a handoff. I want to run tests.",
            "Give me a handoff. Let's inspect the files.",
            "Give me a handoff. (Read README.md.)"
    })
    void freshAcquisitionAndExecutionRetainTheirExistingRoute(String text) {
        assertFalse(WorkStatusRequest.parse(text).selected(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Give me a handoff. {",
            "Give me a handoff. \"unfinished",
            "Give me a handoff.\u0000",
            "Give me a handoff.\u200b",
            "Give me a handoff.\rUnexpected",
            "Is the work verified? {",
            "Is it tested? {",
            "What remains unverified in this conversation? {"
    })
    void recognizedMalformedRequestsRemainOwnedAndInvalid(String text) {
        var request = WorkStatusRequest.parse(text);
        assertTrue(request.selected(), text);
        assertEquals(INVALID, request.coverage());
    }

    @Test
    void boundedRecognitionDoesNotFallbackAfterACueOrScanPastItsBoundary() {
        var longRequest = WorkStatusRequest.parse("Give me a handoff. " + "x".repeat(4096));
        assertTrue(longRequest.selected());
        assertEquals(INVALID, longRequest.coverage());
        assertEquals(longRequest.originalRequest().length(), longRequest.components().getLast().end());
        var tooMany = WorkStatusRequest.parse("Give me a handoff. " + "Briefly. ".repeat(16));
        assertTrue(tooMany.selected());
        assertEquals(INVALID, tooMany.coverage());
        assertEquals(16, tooMany.components().size());
        assertEquals(COVERED, WorkStatusRequest.parse("Give me a handoff. " + "Briefly. ".repeat(15)).coverage());
        assertFalse(WorkStatusRequest.parse("x".repeat(4096) + ". Give me a handoff.").selected());
        assertFalse(WorkStatusRequest.parse("Unknown. ".repeat(16) + "Give me a handoff.").selected());
        var cueAtLimit = WorkStatusRequest.parse("Unknown. ".repeat(15) + "Give me a handoff. More.");
        assertTrue(cueAtLimit.selected());
        assertEquals(INVALID, cueAtLimit.coverage());
        assertTrue(cueAtLimit.roles().contains(ACTIONS));
    }

    @Test
    void constructorClosesStructuralAndImmutabilityInvariants() {
        var mutableRoles = EnumSet.of(ACTIONS);
        var component = new WorkStatusRequest.Component(0, 17, mutableRoles);
        mutableRoles.add(UNSUPPORTED);
        var mutableComponents = new ArrayList<>(List.of(component));
        var request = new WorkStatusRequest("Give me a handoff", true, COVERED, mutableComponents);
        mutableComponents.clear();
        assertEquals(Set.of(ACTIONS), request.roles());
        assertEquals(1, request.components().size());
        assertThrows(UnsupportedOperationException.class, () -> request.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> component.roles().clear());
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest.Component(2, 2, Set.of(ACTIONS)));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest.Component(0, 1, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest("abc", false, PARTIAL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest("abc", true, COVERED,
                List.of(new WorkStatusRequest.Component(0, 3, Set.of(ACTIONS, UNSUPPORTED)))));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest("abc", true, PARTIAL,
                List.of(new WorkStatusRequest.Component(0, 3, Set.of(ACTIONS)))));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest("abc", true, COVERED,
                List.of(new WorkStatusRequest.Component(0, 1, Set.of(ACTIONS)))));
        assertThrows(IllegalArgumentException.class, () -> new WorkStatusRequest("abc", true, INVALID,
                List.of(new WorkStatusRequest.Component(0, 2, Set.of(ACTIONS)),
                        new WorkStatusRequest.Component(1, 3, Set.of(UNSUPPORTED)))));
        assertFalse(WorkStatusRequest.parse(null).selected());
        assertFalse(WorkStatusRequest.parse(" ").selected());
    }
}
