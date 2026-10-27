package dev.talos.core.llm;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.ExecutionPipeline;
import dev.talos.cli.repl.slash.ExplainLastTurnCommand;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRouter;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** In-memory provider transport; real watchdog, turn, CLI and durable readback paths. */
@Timeout(45)
class WatchdogTurnSettlementTest {
    @AfterEach
    void clearCapture() {
        ProviderResponseCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void repetitionIsNotTimeoutInBufferedAndStreamingSettlement(@TempDir Path root) throws Exception {
        for (boolean streaming : List.of(false, true)) {
            assertWatchdog(root.resolve("repetition-" + streaming), "repetition", streaming,
                    "REPETITION_LOOP", 500, "repetition loop", "REPETITION_LOOP");
        }
    }

    @Test
    void actualTimeoutsKeepDistinctReasonsThroughReadback(@TempDir Path root) throws Exception {
        assertWatchdog(root.resolve("idle"), "idle", false,
                "TIMEOUT", 408, "idle", "IDLE_TIMEOUT");
        assertWatchdog(root.resolve("wall"), "wall", true,
                "TIMEOUT", 408, "wall-clock", "WALL_CLOCK_TIMEOUT");
    }

    @Test
    void legacyWatchdogProseCannotInventCauseAndTypedCauseOverridesContradictoryProse() throws Exception {
        var legacy = new LlmClient.StreamResult("partial", List.of(), "", "repetition loop",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG);
        assertEquals("UNEXPECTED", classifyInjected(legacy, "UNKNOWN"));
        var typed = new LlmClient.StreamResult("partial", List.of(), "", "idle timeout - no tokens",
                ProviderResponseCapture.AbortOrigin.RUNTIME_WATCHDOG)
                .withAbandonment(ProviderResponseCapture.Abandonment.REPETITION_LOOP);
        assertEquals("REPETITION_LOOP", classifyInjected(typed, "REPETITION_LOOP"));
        assertEquals("UNEXPECTED", classifyInjected(new LlmClient.StreamResult(
                "partial", List.of(), "", "untrusted diagnostic prose",
                ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT), "TRANSPORT_FAILURE"));
    }

    // Unit-only legacy metadata injection; actual provider/budget integration is above.
    private static String classifyInjected(LlmClient.StreamResult result, String expectedReason) throws Exception {
        var classify = AssistantTurnExecutor.class.getDeclaredMethod("abortedGenerationFailure", LlmClient.StreamResult.class);
        var record = AssistantTurnExecutor.class.getDeclaredMethod("recordLlmAbortOutcome", LlmClient.StreamResult.class, String.class);
        assertTrue(classify.trySetAccessible());
        assertTrue(record.trySetAccessible());
        LocalTurnTraceCapture.begin("legacy-cause", "sid", 1, "2026-09-07T00:00:00Z",
                "workspace-hash", "agent", "llama_cpp", "fixture", "Explain.");
        try {
            RuntimeException failure = (RuntimeException) classify.invoke(null, result);
            record.invoke(null, result, result.text());
            var trace = LocalTurnTraceCapture.complete();
            assertTrue(trace.events().stream().anyMatch(e -> e.type().equals("MODEL_GENERATION_ABORTED")
                    && expectedReason.equals(e.data().get("reasonCode"))), trace.events().toString());
            return dev.talos.runtime.TurnFailureClassifier.classify(failure).kind().name();
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    private static void assertWatchdog(Path root, String cause, boolean streaming,
                                       String kind, int code, String message, String reason) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path storage = root.resolve("sessions");
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
        Config config = new Config();
        config.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp"));
        config.data.put("limits", Map.of(
                "llm_idle_ms", cause.equals("idle") ? 1000 : 0,
                "llm_timeout_ms", cause.equals("wall") ? 1000 : 10000));
        BlockingResolver resolver = new BlockingResolver(cause.equals("repetition"));
        JsonSessionStore store = new JsonSessionStore(storage);
        try (LlmClient client = new LlmClient(config, resolver);
             var lease = store.acquireSessionWriter(sessionId)) {
            Context.Builder builder = Context.builder(config).llm(client)
                    .sandbox(new Sandbox(workspace, Map.of()));
            if (streaming) builder.streamSink(ignored -> {});
            Context context = builder.build();
            TurnRouter router = (raw, ignoredWorkspace, ignoredContext) -> {
                AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                                ChatMessage.system("Respond briefly."), ChatMessage.user(raw))),
                        workspace, context, new AssistantTurnExecutor.Options());
                return Optional.of(new Result.Ok("UNREACHABLE_SUCCESS"));
            };
            TurnProcessor processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(lease, sessionId));
            Result output = new ExecutionPipeline().run(() -> {
                processor.process(new Session(workspace, config), "Explain briefly.", context);
                return new Result.Ok("UNREACHABLE_SUCCESS");
            }, context, "watchdog fixture");
            Result.Error failure = assertInstanceOf(Result.Error.class, output);
            assertEquals(code, failure.code, failure.toString());
            assertTrue(failure.message.contains(message), failure.message);
            assertEquals(1, resolver.calls.get(), "aborted output must not be retried");
        }
        JsonSessionStore reopened = new JsonSessionStore(storage);
        var rows = reopened.loadTurns(sessionId);
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals("failed", row.status());
        assertEquals("", row.assistantText(), "partial bytes cannot be persisted as an answer");
        var trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
        var settlements = trace.events().stream().filter(e -> e.type().equals("TURN_SETTLED")).toList();
        assertEquals(1, settlements.size());
        var terminal = settlements.getFirst();
        assertEquals(kind, terminal.data().get("errorKind"));
        assertEquals(code, ((Number) terminal.data().get("errorCode")).intValue());
        assertEquals(TurnSettlementTraceEvent.DecodeStatus.VALID,
                TurnSettlementTraceEvent.decode(trace).status());
        // Abnormal safe projection intentionally retains the typed provider ledger,
        // not the ordinary conversational MODEL_GENERATION_ABORTED event.
        assertTrue(trace.events().stream().anyMatch(e -> e.type().equals("GENERATION_ABANDONED")
                && reason.equals(e.data().get("abandonment"))), trace.events().toString());
        assertFalse(trace.events().stream().anyMatch(e -> e.type().equals("GENERATION_COMPLETED")),
                "late provider EOS must not replace the winning abandonment");
        var last = new ExplainLastTurnCommand(workspace, reopened, null, sessionId);
        Result.TrustedInfo rendered = (Result.TrustedInfo) last.execute("trace", Context.builder(config).build());
        assertTrue(rendered.text.contains("Trace pair: VALID"), rendered.text);
        assertTrue(rendered.text.contains(kind), rendered.text);
        assertFalse(rendered.text.contains("UNREACHABLE_SUCCESS"));
    }

    private static final class BlockingResolver implements LlmEngineResolver {
        private final boolean repetition;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private BlockingResolver(boolean repetition) { this.repetition = repetition; }
        @Override public void select(String backend, String model) {}
        @Override public Capabilities capabilities() { return Capabilities.of(true, false, false, 32768); }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            Iterator<TokenChunk> chunks = new Iterator<>() {
                private int next;
                @Override public boolean hasNext() {
                    if (next == 0) return true;
                    if (next > 1) return false;
                    try { closed.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return true;
                }
                @Override public TokenChunk next() {
                    if (next++ == 0) return TokenChunk.of(repetition
                            ? "Repeated provider fragment without useful progress. ".repeat(20) : "partial");
                    return TokenChunk.eos("stop"); // late EOS cannot replace the winning abort
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(chunks, 0), false)
                    .onClose(closed::countDown);
        }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(request, handoff, () -> chatStream(request));
        }
        @Override public void close() { closed.countDown(); }
    }
}
