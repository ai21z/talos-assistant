package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replacement request binds its real operands or none at all (MICA invariants I2 and I3, P4). A descriptor is not an
 * operand, an unquoted operand ends at its sentence or at a boundary where a new clause starts, and an operand whose
 * boundary or meaning is undecidable binds nothing rather than a guess.
 */
class ReplacementOperandBindingTest {

    @Test
    void aDescriptorBeforeFromIsNotTheOldTextAndTheNewTextKeepsItsWords() {
        ReplacementExpectation replacement = only(
                "In src/App.java, change only the returned greeting from hello to hello atlas. Preserve formatting.");

        assertEquals("src/App.java", replacement.targetPath());
        assertEquals("hello", replacement.oldText());
        assertEquals("hello atlas", replacement.newText());
    }

    @Test
    void fromWithoutADescriptorOrAfterTheExactTextDescriptorStillBinds() {
        ReplacementExpectation bare = only("In a.md, change from hello to bye.");
        ReplacementExpectation exact = only("In a.md, change the exact text from hello to bye.");

        assertEquals("hello", bare.oldText());
        assertEquals("bye", bare.newText());
        assertEquals("hello", exact.oldText());
        assertEquals("bye", exact.newText());
    }

    @Test
    void wordsBeforeFromThatMayBeTheTextItselfBindNothing() {
        assertNoReplacement("In index.html, change Made from Wood to Steel.");
        assertNoReplacement("In a.md, change the label 'Made from scratch' to 'Homemade'.");
    }

    @Test
    void anUnquotedOperandEndsAtItsSentenceNotInsideADecimal() {
        ReplacementExpectation replacement = only("In config.txt, change 1.5 to 2.0. Keep the rest.");

        assertEquals("1.5", replacement.oldText());
        assertEquals("2.0", replacement.newText());
    }

    @Test
    void aScopedReplaceKeepsAMultiWordNewText() {
        ReplacementExpectation replacement = only("In notes.md, replace draft with final copy.");

        assertEquals("draft", replacement.oldText());
        assertEquals("final copy", replacement.newText());
    }

    @Test
    void aClauseAfterTheNewTextIsNotPartOfIt() {
        ReplacementExpectation comma = only("In notes.md, change draft to final, then stop.");
        ReplacementExpectation connector = only("In notes.md, change draft to final and keep the rest.");
        ReplacementExpectation imperative = only("In a.md, change hello to Hi Atlas keep everything else.");

        assertEquals("final", comma.newText());
        assertEquals("final", connector.newText());
        assertEquals("Hi Atlas", imperative.newText());
    }

    @Test
    void aCommaOrConjunctionInsideTheNewTextBindsNothing() {
        assertNoReplacement("In index.html, change Welcome to Hello, World.");
        assertNoReplacement("In index.html, change Title to Tom and Jerry.");
    }

    @Test
    void aDescriptionOrAnUndelimitedClauseBindsNothing() {
        assertNoReplacement("In notes.md, change draft to final for release.");
        assertNoReplacement("In notes.md, change the status to final.");
        assertNoReplacement("In notes.md, change draft to the final copy.");
    }

    @Test
    void operandsDelimitedByWithAndTheTargetKeepTheirWords() {
        ReplacementExpectation signIn = only("Replace Sign in with Log in in index.html.");
        ReplacementExpectation plan = only("Replace Plan A with Plan B in index.html.");

        assertEquals("Sign in", signIn.oldText());
        assertEquals("Log in", signIn.newText());
        assertEquals("Plan A", plan.oldText());
        assertEquals("Plan B", plan.newText());
    }

    @Test
    void aRenameBindsNothingBecauseTextCannotDecideItsCompleteness() {
        // Other identifiers and comments can contain the old name (form.reset(), getUserById), so a text check
        // would fail a correct rename. The rename stays a checks pass.
        assertNoReplacement(
                "Rename reset to resetAll in script.js only. scripts.js is a different file and must remain unchanged.");
    }

    @Test
    void singleWordOperandsAreUnchanged() {
        ReplacementExpectation replacement = only("In notes.md, change draft to final. If an exact edit fails, recover.");

        assertEquals("draft", replacement.oldText());
        assertEquals("final", replacement.newText());
    }

    private static ReplacementExpectation only(String request) {
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(TaskContractResolver.fromUserRequest(request));
        List<ReplacementExpectation> replacements = expectations.stream()
                .filter(ReplacementExpectation.class::isInstance)
                .map(ReplacementExpectation.class::cast)
                .toList();
        assertEquals(1, replacements.size(), request + " -> " + expectations);
        return replacements.getFirst();
    }

    private static void assertNoReplacement(String request) {
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(TaskContractResolver.fromUserRequest(request));
        assertTrue(expectations.stream().noneMatch(ReplacementExpectation.class::isInstance),
                request + " -> " + expectations);
    }
}
