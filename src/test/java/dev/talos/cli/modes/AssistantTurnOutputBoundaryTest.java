package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTurnOutputBoundaryTest {
    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        Thread.interrupted();
    }

    @Test
    void deterministicAnswerAndNextProviderTurnCompleteWithoutEmission() {
        var visible = new StringBuilder();
        var completed = new ArrayList<String>();
        var context = context(new ToolCallStreamFilter(visible::append),
                () -> completed.add(visible.toString()));
        var first = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertFalse(first.streamed());
        assertFalse(first.text().isBlank());
        assertTrue(visible.isEmpty());
        assertEquals(List.of(""), completed);

        visible.setLength(0);
        var second = execute(context, "Explain dependency injection in Java.",
                new AssistantTurnExecutor.Options());
        assertEquals("The dependency is supplied by the caller.", second.text());
        assertFalse(second.streamed());
        assertTrue(visible.isEmpty());
        assertEquals(List.of("", ""), completed);
    }

    @Test
    void deterministicUnicodeAndEmptyAnswersHaveIndependentBoundaries() {
        var visible = new StringBuilder();
        var completed = new AtomicInteger();
        var context = context(new ToolCallStreamFilter(visible::append), completed::incrementAndGet);
        for (String answer : List.of("A complete answer.\n\u03a9\ud83d\ude00", "", "Another answer.")) {
            visible.setLength(0);
            var result = execute(context, "Who are you?",
                    new AssistantTurnExecutor.Options().answerSanitizer(ignored -> answer));
            assertEquals(answer, result.text());
            assertFalse(result.streamed());
            assertTrue(visible.isEmpty());
        }
        assertEquals(3, completed.get());
    }

    @Test
    void protocolSuppressionSurvivesDeterministicCompletionAtTheFinalRenderer() throws Exception {
        var visible = new StringBuilder();
        var context = context(new ToolCallStreamFilter(visible::append), () -> {});
        String mixed = "A literal <div> is described.\n```json\n"
                + "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"secret.txt\"}}\n```\n"
                + "No tool was run.\u03a9\ud83d\ude00";
        var processor = new TurnProcessor((request, root, turnContext) -> {
            var result = AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                            ChatMessage.system("You are Talos."), ChatMessage.user(request))), root,
                    (Context) turnContext, new AssistantTurnExecutor.Options().answerSanitizer(ignored -> mixed));
            assertFalse(result.streamed());
            return Optional.of(new Result.Ok(result.text()));
        });
        var capture = new ByteArrayOutputStream();
        var renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                capture, true, false, 96, new Redactor());
        try (var session = new Session(workspace, context.cfg())) {
            var turn = processor.process(session, "Who are you?", context);
            assertTrue(visible.isEmpty(), "Protocol suppression must not depend on emitting fragments");
            assertEquals(0, capture.size());
            assertTrue(turn.audit().toolCalls().isEmpty());
            renderer.render(turn.result());
        }
        String displayed = capture.toString(StandardCharsets.UTF_8);
        assertTrue(displayed.contains("<div>"), displayed);
        assertTrue(displayed.contains("No tool was run.\u03a9\ud83d\ude00"), displayed);
        assertFalse(displayed.contains("secret.txt"), displayed);
        assertFalse(displayed.contains("talos.read_file"), displayed);
        capture.reset();
        var next = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertTrue(visible.isEmpty());
        assertFalse(next.streamed());
        assertFalse(next.text().isBlank());
        renderer.render(new Result.Ok(next.text()));
        assertFalse(capture.toString(StandardCharsets.UTF_8).contains("talos.read_file"));
    }

    @Test
    void cancellationDuringCompletionCannotLeakIntoNextTurn() {
        var visible = new StringBuilder();
        var fail = new AtomicBoolean(true);
        var completed = new AtomicInteger();
        var filter = new ToolCallStreamFilter(visible::append);
        var context = context(filter, () -> {
            assertTrue(visible.isEmpty());
            if (fail.getAndSet(false)) throw new CancellationException("output cancelled");
            completed.incrementAndGet();
        });
        assertThrows(CancellationException.class, () ->
                execute(context, "Who are you?", new AssistantTurnExecutor.Options()));
        assertEquals(0, completed.get());
        assertTrue(visible.isEmpty());
        var next = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertFalse(next.streamed());
        assertTrue(visible.isEmpty());
        assertFalse(next.text().isBlank());
        assertEquals(1, completed.get());
    }

    @Test
    void cancellationBeforeFinalSelectionCannotLeakIntoNextTurn() {
        var visible = new StringBuilder();
        var fail = new AtomicBoolean(true);
        var filter = new ToolCallStreamFilter(visible::append);
        var completed = new AtomicInteger();
        var context = context(filter, () -> {
            assertTrue(visible.isEmpty());
            if (fail.getAndSet(false)) throw new CancellationException("completion cancelled");
            completed.incrementAndGet();
        });
        assertThrows(CancellationException.class, () -> execute(context, "Who are you?",
                new AssistantTurnExecutor.Options().answerSanitizer(ignored -> "xy")));
        assertEquals(0, completed.get());
        assertTrue(visible.isEmpty());
        var next = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertFalse(next.streamed());
        assertTrue(visible.isEmpty());
        assertFalse(next.text().isBlank());
        assertEquals(1, completed.get());
    }

    @Test
    void interruptionDuringCompletionIsPropagatedWithoutCarryover() {
        var visible = new StringBuilder();
        var interrupt = new AtomicBoolean(true);
        var filter = new ToolCallStreamFilter(visible::append);
        var context = context(filter, () -> {
            assertTrue(visible.isEmpty());
            if (interrupt.getAndSet(false)) Thread.currentThread().interrupt();
        });
        assertThrows(RuntimeException.class, () -> execute(context, "Who are you?",
                new AssistantTurnExecutor.Options().answerSanitizer(ignored -> "xy")));
        assertTrue(Thread.interrupted());
        assertTrue(visible.isEmpty());
        var next = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertFalse(next.streamed());
        assertTrue(visible.isEmpty());
        assertFalse(next.text().isBlank());
    }

    @Test
    void completionCancellationLeavesNoDisplayOrCarryover() {
        var visible = new StringBuilder();
        var fail = new AtomicBoolean(true);
        var context = context(new ToolCallStreamFilter(visible::append), () -> {
            if (fail.getAndSet(false)) throw new CancellationException("completion cancelled");
        });
        assertThrows(CancellationException.class, () -> execute(context, "Who are you?",
                new AssistantTurnExecutor.Options().answerSanitizer(ignored -> "Completed.\ud83d\ude00")));
        assertTrue(visible.isEmpty(), "Completion cancellation must not expose a selected answer");
        var next = execute(context, "Who are you?", new AssistantTurnExecutor.Options());
        assertFalse(next.streamed());
        assertTrue(visible.isEmpty());
        assertFalse(next.text().isBlank());
    }

    private Context context(ToolCallStreamFilter filter, Runnable completion) {
        return Context.builder(new Config())
                .llm(ScriptedNativeLlmClient.textToolFallbackStrings(
                        "The dependency is supplied by the caller."))
                .streamSink(filter)
                .onStreamComplete(completion)
                .build();
    }

    private AssistantTurnExecutor.TurnOutput execute(
            Context context, String request, AssistantTurnExecutor.Options options) {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("output-boundary", "output-tests", 1,
                "2026-09-27T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", request);
        return AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."), ChatMessage.user(request))), workspace, context, options);
    }
}
