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

    @Test void deniedMutationPreservesTargetsAndRendersBlockedReason() {
        ActiveTaskContext context = ActiveTaskContext.deniedMutation(
                6,
                "trace-denied",
                List.of("src/App.java"),
                "protected path");

        assertEquals(ActiveTaskContext.State.ACTIVE, context.state());
        assertEquals(ActiveTaskContext.Kind.DENIED_MUTATION, context.kind());
        assertEquals(ActiveTaskContext.Operation.APPLY_EDIT, context.operation());
        assertEquals("NO_FILES_CHANGED", context.previousOutcomeStatus());
        assertEquals(List.of("src/App.java"), context.targets());
        assertTrue(context.renderForPlan().contains("protected path"));
    }

    @Test void stateVariantsCopyContextFieldsAndSetReason() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                4,
                "trace-state",
                List.of("README.md"),
                "update docs");

        ActiveTaskContext suppressed = context.suppressed("answer only");
        ActiveTaskContext cleared = context.cleared("new task");
        ActiveTaskContext expired = context.expired("too old");

        assertStateVariantCopiesContext(context, suppressed, ActiveTaskContext.State.SUPPRESSED, "answer only");
        assertStateVariantCopiesContext(context, cleared, ActiveTaskContext.State.CLEARED, "new task");
        assertStateVariantCopiesContext(context, expired, ActiveTaskContext.State.EXPIRED, "too old");
    }

    @Test void constructorNormalizesNullsDeduplicatesAndCopiesLists() {
        List<String> targets = new java.util.ArrayList<>(List.of(
                "a.txt", "a.txt", "b.txt", "c.txt", "d.txt", "e.txt", "f.txt"));
        ActiveTaskContext context = new ActiveTaskContext(
                99,
                null,
                null,
                1,
                null,
                2,
                3,
                targets,
                null,
                null,
                null,
                null,
                null,
                null);

        targets.set(0, "changed.txt");

        assertEquals(ActiveTaskContext.SCHEMA_VERSION, context.schemaVersion());
        assertEquals(ActiveTaskContext.State.NONE, context.state());
        assertEquals(ActiveTaskContext.Kind.NONE, context.kind());
        assertEquals("", context.sourceTraceId());
        assertEquals(List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt"), context.targets());
        assertEquals(ActiveTaskContext.Operation.NONE, context.operation());
        assertEquals("", context.proposalSummary());
        assertEquals("", context.previousOutcomeStatus());
        assertEquals(List.of(), context.verifierFindings());
        assertEquals("", context.blockedReason());
        assertEquals("", context.suppressionReason());
        assertThrows(UnsupportedOperationException.class, () -> context.targets().add("new.txt"));
    }

    @Test void factoryNormalizesNullListsToEmpty() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(1, null, null, null);

        assertEquals("", context.sourceTraceId());
        assertEquals(List.of(), context.targets());
        assertEquals("", context.proposalSummary());
    }

    @Test void verifierFindingsAreTruncatedToMaxFindingChars() {
        ActiveTaskContext context = ActiveTaskContext.verifierFindings(
                9,
                "trace-verify",
                List.of("index.html"),
                List.of("x".repeat(ActiveTaskContext.MAX_FINDINGS_CHARS + 50)),
                "FAILED");

        assertEquals(ActiveTaskContext.MAX_FINDINGS_CHARS, context.verifierFindings().getFirst().length());
    }

    @Test void activeAtReturnsFalseForNonActiveStates() {
        ActiveTaskContext active = ActiveTaskContext.proposedChanges(
                4,
                "trace-active",
                List.of("README.md"),
                "update docs");

        assertFalse(ActiveTaskContext.none().activeAt(4));
        assertFalse(active.suppressed("answer only").activeAt(4));
        assertFalse(active.cleared("new task").activeAt(4));
        assertFalse(active.expired("too old").activeAt(4));
    }

    private static void assertStateVariantCopiesContext(
            ActiveTaskContext expectedBase,
            ActiveTaskContext actual,
            ActiveTaskContext.State expectedState,
            String expectedReason) {
        assertEquals(expectedState, actual.state());
        assertEquals(expectedBase.kind(), actual.kind());
        assertEquals(expectedBase.targets(), actual.targets());
        assertEquals(expectedBase.operation(), actual.operation());
        assertEquals(expectedBase.proposalSummary(), actual.proposalSummary());
        assertEquals(expectedReason, actual.suppressionReason());
    }
}
