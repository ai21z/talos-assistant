package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class CompactReadOnlyEvidenceContinuationTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void ownerBuildsCompactReadOnlyEvidenceAnswerWithoutConversationHistory() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        "  Suggestion: say the README validates the workflow.  ",
                        List.of())),
                2048);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .build();
        String request = "Please review README.md again and propose one concrete wording improvement, "
                + "but do not edit any files yet.";
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys large-system-token"),
                ChatMessage.user("Earlier README conversation that must not enter the compact frame."),
                ChatMessage.assistant("Historical proposal that must not enter the compact frame."),
                ChatMessage.user(request)));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                5,
                0);
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "README.md",
                true,
                false,
                false,
                "read README.md",
                ""));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", java.util.Map.of("path", "README.md")),
                "README.md",
                ToolResult.ok("1 | # Fixture\n2 | README evidence belongs in the compact answer."));
        beginTrace();

        boolean answered = CompactReadOnlyEvidenceContinuation.tryAnswer(
                state,
                "tool-call loop continuation");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(answered);
        assertEquals("Suggestion: say the README validates the workflow.", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertFalse(state.failureDecision.shouldStop(), state.failureDecision.reason());
        assertFalse(state.hasPendingActionObligation());
        assertEquals(1, recorded.requests().size(), "compact answer should make one backend call");
        String compactPrompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(compactPrompt.contains("[ReadOnlyEvidenceAnswer]"), compactPrompt);
        assertTrue(compactPrompt.contains(request), compactPrompt);
        assertTrue(compactPrompt.contains("README evidence belongs in the compact answer"), compactPrompt);
        assertFalse(compactPrompt.contains("large-system-token"), compactPrompt);
        assertFalse(compactPrompt.contains("Earlier README conversation"), compactPrompt);
        assertFalse(compactPrompt.contains("Historical proposal"), compactPrompt);
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertTrue(terminal.data().containsKey("sourceGenerationId"));
        assertTrue(terminal.data().containsKey("sourceAttemptId"));
        assertTrue(trace.events().stream().anyMatch(event ->
                "ANSWER_TRANSFORMED".equals(event.type())
                        && "PROVIDER_RESPONSE_NORMALIZATION".equals(
                                event.data().get("reasonCode"))));
    }

    @Test
    void wrappedCancellationEscapesCompactReadOnlyEvidenceContinuation() {
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("cancel compact read-only continuation"));
        LoopState state = readOnlyEvidenceState(LlmClient.scriptedFailure(wrapped));

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                CompactReadOnlyEvidenceContinuation.tryAnswer(
                        state,
                        "tool-call loop continuation"));

        assertSame(wrapped, thrown,
                "continuation cancellation must preserve the caller-owned runtime wrapper");
    }

    @Test
    void repromptStageDelegatesCompactReadOnlyEvidenceContinuationToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptContextBudgetHandler.java"));

        assertFalse(source.contains("CompactReadOnlyEvidenceContinuation.tryAnswer"), source);
        assertTrue(handler.contains("CompactReadOnlyEvidenceContinuation.tryAnswer"), handler);
        assertFalse(source.contains("private static boolean tryCompactReadOnlyEvidenceContinuation"), source);
        assertFalse(source.contains("private static List<ChatMessage> readOnlyEvidenceAnswerMessages"), source);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trace-compact-read-only",
                "session-compact-read-only",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "review README.md");
    }

    private static LoopState readOnlyEvidenceState(LlmClient llm) {
        String request = "Review README.md and propose one wording improvement, but do not edit it.";
        var ctx = Context.builder(new Config())
                .llm(llm)
                .build();
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                ctx,
                null,
                5,
                0);
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "README.md",
                true,
                false,
                false,
                "read README.md",
                ""));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", java.util.Map.of("path", "README.md")),
                "README.md",
                ToolResult.ok("1 | # Fixture\n2 | Evidence for a wording proposal."));
        return state;
    }
}
