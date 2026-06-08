package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptSourceEvidenceRepairDecisionTest {
    @TempDir
    Path workspace;

    @Test
    void ownsSourceEvidenceRepairDecisionMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptSourceEvidenceRepairDecision.java"));

        assertTrue(source.contains("SourceEvidenceExactRepairPlanner.nextPlan("), source);
        assertTrue(source.contains("sourceEvidenceExactRepairPromptedKeys.add"), source);
        assertTrue(source.contains("PendingActionObligation.expectedTargets"), source);
        assertTrue(source.contains("source-evidence exact compact repair"), source);
    }

    @Test
    void noSourceEvidenceRepairPlanReturnsEmptyDecision() {
        LoopState state = state("Update README.md.", List.of(new LlmClient.StreamResult("", List.of())));

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, "Update README.md.");

        assertTrue(decision.isEmpty());
    }

    @Test
    void sourceEvidenceRepairPlanRaisesObligationAndExecutesCompactRetry() {
        ChatMessage.NativeToolCall repairCall = new ChatMessage.NativeToolCall(
                "repair-1",
                "talos.write_file",
                Map.of("path", "office-summary.md", "content", "Board brief marker: ORBITAL-DECK-71."));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(repairCall))),
                16_384);
        String request = sourceEvidenceRequest();
        LoopState state = state(request, recorded.client());
        addSourceReadbacks(state);
        state.toolOutcomes.add(failedSourceEvidenceWrite("office-summary.md"));

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertTrue(state.hasPendingActionObligation());
        assertEquals(1, state.sourceEvidenceExactRepairPromptedKeys.size());
        assertTrue(state.sourceEvidenceExactRepairPromptedKeys.iterator().next()
                .startsWith("office-summary.md->"), state.sourceEvidenceExactRepairPromptedKeys.toString());
        assertEquals(List.of(repairCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[SourceEvidenceExactRepair] Target: office-summary.md"), prompt);
        assertTrue(prompt.contains("Board brief marker: ORBITAL-DECK-71."), prompt);
    }

    @Test
    void sourceEvidenceWriteBeforeReadRepromptsForMissingSourceReadFirst() {
        ChatMessage.NativeToolCall readCall = new ChatMessage.NativeToolCall(
                "read-1",
                "talos.read_file",
                Map.of("path", "problem.md", "max_lines", 200));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(readCall))),
                16_384);
        String request = "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        LoopState state = state(request, recorded.client());
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("dijkstra.py"));
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("test_dijkstra.py"));

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertFalse(state.hasPendingActionObligation(), "source-read repair must not raise a mutation obligation");
        assertEquals(List.of(readCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[SourceEvidenceReadBeforeWriteRepair]"), prompt);
        assertTrue(prompt.contains("Missing source target(s): problem.md"), prompt);
        assertTrue(prompt.contains("Call talos.read_file for the missing source target(s) first"), prompt);
        assertFalse(prompt.contains("[Expected target progress]"), prompt);
    }

    private LoopState state(String request, List<LlmClient.StreamResult> responses) {
        return state(request, ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384).client());
    }

    private LoopState state(String request, LlmClient llm) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
    }

    private static String sourceEvidenceRequest() {
        return "Create office-summary.md summarizing board-brief.md, client-notes.md, and revenue.csv. "
                + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.";
    }

    private static void addSourceReadbacks(LoopState state) {
        state.toolOutcomes.add(readOutcome("board-brief.md"));
        state.toolOutcomes.add(readOutcome("client-notes.md"));
        state.toolOutcomes.add(readOutcome("revenue.csv"));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=board-brief.md;",
                "1 | Board brief marker: ORBITAL-DECK-71.");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=client-notes.md;",
                "1 | Client note marker: NEON-RESPONSE-44.");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=revenue.csv;",
                "1 | Revenue marker: LASER-LEDGER-19");
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Read " + path,
                "");
    }

    private static ToolCallLoop.ToolOutcome failedSourceEvidenceWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived write blocked before approval: " + path
                        + " does not include required exact evidence phrase(s).");
    }

    private static ToolCallLoop.ToolOutcome failedSourceEvidenceReadBeforeWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived artifact write blocked before approval: the current task requires reading "
                        + "source target(s) problem.md before writing `" + path + "`. Call talos.read_file "
                        + "for the source target(s) first, then retry the write. No approval was requested "
                        + "and no file was changed.");
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
