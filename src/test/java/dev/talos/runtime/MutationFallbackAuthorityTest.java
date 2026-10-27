package dev.talos.runtime;

import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.intent.MutationActionEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MutationFallbackAuthorityTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Do not edit README.md yet. Propose the changes first.",
            "Do not edit the README.md yet. Propose the changes first.",
            "Never edit README.md. Explain the proposal.",
            "The ticket says edit README.md. Explain the ticket.",
            "The tutorial says \"rename foo.txt to bar.txt\" in step 3.",
            "The note says 'edit the file'. Explain the note.",
            "If approved, edit README.md.",
            "Perhaps edit README.md.",
            "Context is available. Edit README.md if needed.",
            "Context is available. Edit README.md as an example.",
            "Context is available. Edit the file if needed.",
            "Read edit.md. Describe its purpose.",
            "The design was described as darker and more minimal.",
            "Darker and more minimal.",
            "Do not re-style README.md.",
            "The guide says re-design the page. Explain the guide.",
            "Improve styles.css if needed.",
            "ok just edit the site if needed.",
            "If approved, use talos.delete_path to delete README.md.",
            "The ticket says use talos.delete_path to delete README.md.",
            "Use talos.delete_path to delete README.md if needed.",
            "Use maybe to edit README.md.",
            "The guide says \"Use talos.delete_path to delete README.md\".",
            "Do not edit README.md, but edit notes.md.",
            "Do not edit README.md, then edit notes.md.",
            "Do not edit README.md, but edit README.md.",
            "If approved, do not edit README.md, but edit notes.md.",
            "The ticket says \"do not edit README.md, but edit notes.md\".",
            "The ticket says do not edit README.md but edit notes.md.",
            "Do not edit anything, including README.md, but edit notes.md.",
            "Do not edit README.md and notes.md, but edit changes.md."
    })
    void unsupportedOccurrencesCannotSupplyFallbackMutationAuthority(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertNotEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not edit README.md. Edit notes.md.",
            "Context is available. Edit notes.md. Do not edit README.md.",
            "Context is available. Edit notes.md. The ticket says edit README.md.",
            "İnformation is available. Edit notes.md.",
            "Context is available. Re-style notes.md.",
            "Context is available. Re-design notes.md.",
            "Improve only styles.css. Do not modify index.html or scripts.js.",
            "Context is available. Adjust notes.md.",
            "Context is available. Tweak notes.md.",
            "Context is available. Polish notes.md.",
            "ok just edit the site to look better"
    })
    void affirmativeOccurrenceRemainsAvailable(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
        assertTrue(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }

    @Test
    void acceptedOccurrenceIsUsedForEvidenceInsteadOfEarlierProhibition() {
        String request = "Do not edit README.md. Edit notes.md.";
        var shape = MutationIntent.classifyShape(request);
        assertEquals("Edit notes.md", shape.evidenceSpan());
        var contract = TaskContractResolver.fromUserRequest(request);
        assertTrue(contract.forbiddenTargets().contains("README.md"), contract.toString());
        assertFalse(contract.expectedTargets().contains("README.md"), contract.toString());
        assertTrue(contract.expectedTargets().contains("notes.md"), contract.toString());
    }

    @Test
    void repeatedMarkerEvidenceComesFromTheAcceptedOccurrence() {
        var shape = MutationIntent.classifyShape(
                "The guide says edit the file. Edit the page.");
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision());
        assertEquals("Edit the", shape.evidenceSpan());
    }

    @Test
    void unicodeBeforeTheActionDoesNotShiftItsEvidence() {
        var shape = MutationIntent.classifyShape("İnformation is available. Edit notes.md.");
        assertEquals("Edit notes.md", shape.evidenceSpan());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void repeatedProhibitedOccurrencesDoNotSupplyAuthorityToOneAnother() {
        String request = "Do not edit README.md. ".repeat(256) + "Propose changes to README.md.";
        assertEquals(MutationIntent.Decision.READ_ONLY, MutationIntent.classifyShape(request).decision());
    }

    @Test
    void evidenceCannotExtendBeyondItsSentence() {
        String request = "Edit notes.md. Explain the change.";
        var evidence = MutationActionEvidence.fromRequest(request);
        assertTrue(evidence.permits(0, "Edit notes.md".length()));
        assertFalse(evidence.permits(0, request.length()));
        assertFalse(evidence.permits(-1, 4));
        assertFalse(evidence.permits(0, 2));
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void longAffirmativeTailDoesNotEvadeItsRestriction() {
        String request = "Context is available. " + "edit notes.md and ".repeat(512)
                + "edit README.md if needed.";
        assertEquals(MutationIntent.Decision.READ_ONLY, MutationIntent.classifyShape(request).decision());
    }

    @Test
    void ambiguousSameSentencePostfixOwnershipRemainsRestricted() {
        String request = "Context is available. Edit notes.md and if approved edit README.md.";
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed());
    }

    @Test
    void existingReportedCommaButResetRetainsTheExactAcceptedOccurrence() {
        var shape = MutationIntent.classifyShape(
                "The ticket says do not edit README.md, but edit notes.md.");
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision());
        assertEquals("edit notes.md", shape.evidenceSpan());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Context is available. Can you make it?",
            "Context is available. Can you make it",
            "Context is available. Can you make it.",
            "Context is available. Can you make it!",
            "Context is available. Can you make it\tplease?",
            "I want to create a modern BMI calculator website to use! Can you make it?",
            "Please keep the look the same and just make the button work.",
            "Please keep the look the same and simply make the button work.",
            "My BMI page is almost there, but when I press the button nothing happens. "
                    + "Please keep the look the same and just make the button work."
    })
    void completeMarkerTokensAndDirectModifiersRetainAffirmativeIntent(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Context is available. Can you make it if needed?",
            "Context is available. Can you make it as an example?",
            "The guide says can you make it?",
            "The guide says \"Can you make it?\"",
            "Context is available. Make itemized.",
            "Context is available. Make it.md.",
            "Please keep the look the same and just make the button work if needed.",
            "The guide says keep the look the same and just make the button work.",
            "The guide says keep the look the same and simply make the button work.",
            "Do not edit files and just make the button work.",
            "If approved, keep the look the same and just make the button work."
    })
    void tokenCompletionCannotEraseScopeOrMatchInsideAnotherToken(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertNotEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(), shape.toString());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void canonicalWorkRejectionPrecedesToolPrefixCompatibility() {
        String request = "Context is available. " + " ".repeat(100_000)
                + "edit notes.md and ".repeat(256) + "edit README.md if needed.";
        assertEquals(MutationIntent.Decision.READ_ONLY, MutationIntent.classifyShape(request).decision());
    }

    @Test
    void terminalModalEvidenceDoesNotComeFromAnEarlierDesire() {
        var shape = MutationIntent.classifyShape(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        assertEquals("make it", shape.evidenceSpan());
        assertNotEquals(MutationIntent.Decision.EXECUTE_MUTATION,
                MutationIntent.classifyShape("I want to create a modern BMI calculator website.").decision());
    }
}
