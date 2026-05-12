package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationIntentTest {

    private static final String T61_B_RETRY_PROMPT =
            "This is a retry after the denied attempt. Edit README.md now using talos.write_file. "
                    + "The complete file must contain exactly two lines: first line T61-B exact README; "
                    + "second line Line two; no other characters.";

    @Test
    void overwriteRewriteReplaceAndNaturalCreationPhrasingAreExplicitMutationIntent() {
        for (String input : java.util.List.of(
                "Overwrite index.html with a corrected complete version.",
                "Overwrite these three files to make a working BMI calculator: index.html, styles.css, scripts.js.",
                "Replace index.html with a corrected complete version.",
                "Rewrite scripts.js so the button works.",
                "Can you make me a simple BMI calculator webpage here?",
                "I am not technical, I just want a page I can open and use. Can you make it?",
                "Can you fix the files in this folder for me?",
                "Move public.txt to archive/public.txt.",
                "Copy docs/plan.md to docs/archive/plan.md.",
                "Rename old.txt to new.txt.",
                "Mkdir docs/reports.",
                "make me a folder called ideas",
                "make a folder called docs",
                "create a directory named reports")) {
            assertTrue(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void repairIsExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest("Repair this website."));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Can you repair index.html?"));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Please repair the broken app."));
    }

    @Test
    void preambleBeforeExplicitFileEditIsMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(T61_B_RETRY_PROMPT));
        assertTrue(MutationIntent.classificationReason(T61_B_RETRY_PROMPT)
                .contains("explicit-mutation-verb-with-file-target"));
    }

    @Test
    void retryStatusReviewAndAdvisoryEditPromptsStayReadOnly() {
        for (String input : java.util.List.of(
                "Review README.md",
                "What happened after the denied attempt?",
                "Should I edit README.md?",
                "Can you explain how to edit README.md?",
                "Show me how to update README.md.")) {
            assertFalse(MutationIntent.looksExplicitMutationRequest(input), input);
        }
    }

    @Test
    void advisoryRepairQuestionStaysReadOnly() {
        assertFalse(MutationIntent.looksExplicitMutationRequest("What repair would you make?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("Can you explain the repair?"));
    }

    @Test
    void priorChangeStatusQuestionsAreNotMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest("did you make the changes?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("did you update the files?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("what did you change?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest("why did nothing change?"));
    }

    @Test
    void readOnlyNegationStillWinsForRepair() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Repair this file but do not change anything."));
    }

    @Test
    void namedFileScopedNegationDoesNotCancelMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Fix only styles.css. Do not change index.html or scripts.js."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Edit only index.html; don't touch styles.css."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md. keep it tight. don't touch private files."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md. do not touch protected files."));
    }

    @Test
    void globalReadOnlyNegationStillCancelsMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not change anything. Just inspect."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Summarize long-notes.txt into ideas/summary.md, but don't touch files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Diagnose this, do not change files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Show me how to make one, do not edit files."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "I am only chatting, please don't inspect my files. What can you do for me?"));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Can you explain how to build a BMI calculator?"));
    }

    @Test
    void formattingNegationDoesNotCancelExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Use talos.write_file to overwrite index.html. "
                        + "Set the content argument to the exact five letters AFTER. "
                        + "Do not use angle brackets. Do not use placeholders. "
                        + "The entire file should be AFTER."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Use write_file to overwrite index.html. Do not use placeholders."));
        assertTrue(MutationIntent.looksExplicitMutationRequest(
                "Overwrite index.html. Do not use angle brackets."));

        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not edit files. Explain what you would change."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "I am only chatting, please don't inspect my files. What can you do for me?"));
    }
}
