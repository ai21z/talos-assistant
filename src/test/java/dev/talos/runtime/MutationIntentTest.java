package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationIntentTest {

    @Test
    void repairIsExplicitMutationIntent() {
        assertTrue(MutationIntent.looksExplicitMutationRequest("Repair this website."));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Can you repair index.html?"));
        assertTrue(MutationIntent.looksExplicitMutationRequest("Please repair the broken app."));
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
    }

    @Test
    void globalReadOnlyNegationStillCancelsMutationIntent() {
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Do not change anything. Just inspect."));
        assertFalse(MutationIntent.looksExplicitMutationRequest(
                "Diagnose this, do not change files."));
    }
}
