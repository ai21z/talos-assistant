package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTaskContextUpdateListenerTest {

    @Test
    void completedTurnUpdatesSessionMemoryActiveContextAndArtifactGoal() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        TurnResult result = new TurnResult(
                new Result.Ok("I would add setup steps to README.md."),
                null,
                3,
                Duration.ofMillis(25),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        new TurnPolicyTrace(
                                "READ_ONLY_QA",
                                false,
                                false,
                                List.of("README.md"),
                                List.of(),
                                "INSPECT",
                                "INSPECT",
                                List.of(),
                                List.of(),
                                List.of()),
                        LocalTurnTrace.builder("trace-listener", "session", 3, "2026-05-01T00:00:00Z")
                                .taskContract(new LocalTurnTrace.TaskContractSummary(
                                        "READ_ONLY_QA",
                                        false,
                                        false,
                                        false,
                                        List.of("README.md"),
                                        List.of()))
                                .outcome("ADVISORY_ONLY", "NOT_RUN", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY")
                                .build()));

        listener.onTurnComplete(result, "Propose README.md changes without editing.");

        assertEquals(ActiveTaskContext.State.ACTIVE, memory.activeTaskContext().state());
        assertEquals(ActiveTaskContext.Kind.PROPOSED_CHANGES, memory.activeTaskContext().kind());
        assertEquals(List.of("README.md"), memory.activeTaskContext().targets());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, memory.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.README, memory.artifactGoal().artifactKind());
    }

    @Test
    void evidenceIncompleteProposalDoesNotBecomeActiveContext() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        TurnResult result = new TurnResult(
                new Result.Ok(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX
                        + "\n\nI would add setup steps to README.md."),
                null,
                3,
                Duration.ofMillis(25),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        new TurnPolicyTrace(
                                "READ_ONLY_QA",
                                false,
                                false,
                                List.of("README.md"),
                                List.of(),
                                "INSPECT",
                                "INSPECT",
                                List.of(),
                                List.of(),
                                List.of()),
                        LocalTurnTrace.builder("trace-listener", "session", 3, "2026-05-01T00:00:00Z")
                                .taskContract(new LocalTurnTrace.TaskContractSummary(
                                        "READ_ONLY_QA",
                                        false,
                                        false,
                                        false,
                                        List.of("README.md"),
                                        List.of()))
                                .outcome("ADVISORY_ONLY", "NOT_RUN", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY")
                                .warning("MISSING_EVIDENCE",
                                        "Required workspace evidence was not gathered in this turn.")
                                .build()));

        listener.onTurnComplete(result, "Propose README.md changes without editing.");

        assertEquals(ActiveTaskContext.State.NONE, memory.activeTaskContext().state());
        assertEquals(ArtifactGoal.Source.NONE, memory.artifactGoal().source());
    }

    @Test
    void mutatingTurnUpdatesRuntimeChangeSummaryContext() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        TurnResult result = new TurnResult(
                new Result.Ok("[Task incomplete: Static verification failed]"),
                null,
                18,
                Duration.ofMillis(25),
                new TurnAudit(
                        List.of(
                                new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true),
                                new TurnRecord.ToolCallSummary("talos.write_file", "styles.css", true),
                                new TurnRecord.ToolCallSummary("talos.write_file", "script.js", true)),
                        0,
                        0,
                        0,
                        new TurnPolicyTrace(
                                "FILE_CREATE",
                                true,
                                true,
                                List.of("index.html", "styles.css", "scripts.js"),
                                List.of(),
                                "APPLY",
                                "VERIFY",
                                List.of(),
                                List.of(),
                                List.of()),
                        LocalTurnTrace.builder("trace-bmi", "session", 18, "2026-05-02T00:00:00Z")
                                .taskContract(new LocalTurnTrace.TaskContractSummary(
                                        "FILE_CREATE",
                                        true,
                                        true,
                                        true,
                                        List.of("index.html", "styles.css", "scripts.js"),
                                        List.of()))
                                .verification("FAILED", "Static verification failed", List.of(
                                        "scripts.js: expected target was not successfully mutated.",
                                        "Calculator/form task is missing a result output element."))
                                .outcome("MUTATION_APPLIED", "FAILED", "NONE", "SUCCEEDED", "TASK_INCOMPLETE")
                                .build()));

        listener.onTurnComplete(result, "Create a BMI calculator with index.html, styles.css, and scripts.js.");

        ChangeSummaryContext context = memory.changeSummaryContext();
        assertTrue(context.hasRecordedChanges());
        assertEquals(List.of("index.html", "styles.css", "script.js"),
                context.changedFiles().stream().map(ChangeSummaryContext.FileChange::path).toList());
        assertEquals(List.of("scripts.js"), context.unresolvedTargets());
        assertEquals("FAILED", context.verificationStatus());
        assertTrue(context.verifierFindings().contains(
                "scripts.js: expected target was not successfully mutated."));
    }

    @Test
    void noToolTurnDoesNotOverwriteExistingChangeSummaryContext() {
        SessionMemory memory = new SessionMemory();
        memory.setChangeSummaryContext(new ChangeSummaryContext(
                ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange("script.js", "talos.edit_file", 16, "trace-edit")),
                List.of("styles.css"),
                "FAILED",
                "TASK_INCOMPLETE",
                List.of("styles.css: expected target was not successfully mutated.")));
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        TurnResult result = new TurnResult(
                new Result.Ok("No. The previous verified outcome says the task is not complete."),
                null,
                20,
                Duration.ofMillis(5),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        TurnPolicyTrace.empty(),
                        LocalTurnTrace.builder("trace-summary", "session", 20, "2026-05-02T00:00:00Z")
                                .outcome("NO_TOOL_RESPONSE", "NOT_RUN", "NONE", "UNKNOWN", "TURN_RECORDED")
                                .build()));

        listener.onTurnComplete(result, "What files changed during this audit?");

        ChangeSummaryContext context = memory.changeSummaryContext();
        assertEquals(List.of("script.js"),
                context.changedFiles().stream().map(ChangeSummaryContext.FileChange::path).toList());
        assertEquals(List.of("styles.css"), context.unresolvedTargets());
        assertEquals("FAILED", context.verificationStatus());
        assertEquals("TASK_INCOMPLETE", context.completionStatus());
    }

    @Test
    void failedExactVerificationHistorySurvivesLaterUnrelatedVerifiedChange() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        listener.onTurnComplete(mutatingTurn(
                21,
                "trace-readme-failed",
                List.of("README.md"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "README.md", true)),
                "FAILED",
                "TASK_INCOMPLETE",
                List.of("README.md: exact content mismatch; expected 27 bytes/2 lines, observed 28 bytes/3 lines.")),
                "Edit README.md with exactly two lines.");
        listener.onTurnComplete(mutatingTurn(
                22,
                "trace-index-passed",
                List.of("index.html"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true)),
                "PASSED",
                "COMPLETED_VERIFIED",
                List.of()),
                "Update index.html title.");

        String rendered = memory.changeSummaryContext().renderForChangeSummaryQuestion();

        assertTrue(rendered.contains("README.md"), rendered);
        assertTrue(rendered.contains("Unresolved verification failures"), rendered);
        assertTrue(rendered.contains("exact content mismatch"), rendered);
        assertTrue(rendered.contains("not verified complete"), rendered);
        assertFalse(rendered.contains("Verification status: verified complete"), rendered);
    }

    @Test
    void failedStaticWebVerificationHistorySurvivesLaterUnrelatedVerifiedChange() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        listener.onTurnComplete(mutatingTurn(
                23,
                "trace-static-failed",
                List.of("index.html", "styles.css", "scripts.js"),
                List.of(
                        new TurnRecord.ToolCallSummary("talos.write_file", "index.html", true),
                        new TurnRecord.ToolCallSummary("talos.write_file", "styles.css", true),
                        new TurnRecord.ToolCallSummary("talos.write_file", "script.js", true)),
                "FAILED",
                "TASK_INCOMPLETE",
                List.of(
                        "scripts.js: expected target was not successfully mutated.",
                        "script.js was mutated but does not satisfy expected target scripts.js.")),
                "Create static BMI website.");
        listener.onTurnComplete(mutatingTurn(
                24,
                "trace-readme-passed",
                List.of("README.md"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "README.md", true)),
                "PASSED",
                "COMPLETED_VERIFIED",
                List.of()),
                "Update README.md.");

        String rendered = memory.changeSummaryContext().renderForChangeSummaryQuestion();

        assertTrue(rendered.contains("index.html"), rendered);
        assertTrue(rendered.contains("styles.css"), rendered);
        assertTrue(rendered.contains("script.js"), rendered);
        assertTrue(rendered.contains("scripts.js: expected target was not successfully mutated"), rendered);
        assertTrue(rendered.contains("Unresolved verification failures"), rendered);
        assertTrue(rendered.contains("not verified complete"), rendered);
    }

    @Test
    void failedVerificationHistoryIsResolvedByLaterVerifiedChangeToSameTarget() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        listener.onTurnComplete(mutatingTurn(
                25,
                "trace-readme-failed",
                List.of("README.md"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "README.md", true)),
                "FAILED",
                "TASK_INCOMPLETE",
                List.of("README.md: exact content mismatch.")),
                "Edit README.md with exactly two lines.");
        listener.onTurnComplete(mutatingTurn(
                26,
                "trace-readme-passed",
                List.of("README.md"),
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "README.md", true)),
                "PASSED",
                "COMPLETED_VERIFIED",
                List.of()),
                "Repair README.md exact content.");

        String rendered = memory.changeSummaryContext().renderForChangeSummaryQuestion();

        assertFalse(rendered.contains("Unresolved verification failures"), rendered);
        assertFalse(rendered.contains("exact content mismatch"), rendered);
        assertTrue(rendered.contains("Verification status: verified complete"), rendered);
    }

    @Test
    void nullMemoryIsIgnored() {
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(null);

        assertDoesNotThrow(() -> listener.onTurnComplete(null, "anything"));
    }

    private static TurnResult mutatingTurn(
            int turnNumber,
            String traceId,
            List<String> expectedTargets,
            List<TurnRecord.ToolCallSummary> toolCalls,
            String verificationStatus,
            String completionStatus,
            List<String> verifierFindings
    ) {
        return new TurnResult(
                new Result.Ok("runtime summary"),
                null,
                turnNumber,
                Duration.ofMillis(25),
                new TurnAudit(
                        toolCalls,
                        0,
                        0,
                        0,
                        new TurnPolicyTrace(
                                "FILE_CREATE",
                                true,
                                true,
                                expectedTargets,
                                List.of(),
                                "APPLY",
                                "VERIFY",
                                List.of(),
                                List.of(),
                                List.of()),
                        LocalTurnTrace.builder(traceId, "session", turnNumber, "2026-05-02T00:00:00Z")
                                .taskContract(new LocalTurnTrace.TaskContractSummary(
                                        "FILE_CREATE",
                                        true,
                                        true,
                                        true,
                                        expectedTargets,
                                        List.of()))
                                .verification(verificationStatus, "Static verification " + verificationStatus,
                                        verifierFindings)
                                .outcome("MUTATION_APPLIED", verificationStatus, "NONE", "SUCCEEDED",
                                        completionStatus)
                                .build()));
    }
}
