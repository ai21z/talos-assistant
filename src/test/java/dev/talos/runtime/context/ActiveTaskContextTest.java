package dev.talos.runtime.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTaskContextTest {

    @Test void noneHasNoPromptContext() {
        ActiveTaskContext context = ActiveTaskContext.none();

        assertEquals(ActiveTaskContext.State.NONE, context.state());
        assertFalse(context.hasPromptContext());
        assertEquals(ActiveTaskContext.NONE_OR_NOT_DERIVED, context.renderForPlan());
    }

    @Test void proposedChangesAreBoundedAndExpireAfterThreeTurns() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                4,
                "trace-abc",
                List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt", "f.txt"),
                "x".repeat(700));

        assertEquals(ActiveTaskContext.State.ACTIVE, context.state());
        assertEquals(ActiveTaskContext.Kind.PROPOSED_CHANGES, context.kind());
        assertEquals(ActiveTaskContext.Operation.APPLY_EDIT, context.operation());
        assertEquals(5, context.targets().size());
        assertEquals(600, context.proposalSummary().length());
        assertEquals(7, context.expiresAfterTurnNumber());
        assertTrue(context.activeAt(7));
        assertFalse(context.activeAt(8));
    }

    @Test void renderForPlanIsCompactAndRedacted() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                2,
                "trace-secret",
                List.of(".env"),
                "set sk-live-1234567890 and API_KEY=secret before running");

        String rendered = context.renderForPlan();

        assertTrue(rendered.contains("ACTIVE"));
        assertTrue(rendered.contains("PROPOSED_CHANGES"));
        assertTrue(rendered.contains(".env"));
        assertTrue(rendered.length() <= ActiveTaskContext.PROMPT_RENDER_CHAR_CAP);
        assertFalse(rendered.contains("sk-live-1234567890"));
        assertFalse(rendered.contains("API_KEY=secret"));
    }

    @Test void verifierFindingsAreBounded() {
        ActiveTaskContext context = ActiveTaskContext.verifierFindings(
                9,
                "trace-verify",
                List.of("index.html"),
                List.of("one", "two", "three", "four", "five", "six"),
                "FAILED");

        assertEquals(5, context.verifierFindings().size());
        assertEquals("FAILED", context.previousOutcomeStatus());
        assertTrue(context.renderForPlan().contains("VERIFIER_FINDINGS"));
    }
}
