package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectUnderCompletionAnswerGuardTest {

    private static String longAnswer() {
        return "a".repeat(InspectUnderCompletionAnswerGuard.INSPECT_MIN_CHARS + 50);
    }

    private static List<ChatMessage> messagesWith(String userText) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user(userText));
        return messages;
    }

    private static ToolCallLoop.LoopResult loopWithTools(String... toolNames) {
        return new ToolCallLoop.LoopResult(
                "unused",
                toolNames.length,
                toolNames.length,
                List.of(toolNames),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);
    }

    @Test
    @DisplayName("annotates long inspect-first answer when only one read-only tool was used")
    void annotatesLongInspectFirstAnswerWithOneReadOnlyTool() {
        String answer = longAnswer();

        String shaped = InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                answer,
                messagesWith("Read the relevant files first, then summarize."),
                loopWithTools("talos.read_file"));

        assertTrue(shaped.startsWith(InspectUnderCompletionAnswerGuard.UNDER_INSPECTION_ANNOTATION));
        assertTrue(shaped.endsWith(answer));
    }

    @Test
    @DisplayName("does not annotate when two read-only tools were used")
    void doesNotAnnotateAfterTwoReadOnlyTools() {
        String answer = longAnswer();

        String shaped = InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                answer,
                messagesWith("Read the relevant files first, then summarize."),
                loopWithTools("talos.read_file", "talos.grep"));

        assertEquals(answer, shaped);
    }

    @Test
    @DisplayName("preserves current null and blank answer behavior")
    void preservesNullAndBlankAnswerBehavior() {
        List<ChatMessage> messages = messagesWith("Read the entry files first.");
        ToolCallLoop.LoopResult loopResult = loopWithTools("talos.read_file");

        assertNull(InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                null, messages, loopResult));
        assertEquals("   ", InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                "   ", messages, loopResult));
    }

    @Test
    @DisplayName("inspect marker and read-only tool count remain discriminating")
    void markerAndReadOnlyToolCountingRemainDiscriminating() {
        assertTrue(InspectUnderCompletionAnswerGuard.looksLikeInspectFirstRequest(
                "Start by reading the main files."));
        assertFalse(InspectUnderCompletionAnswerGuard.looksLikeInspectFirstRequest(
                "What is the capital of France?"));
        assertEquals(3, InspectUnderCompletionAnswerGuard.readOnlyToolCount(loopWithTools(
                "talos.read_file", "talos.edit_file", "list_dir", "talos.grep")));
    }
}
