package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

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

    @Test
    void rejectedProviderRetryRestoresExactPriorProviderOwner() {
        beginTrace("post-tool-retry-rejected");
        String original = "How can I help you with these files?";
        LlmClient.StreamResult originalProviderResult = providerTextResult(original);
        List<ChatMessage> messages = synthesisMessages();

        String result = PostToolSynthesisRetry.synthesizeIfNeeded(
                original,
                1,
                messages,
                ignored -> providerTextResult("What would you like me to do next?"));

        assertEquals(original, result, "a rejected retry must preserve the returned answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                "SPECULATIVE_RETRY_REJECTED");
    }

    @Test
    void failedProviderRetryRestoresExactPriorProviderOwner() {
        beginTrace("post-tool-retry-failed");
        String original = "How can I assist you today?";
        LlmClient.StreamResult originalProviderResult = providerTextResult(original);
        List<ChatMessage> messages = synthesisMessages();

        String result = PostToolSynthesisRetry.synthesizeIfNeeded(
                original,
                1,
                messages,
                ignored -> {
                    failProviderGeneration();
                    throw new IllegalStateException("injected synthesis retry failure");
                });

        assertEquals(original, result, "a failed retry must preserve the returned answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                "SPECULATIVE_RETRY_FAILED");
    }

    @Test
    void emptyProviderRetryRestoresExactPriorProviderOwner() {
        beginTrace("post-tool-retry-empty");
        String original = "How can I help?";
        LlmClient.StreamResult originalProviderResult = providerTextResult(original);
        List<ChatMessage> messages = synthesisMessages();

        String result = PostToolSynthesisRetry.synthesizeIfNeeded(
                original,
                1,
                messages,
                ignored -> providerTextResult(""));

        assertEquals(original, result, "an empty retry must preserve the returned answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                "SPECULATIVE_RETRY_EMPTY");
    }

    @Test
    void acceptedProviderRetryRetainsExactRetryOwner() {
        beginTrace("post-tool-retry-accepted");
        String original = "How can I help?";
        providerTextResult(original);
        List<ChatMessage> messages = synthesisMessages();
        AtomicReference<ProviderResponseCapture.Receipt> retryReceipt = new AtomicReference<>();
        String accepted = "The button handler never updates the visible result text.";

        String result = PostToolSynthesisRetry.synthesizeIfNeeded(
                original,
                1,
                messages,
                ignored -> {
                    LlmClient.StreamResult retry = providerTextResult(accepted);
                    retryReceipt.set(retry.provenance());
                    return retry;
                });

        assertEquals(accepted, result, "an accepted retry must preserve the retry answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(retryReceipt.get().generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(retryReceipt.get().attemptId(), terminal.data().get("sourceAttemptId"));
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty(),
                "an accepted retry must not restore the earlier candidate");
    }

    private static List<ChatMessage> synthesisMessages() {
        return new ArrayList<>(List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Why does the BMI button fail?"),
                ChatMessage.assistant("tool result context")));
    }

    private static LlmClient.StreamResult providerTextResult(String text) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return new LlmClient.StreamResult(
                text, List.of(), "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false, false, receipt);
    }

    private static void failProviderGeneration() {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        generation.fail(ProviderResponseCapture.Failure.PROVIDER_EXCEPTION);
    }

    private static void assertRestoredProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt priorReceipt,
            String expectedReason) {
        List<TurnTraceEvent> restorations = events(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals(1, restorations.size(),
                "a rejected, failed, or empty speculative retry must restore exactly one prior candidate");
        TurnTraceEvent restoration = restorations.getFirst();
        assertEquals("MODEL_PROVIDER", restoration.data().get("origin"));
        assertEquals(priorReceipt.generationId(), restoration.data().get("sourceGenerationId"));
        assertEquals(priorReceipt.attemptId(), restoration.data().get("sourceAttemptId"));
        assertEquals(expectedReason, restoration.data().get("reasonCode"));

        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(priorReceipt.generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(priorReceipt.attemptId(), terminal.data().get("sourceAttemptId"));
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-post-tool-retry",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "verify post-tool retry answer lineage");
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return events(trace, type).getFirst();
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
    }
}
