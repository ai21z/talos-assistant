package dev.talos.runtime.policy;

import dev.talos.runtime.*;
import dev.talos.runtime.intent.HistoricalAttributionRequest;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.safety.ContentRestrictions;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed audit facts are not live model or test-execution evidence. */
class HistoricalAttributionAnswerPolicyTest {
    @TempDir Path workspace;
    private static final String REQUEST = "Did you edit README.md and edit config.json and run tests?";

    @Test void everyRequestedResultHasAnIndependentScopedOutcome() {
        var memory = new SessionMemory();
        record(memory, workspace, new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", List.of("README.md"),
                false, "post-commit error", ToolMutationEffects.applied("README.md"),
                TurnRecord.ToolCallDisposition.EXECUTED), ContentRestrictions.UNRESTRICTED);
        String answer = render(memory, workspace);
        assertTrue(answer.contains("File edit: README.md. A Talos edit or write with a matching applied effect"), answer);
        assertTrue(answer.contains("File edit: config.json. A Talos edit is not established"), answer);
        assertTrue(answer.contains("Test execution: not established"), answer);
        assertTrue(answer.contains("does not establish the final content"), answer);
    }

    @Test void otherWorkspaceAndNarrativeClaimsCannotProveCurrentAttribution() {
        var memory = new SessionMemory();
        memory.update("Earlier work", "README.md was edited and all tests passed.");
        record(memory, workspace.resolve("other"), edit(ToolMutationEffects.applied("README.md")),
                ContentRestrictions.UNRESTRICTED);
        assertTrue(render(memory, workspace).contains("File edit: README.md. A Talos edit is not established"));
    }

    @Test void readEvidenceCannotProveAnEdit() {
        var memory = new SessionMemory();
        record(memory, workspace, new TurnRecord.ToolCallSummary("talos.read_file", "README.md", List.of("README.md"),
                true, "", ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.EXECUTED),
                ContentRestrictions.UNRESTRICTED);
        String answer = render(memory, workspace);
        assertTrue(answer.contains("A current read or a reported external change does not establish Talos authorship"));
        assertFalse(answer.contains("README was not modified"));
    }

    @Test void laterPositiveEvidenceDoesNotEraseRetainedUncertainty() {
        var memory = new SessionMemory();
        record(memory, workspace, edit(ToolMutationEffects.uncertain("README.md")), ContentRestrictions.UNRESTRICTED);
        record(memory, workspace, edit(ToolMutationEffects.applied("README.md")), ContentRestrictions.UNRESTRICTED);
        String answer = render(memory, workspace);
        assertTrue(answer.contains("matching applied effect"));
        assertTrue(answer.contains("An uncertain effect is also recorded for this target"));
    }

    @Test void executedCommandAndStaticPassDoNotProveTests() {
        var memory = new SessionMemory();
        record(memory, workspace, new TurnRecord.ToolCallSummary("talos.run_command", "", List.of(),
                true, "", ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.EXECUTED),
                ContentRestrictions.UNRESTRICTED);
        String answer = render(memory, workspace);
        assertTrue(answer.contains("1 command calls reached tool execution"));
        assertTrue(answer.contains("does not establish subprocess launch, executed test cases or passing tests"));
        assertFalse(answer.contains("Tests passed"));
    }

    @Test void privateProofCannotRestoreNamedAttribution() {
        var memory = new SessionMemory();
        record(memory, workspace, edit(ToolMutationEffects.applied("README.md")),
                new ContentRestrictions(false, false, false, false));
        assertTrue(render(memory, workspace).contains("File edit: README.md. A Talos edit is not established"));
    }

    @Test void missingMemoryAndScopeAreReportedWithoutInventingACompleteAudit() {
        String answer = render(null, null);
        assertTrue(answer.contains("Retained runtime memory is unavailable"));
        assertTrue(answer.contains("Current workspace scope is unavailable"));
        assertTrue(answer.contains("not a complete session audit"));
        assertTrue(answer.contains("Test execution: not established"));
    }

    @Test void evictedEditCannotRemainARecordedCurrentProof() {
        var memory = new SessionMemory();
        record(memory, workspace, edit(ToolMutationEffects.applied("README.md")), ContentRestrictions.UNRESTRICTED);
        for (int index = 0; index < HistoricalAttributionEvidence.MAX_TURNS; index++) {
            retain(memory, new TurnResult(new Result.Ok("Constructed answer"), index + 2));
        }
        String answer = render(memory, workspace);
        assertTrue(answer.contains("File edit: README.md. A Talos edit is not established"));
        assertTrue(answer.contains("1 evicted turns"));
        assertTrue(answer.contains("turns without workspace binding"));
    }

    private static TurnRecord.ToolCallSummary edit(ToolMutationEffects effects) {
        return new TurnRecord.ToolCallSummary("talos.edit_file", "README.md", List.of("README.md"), false,
                "constructed failure", effects, TurnRecord.ToolCallDisposition.EXECUTED);
    }

    private static String render(SessionMemory memory, Path workspace) {
        return HistoricalAttributionAnswerPolicy.render(HistoricalAttributionRequest.parse(REQUEST).orElseThrow(),
                memory, workspace);
    }

    private static void record(SessionMemory memory, Path workspace, TurnRecord.ToolCallSummary call,
                               ContentRestrictions restrictions) {
        var trace = LocalTurnTrace.builder("attribution-render", "offline", 1, "2026-10-01T00:00:00Z")
                .workspaceHash(JsonSessionStore.sessionIdFor(workspace))
                .verification("PASSED", "Constructed static pass", List.of()).build();
        retain(memory, new TurnResult(new Result.Ok("Constructed prose says all tests passed"), null,
                1, Duration.ZERO, new TurnAudit(List.of(call), 0, 0, 0, TurnPolicyTrace.empty(), trace,
                restrictions, List.of())));
    }

    private static void retain(SessionMemory memory, TurnResult result) {
        new MemoryUpdateListener(new dev.talos.core.context.ConversationManager(memory,
                new dev.talos.core.context.TokenBudget()), null, memory)
                .onTurnComplete(result, "Constructed request");
    }
}
