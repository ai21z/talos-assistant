package dev.talos.runtime;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.cli.ui.FinalAnswerStatusRenderer;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.trace.FinalAnswerStatus;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted transport crosses the real executor, terminalizer and durable session store. */
class FinalAnswerStatusPersistenceTest {
    @TempDir Path dir;
    @AfterEach void clear() { LocalTurnTraceCapture.clear(); TurnTaskContractCapture.clear(); }

    @Test void bufferedAndStreamingLengthAnswersWithholdTextAndRetainTypedStatusAfterReload() throws Exception {
        for (boolean streaming : List.of(false, true)) run(streaming, "length", 20_000, true);
    }

    @Test void unknownProviderTerminalReasonsWithholdTextAndRetainTypedStatusAfterReload() throws Exception {
        for (boolean streaming : List.of(false, true)) run(streaming, "unexpected-provider-terminal", 20_000, true);
    }

    @Test void ordinaryStopsDoNotCertifyCoverageAndLocalCapsRemainVisible() throws Exception {
        run(false, "stop", 20_000, false);
        run(false, "stop", 12, true);
    }

    private void run(boolean streaming, String finishReason, int cap, boolean limited) throws Exception {
        boolean uncommitted = !"stop".equals(finishReason);
        String label = streaming+"-"+finishReason+"-"+cap;
        Path workspace = Files.createDirectory(dir.resolve(label));
        Path storage = dir.resolve(label+"-store");
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.parse("2026-10-09T00:00:00Z"));
        String answer = "Recursion repeats a function call until a stopping condition is reached.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult(answer, List.of(), finishReason),
                new LlmClient.StreamResult("A loop repeats its body.", List.of(), "stop")), 16_384);
        var store = new JsonSessionStore(storage);
        var config = new Config();
        var visible = new StringBuilder();
        var rawSinkCalls = new AtomicInteger();
        try (LlmClient llm = recorded.client(); var writer = store.acquireSessionWriter(sessionId)) {
            var builder = Context.builder(config).llm(llm);
            if (streaming) builder.streamSink(new ToolCallStreamFilter(chunk -> {
                rawSinkCalls.incrementAndGet();
                visible.append(chunk);
            }));
            var context = builder.build();
            var options = new AssistantTurnExecutor.Options().responseMaxChars(cap);
            TurnRouter router = (request, root, ignored) -> {
                var result = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                        ChatMessage.user(request))), root, context, options);
                return Optional.of(new Result.Ok(result.text()));
            };
            var processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var session = new Session(workspace, config);
            var turn = processor.process(session, "Explain recursion briefly.", context);
            String delivered = assertInstanceOf(Result.Ok.class, turn.result()).text;
            var status = FinalAnswerStatus.decode(turn.audit().localTrace());
            assertTrue(status.available(), turn.audit().localTrace().toString());
            assertEquals(FinalAnswerStatus.Coverage.NOT_ASSESSED, status.coverage());
            assertEquals(limited ? FinalAnswerStatus.Delivery.LIMITED : FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT,
                    status.delivery());
            assertEquals(limited ? "ADVISORY_ONLY" : "COMPLETE", turn.audit().localTrace().outcome().status());
            assertEquals("COMPLETE", status.operational().status());
            assertEquals("NOT_RUN", status.operational().verificationStatus());
            assertEquals("NOT_REQUESTED", status.operational().mutationStatus());
            if (uncommitted) {
                assertTrue(delivered.contains("uncommitted provider text was withheld"), delivered);
                assertFalse(delivered.contains("Recursion"), delivered);
            } else if (limited) {
                assertTrue(delivered.startsWith(answer.substring(0, cap)), delivered);
                assertTrue(delivered.contains("[output truncated]"), delivered);
                assertFalse(delivered.contains(answer), delivered);
            } else assertTrue(delivered.contains(answer), delivered);
            assertEquals(0, rawSinkCalls.get(), "Provider capture must not invoke the public answer sink");
            assertEquals("", visible.toString());
            assertEquals(1, recorded.requests().size());

            var reopened = new JsonSessionStore(storage);
            var row = reopened.loadTurns(sessionId).getFirst();
            assertEquals("ok", row.status());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(delivered, "Explain recursion briefly."), row.assistantText());
            assertFalse(row.assistantText().contains("[Model output limit reached"));
            if (uncommitted) assertFalse(row.assistantText().contains("Recursion"), row.assistantText());
            else if (limited) {
                assertTrue(row.assistantText().startsWith(answer.substring(0, cap)), row.assistantText());
                assertTrue(row.assistantText().contains("[output truncated]"), row.assistantText());
                assertFalse(row.assistantText().contains(answer), row.assistantText());
            }
            var reloaded = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
            assertEquals(status, FinalAnswerStatus.decode(reloaded));
            assertEquals(FinalAnswerStatusRenderer.lines(turn.audit().localTrace()), FinalAnswerStatusRenderer.lines(reloaded));
            var bytes = new ByteArrayOutputStream();
            var renderer = new RenderEngine(config, new Redactor(), new PrintStream(bytes, true, StandardCharsets.UTF_8), true);
            renderer.render(turn.result());
            String terminalAnswer = bytes.toString(StandardCharsets.UTF_8);
            if (uncommitted) {
                assertTrue(terminalAnswer.contains("withheld"), terminalAnswer);
                assertFalse(terminalAnswer.contains("Recursion"), terminalAnswer);
            } else if (limited) {
                assertTrue(terminalAnswer.contains(answer.substring(0, cap)), terminalAnswer);
                assertTrue(terminalAnswer.contains("[output truncated]"), terminalAnswer);
                assertFalse(terminalAnswer.contains(answer), terminalAnswer);
            } else assertTrue(terminalAnswer.contains(answer), terminalAnswer);
            renderer.printTurnStats(turn, delivered.length());
            String footer = bytes.toString(StandardCharsets.UTF_8);
            for (String line : FinalAnswerStatusRenderer.lines(reloaded)) assertTrue(footer.contains(line), footer);
            var last = new dev.talos.cli.repl.slash.ExplainLastTurnCommand(workspace, reopened, null, sessionId);
            for (String view : List.of("", "trace")) {
                String diagnostic = assertInstanceOf(Result.TrustedInfo.class, last.execute(view, context)).text;
                for (String line : FinalAnswerStatusRenderer.lines(reloaded))
                    assertTrue(diagnostic.contains(line), diagnostic);
            }
            var quietBytes = new ByteArrayOutputStream();
            new RenderEngine(config, new Redactor(), new PrintStream(quietBytes), false)
                    .printTurnStats(turn, delivered.length());
            assertEquals(0, quietBytes.size());

            options.responseMaxChars(20_000);
            var next = processor.process(session, "Explain a loop briefly.", context);
            assertEquals(FinalAnswerStatus.Delivery.NO_KNOWN_LIMIT, FinalAnswerStatus.decode(next.audit().localTrace()).delivery());
            assertEquals(2, recorded.requests().size());
        }
    }
}
