package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostToolSynthesisRetryTest {

    @Test
    @DisplayName("retries post-tool deflection with original request anchored")
    void retriesPostToolDeflectionWithOriginalRequestAnchored() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("Why does the BMI button fail?"));
        messages.add(ChatMessage.assistant("tool result context"));
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        String result = PostToolSynthesisRetry.synthesizeIfNeeded(
                "How can I help you with these files?",
                2,
                messages,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("The button handler never updates visible text.", List.of());
                });

        assertEquals("The button handler never updates visible text.", result);
        assertEquals(5, messages.size(), "retry appends assistant answer and corrective user prompt");
        assertEquals("assistant", messages.get(3).role());
        assertEquals("user", messages.get(4).role());
        assertTrue(messages.get(4).content().contains("Why does the BMI button fail?"));
        assertTrue(messages.get(4).content().contains("Do not say the question is missing."));
        assertEquals(messages, retryMessages.get(), "chat function receives the appended retry messages");
    }

    @Test
    @DisplayName("does not retry substantive answers or no-tool turns")
    void doesNotRetrySubstantiveAnswersOrNoToolTurns() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("Summarize README.md"));
        String substantive = "The README says the project is a local workspace assistant.";

        String noToolResult = PostToolSynthesisRetry.synthesizeIfNeeded(
                "How can I help?", 0, messages, ignored -> {
                    throw new AssertionError("chat should not be called");
                });
        String substantiveResult = PostToolSynthesisRetry.synthesizeIfNeeded(
                substantive, 1, messages, ignored -> {
                    throw new AssertionError("chat should not be called");
                });

        assertEquals("How can I help?", noToolResult);
        assertSame(substantive, substantiveResult);
        assertEquals(1, messages.size(), "non-retry paths must not append messages");
    }

    @Test
    @DisplayName("deflection detection remains discriminating")
    void deflectionDetectionRemainsDiscriminating() {
        assertTrue(PostToolSynthesisRetry.isDeflection(null));
        assertTrue(PostToolSynthesisRetry.isDeflection("How can I assist you today?"));
        assertFalse(PostToolSynthesisRetry.isDeflection(
                "The HTML imports styles.css and script.js, and the form uses id bmi-form."));
    }
}
