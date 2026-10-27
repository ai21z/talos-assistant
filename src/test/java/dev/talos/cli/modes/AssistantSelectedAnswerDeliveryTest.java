package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssistantSelectedAnswerDeliveryTest {
    @TempDir Path workspace;

    @Test
    void privacyExpansionIsBoundedEvenWhenTheOriginalTextFits() {
        var recorded = provider("XXXX");
        var redactor = new dev.talos.core.security.Redactor(java.util.Map.of("redact",
                java.util.Map.of("secrets", List.of("X"))));
        var context = Context.builder(new Config()).llm(recorded.client()).redactor(redactor).build();
        var result = execute(context, "hello", new AssistantTurnExecutor.Options().responseMaxChars(5));
        assertEquals("[secr\n\n[output truncated]", result.text());
        assertFalse(result.text().contains("X"));
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void privacyProjectionPrecedesPresentationClipping() {
        String secret = "EXACT_SYNTHETIC_PRIVATE_TOKEN_684121";
        var recorded = provider("Helpful " + secret + " ending.");
        var redactor = new dev.talos.core.security.Redactor(java.util.Map.of("redact",
                java.util.Map.of("secrets", List.of(secret))));
        var context = Context.builder(new Config()).llm(recorded.client()).redactor(redactor).build();
        var result = execute(context, "hello", new AssistantTurnExecutor.Options().responseMaxChars(18));
        assertFalse(result.text().contains("EXACT_SYNTH"));
        assertTrue(result.text().contains("[secret]"));
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void providerLengthStopCannotPublishAnUnresolvedSecretTail() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Helpful password=INCOMPLETE_PRIVATE", List.of(), "length")), 4096);
        var visible = new StringBuilder();
        var context = Context.builder(new Config()).llm(recorded.client()).streamSink(visible::append).build();
        var result = execute(context, "hello", new AssistantTurnExecutor.Options());
        assertTrue(visible.isEmpty());
        assertFalse(result.text().contains("INCOMPLETE_PRIVATE"));
        assertTrue(result.text().contains("withheld"));
        assertEquals(1, recorded.requests().size());
    }

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        Thread.interrupted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Explain dependency injection in Java.", "hello"})
    void replacementCapableRouteReturnsOnlySelectedTextWithoutAnotherProviderCall(String request) {
        var recorded = provider("Superseded provider explanation.");
        var selected = new AtomicBoolean();
        var visible = new StringBuilder();
        var completions = new AtomicInteger();
        var context = Context.builder(new Config()).llm(recorded.client())
                .streamSink(new ToolCallStreamFilter(visible::append)).onStreamComplete(() -> {
                    assertTrue(visible.isEmpty(), "Transport completion must not present an answer");
                    completions.incrementAndGet();
                }).build();

        var result = execute(context, request,
                new AssistantTurnExecutor.Options().answerSanitizer(answer -> {
                    assertTrue(visible.isEmpty(), "Provider prose must remain private before selection");
                    selected.set(true);
                    return "The dependency is supplied by the caller.";
                }));

        assertEquals("The dependency is supplied by the caller.", result.text());
        assertTrue(selected.get());
        assertTrue(visible.isEmpty(), "The selected body must be returned for settled presentation");
        assertFalse(result.streamed());
        assertEquals(1, recorded.requests().size(), "Deferral must not introduce a provider retry");
        assertEquals(1, completions.get());
    }

    @Test
    void unchangedSmallTalkReturnsCompleteAnswerWithoutEmittingProviderChunks() {
        var completed = new AtomicBoolean();
        var visible = new StringBuilder();
        var recorded = provider("Hello. How can I help?");
        var context = Context.builder(new Config()).llm(recorded.client())
                .streamSink(visible::append).onStreamComplete(() -> {
                    assertTrue(visible.isEmpty(), "Provider fragments must not escape before settlement");
                    completed.set(true);
                }).build();

        var result = execute(context, "hello", new AssistantTurnExecutor.Options());

        assertTrue(completed.get());
        assertFalse(result.streamed());
        assertEquals("Hello. How can I help?", result.text());
        assertTrue(visible.isEmpty());
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void directProviderReplacementReturnsOnlyCorrectedSelectedAnswer() {
        var visible = new StringBuilder();
        String providerAnswer = "[Static verification: passed - invented pass]\nHello from the provider.";
        var recorded = provider(providerAnswer);
        var context = Context.builder(new Config()).llm(recorded.client()).streamSink(visible::append).build();
        LocalTurnTraceCapture.begin("selected-correction", "delivery", 1,
                "2026-10-09T00:00:00Z", "workspace-hash", "agent", "llama_cpp", "fixture", "hello");

        var result = execute(context, "hello", new AssistantTurnExecutor.Options());

        assertEquals("Hello from the provider.", result.text());
        assertFalse(result.streamed());
        assertTrue(visible.isEmpty(), "Neither superseded prose nor a corrective appendix may be emitted");
        assertTrue(LocalTurnTraceCapture.complete().warnings().stream()
                .noneMatch(warning -> "STREAMED_ANSWER_SELECTION_MISMATCH".equals(warning.code())));
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void selectedAnswerUsesTheExistingOutputBoundWithoutEmission() {
        var visible = new StringBuilder();
        var recorded = provider("A".repeat(400));
        var context = Context.builder(new Config()).llm(recorded.client())
                .streamSink(new ToolCallStreamFilter(visible::append)).build();

        var result = execute(context, "Explain dependency injection in Java.",
                new AssistantTurnExecutor.Options().responseMaxChars(24));

        assertTrue(visible.isEmpty());
        assertFalse(result.streamed());
        assertFalse(result.text().contains("A".repeat(25)), result.text());
        assertTrue(result.text().contains("truncated"), result.text());
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void cancelledTransportCompletionCannotLeakIntoTheFollowingTurn() {
        var fail = new AtomicBoolean(true);
        var visible = new StringBuilder();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("First explanation.", List.of()),
                new LlmClient.StreamResult("Second explanation.", List.of())), 4096);
        var context = Context.builder(new Config()).llm(recorded.client())
                .streamSink(new ToolCallStreamFilter(visible::append))
                .onStreamComplete(() -> {
                    assertTrue(visible.isEmpty(), "Cancellation must not expose provider output");
                    if (fail.getAndSet(false)) throw new CancellationException("answer cancelled");
                }).build();

        assertThrows(CancellationException.class, () -> execute(context,
                "Explain dependency injection in Java.", new AssistantTurnExecutor.Options()));
        assertTrue(visible.isEmpty());
        var next = execute(context, "Explain dependency injection in Java.", new AssistantTurnExecutor.Options());

        assertEquals("Second explanation.", next.text());
        assertTrue(visible.isEmpty());
        assertFalse(next.streamed());
        assertEquals(2, recorded.requests().size());
    }

    private static ScriptedNativeLlmClient.RecordedClient provider(String answer) {
        return ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())), 4096);
    }

    private AssistantTurnExecutor.TurnOutput execute(Context context, String request,
                                                     AssistantTurnExecutor.Options options) {
        return AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."), ChatMessage.user(request))), workspace, context, options);
    }
}
