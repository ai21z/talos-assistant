package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactReadOnlyEvidenceContinuationTest {

    @Test
    void ownerBuildsCompactReadOnlyEvidenceAnswerWithoutConversationHistory() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(
                        "Suggestion: say the README validates the workflow.",
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
        state.successfulReadCallBodies.put(
                "talos.read_file:path=readme.md;",
                "1 | # Fixture\n2 | README evidence belongs in the compact answer.");

        boolean answered = CompactReadOnlyEvidenceContinuation.tryAnswer(
                state,
                "tool-call loop continuation");

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
}
