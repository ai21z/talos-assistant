package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Spliterators;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Authored SSE bytes traverse real loopback HTTP, the compatibility decoder and the client budget. */
@Timeout(15)
class LlmClientFirstOutputBudgetTest {
    private static final long IDLE_MS = 1_000L;
    private static final long FIRST_OUTPUT_MS = 2_300L;
    private static final List<ChatMessage> MESSAGES = List.of(ChatMessage.user("Inspect the requested source."));
    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("talos.read_file", "Read a file.",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
    private static final ChatRequestControls CONTROLS = ChatRequestControls.defaults()
            .withSingleAttempt(true)
            .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);

    @AfterEach
    void clearCaptures() {
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
    }

    @Test
    void delayedFirstContentSurvivesTheShorterIdleBudget() throws Exception {
        try (var fixture = new Fixture(IDLE_MS, session -> {
            Thread.sleep(1_700L);
            session.send(content("Delayed answer."));
            session.send(done());
        })) {
            long started = System.nanoTime();
            var result = fixture.chat(4_000L);
            assertFalse(result.aborted(), result.text());
            assertEquals("Delayed answer.", result.text());
            assertEquals(result.text(), fixture.displayed.toString());
            assertTrue(elapsed(started) >= 1_600L, "the first content must actually be delayed beyond idle");
            assertEquals(1, fixture.resolver.textChunks.get());
            assertTrue(result.toolCalls().isEmpty());
            fixture.assertRealTransport();
            assertTrue(fixture.ledger.abandonments.isEmpty());
        }
    }

    enum BeforeOutput { SILENT, HEARTBEAT, REASONING_CONTENT, REASONING, NATIVE_ARGUMENTS }

    @ParameterizedTest
    @EnumSource(BeforeOutput.class)
    void nonOutputFramesRemainBoundedByTheFirstOutputDeadline(BeforeOutput traffic) throws Exception {
        try (var fixture = new Fixture(IDLE_MS, session -> {
            if (traffic == BeforeOutput.NATIVE_ARGUMENTS) session.send(partialToolStart());
            while (!session.release.await(100L, TimeUnit.MILLISECONDS)) {
                switch (traffic) {
                    case SILENT -> { }
                    case HEARTBEAT -> session.send(": keep-alive\n\n");
                    case REASONING_CONTENT -> session.send(delta("\"content\":\"\",\"reasoning_content\":\"hidden deliberation\""));
                    case REASONING -> session.send(delta("\"reasoning\":\"hidden deliberation\""));
                    case NATIVE_ARGUMENTS -> session.send(delta("\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\" \"}}]"));
                }
            }
            if (traffic == BeforeOutput.NATIVE_ARGUMENTS) session.send(partialToolEnd());
            session.send(content("Late answer must not be selected."));
            session.send(done());
        })) {
            long started = System.nanoTime();
            var result = fixture.chat(FIRST_OUTPUT_MS);
            assertAbort(result, ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT);
            assertTrue(elapsed(started) >= FIRST_OUTPUT_MS - 150L,
                    "wire activity is not decoded progress and idle must not end the first-output allowance");
            assertTrue(elapsed(started) < FIRST_OUTPUT_MS + 3_000L,
                    "non-output frames must not extend the first-output deadline");
            assertEquals(0, fixture.resolver.textChunks.get());
            assertEquals(0, fixture.resolver.toolChunks.get());
            assertEquals("", fixture.displayed.toString());
            if (traffic != BeforeOutput.SILENT) assertTrue(fixture.framesWritten.get() >= 3);
            fixture.assertRealTransport();
            fixture.assertLateResponseCannotReplaceAbort(result);
        }
    }

    @Test
    void firstContentThenAStallUsesTheIdleBudget() throws Exception {
        try (var fixture = new Fixture(IDLE_MS, session -> {
            session.send(content("Visible first sentence.\n"));
            session.release.await();
            session.send(content("Late answer must not be selected."));
            session.send(done());
        })) {
            long started = System.nanoTime();
            var result = fixture.chat(6_000L);
            assertAbort(result, ProviderResponseCapture.Abandonment.IDLE_TIMEOUT);
            assertEquals(1, fixture.resolver.textChunks.get());
            assertTrue(elapsed(started) >= IDLE_MS - 100L);
            assertTrue(elapsed(started) < 4_000L, "the six-second first-output deadline must not replace idle");
            fixture.assertRealTransport();
            fixture.assertLateResponseCannotReplaceAbort(result);
        }
    }

    @Test
    void completedNativeToolChunksKeepTheirExistingProgressSemantics() throws Exception {
        try (var fixture = new Fixture(2_000L, session -> {
            Thread.sleep(1_500L);
            session.send(completeTool("call_one", "one.txt"));
            Thread.sleep(1_500L);
            session.send(done());
        })) {
            long started = System.nanoTime();
            var result = fixture.chat(2_500L);
            assertFalse(result.aborted(), result.text());
            assertTrue(elapsed(started) >= 2_900L, "completed calls must permit progress beyond the initial deadline");
            assertEquals(1, fixture.resolver.toolChunks.get());
            assertEquals(0, fixture.resolver.textChunks.get());
            assertEquals(List.of("one.txt"), result.toolCalls().stream()
                    .map(call -> call.arguments().get("path")).toList());
            assertEquals("", fixture.displayed.toString());
            fixture.assertRealTransport();
            assertTrue(fixture.ledger.abandonments.isEmpty());
        }
    }

    @Test
    void disabledIdleKeepsAHardWallClockDespiteDecodedProgress() throws Exception {
        try (var fixture = new Fixture(0L, session -> {
            int index = 0;
            do {
                session.send(content("Progress " + index++ + ".\n"));
            } while (!session.release.await(100L, TimeUnit.MILLISECONDS));
            session.send(done());
        })) {
            var result = fixture.chat(900L);
            assertAbort(result, ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT);
            assertTrue(fixture.resolver.textChunks.get() >= 2);
            fixture.assertRealTransport();
            fixture.assertLateResponseCannotReplaceAbort(result);
        }
    }

    @Test
    void cancellationBeforeFirstOutputCannotAcquireLateTextOrToolAuthority() throws Exception {
        try (var fixture = new Fixture(IDLE_MS, session -> {
            session.send(delta("\"reasoning_content\":\"still thinking\""));
            session.release.await();
            session.send(content("Late answer must not be selected."));
            session.send(completeTool("late_call", "late.txt"));
            session.send(done());
        })) {
            AtomicBoolean cancelled = new AtomicBoolean();
            fixture.client.setCancelSupplier(cancelled::get);
            Thread cancel = Thread.ofPlatform().daemon().start(() -> {
                try {
                    if (fixture.resolver.readEntered.await(3L, TimeUnit.SECONDS)) cancelled.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            long started = System.nanoTime();
            var result = fixture.chat(6_000L);
            cancel.join(1_000L);
            assertFalse(cancel.isAlive());
            assertTrue(cancelled.get(), "cancellation must follow a real HTTP request");
            assertAbort(result, ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
            assertEquals(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL, result.abortOrigin());
            assertTrue(elapsed(started) < 4_000L, "cancellation must not wait for the first-output deadline");
            assertEquals("", fixture.displayed.toString());
            assertEquals(0, fixture.resolver.textChunks.get());
            assertEquals(0, fixture.resolver.toolChunks.get());
            fixture.assertRealTransport();
            fixture.assertLateResponseCannotReplaceAbort(result);
        }
    }

    private static void assertAbort(LlmClient.StreamResult result, ProviderResponseCapture.Abandonment reason) {
        assertTrue(result.aborted(), result.text());
        assertEquals(reason, result.abandonment().orElseThrow());
        assertTrue(result.toolCalls().isEmpty(), "an abandoned generation must not authorize a native call");
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String delta(String fields) {
        return "data: {\"choices\":[{\"delta\":{" + fields + "}}]}\n\n";
    }

    private static String content(String text) throws IOException {
        return delta("\"content\":" + new ObjectMapper().writeValueAsString(text));
    }

    private static String done() { return "data: [DONE]\n\n"; }

    private static String partialToolStart() {
        return delta("\"tool_calls\":[{\"index\":0,\"id\":\"partial_call\",\"type\":\"function\","
                + "\"function\":{\"name\":\"talos.read_file\",\"arguments\":\"{\"}}]");
    }

    private static String partialToolEnd() {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"arguments\":\"\\\"path\\\":\\\"late.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n";
    }

    private static String completeTool(String id, String path) {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"talos.read_file\","
                + "\"arguments\":\"{\\\"path\\\":\\\"" + path + "\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n";
    }

    @FunctionalInterface
    private interface Script { void write(Session session) throws Exception; }

    private record Session(OutputStream body, CountDownLatch release, AtomicInteger framesWritten) {
        void send(String frame) throws IOException {
            body.write(frame.getBytes(StandardCharsets.UTF_8));
            body.flush();
            framesWritten.incrementAndGet();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch headersSent = new CountDownLatch(1);
        final CountDownLatch serverFinished = new CountDownLatch(1);
        final AtomicInteger framesWritten = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final StringBuffer displayed = new StringBuffer();
        final Ledger ledger = new Ledger();
        final ExecutorService serverExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "first-output-sse-fixture");
            thread.setDaemon(true);
            return thread;
        });
        final HttpServer server;
        final HttpClient http;
        final CompatResolver resolver;
        final LlmClient client;

        Fixture(long idleMs, Script script) throws IOException {
            ProviderResponseCapture.begin(ledger, "first-output-http-fixture");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverExecutor);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                try {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    headersSent.countDown();
                    script.write(new Session(exchange.getResponseBody(), release, framesWritten));
                } catch (IOException closedByClient) {
                    // A timed-out or cancelled client may close the body before late fixture bytes are sent.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Exception failure) {
                    throw new IOException("SSE fixture failed", failure);
                } finally {
                    exchange.close();
                    serverFinished.countDown();
                }
            });
            server.start();
            http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2)).build();
            resolver = new CompatResolver(new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "fixture-model", http, new ObjectMapper()));
            Config config = new Config();
            config.data.put("llm", new LinkedHashMap<>(java.util.Map.of("transport", "engine", "default_backend", "llama_cpp")));
            config.data.put("llama_cpp", new LinkedHashMap<>(java.util.Map.of("model", "fixture-model")));
            config.data.put("limits", new LinkedHashMap<>(java.util.Map.of("llm_idle_ms", idleMs)));
            client = new LlmClient(config, resolver);
        }

        LlmClient.StreamResult chat(long wallMs) {
            return client.chatStreamFull(MESSAGES, displayed::append, wallMs, TOOLS, CONTROLS);
        }

        void assertRealTransport() {
            assertEquals(1, requests.get());
            assertEquals(1, resolver.calls.get(), "an abandoned or completed physical request must not be retried");
            assertTrue(ledger.transport.stream().anyMatch(event -> event.transition() == ProviderTransportAttempt.Transition.SEND_API_ENTERED));
            assertTrue(ledger.transport.stream().anyMatch(event -> event.transition() == ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED));
            assertTrue(ledger.transport.stream().allMatch(event -> event.observationMode() == ProviderTransportAttempt.ObservationMode.OBSERVED));
        }

        void assertLateResponseCannotReplaceAbort(LlmClient.StreamResult result) throws InterruptedException {
            String before = displayed.toString();
            assertTrue(ledger.responses.isEmpty());
            assertTrue(ledger.completions.isEmpty());
            assertEquals(1, ledger.abandonments.size());
            release.countDown();
            assertTrue(serverFinished.await(2L, TimeUnit.SECONDS));
            assertTrue(resolver.streamClosed.await(2L, TimeUnit.SECONDS));
            assertEquals(before, displayed.toString(), "late HTTP data must not re-enter the display callback");
            assertFalse(result.text().contains("Late answer"));
            assertTrue(result.toolCalls().isEmpty());
            assertTrue(ledger.responses.isEmpty(), "late body completion must not publish a selected provider response");
            assertTrue(ledger.completions.isEmpty());
            assertEquals(1, ledger.abandonments.size());
            assertEquals(1, resolver.calls.get());
        }

        @Override
        public void close() {
            release.countDown();
            client.close();
            server.stop(0);
            serverExecutor.shutdownNow();
            http.shutdownNow();
        }
    }

    private static final class CompatResolver implements LlmEngineResolver {
        final CompatChatClient compat;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger textChunks = new AtomicInteger();
        final AtomicInteger toolChunks = new AtomicInteger();
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch streamClosed = new CountDownLatch(1);

        CompatResolver(CompatChatClient compat) { this.compat = compat; }
        @Override public void select(String backend, String model) { }
        @Override public Capabilities capabilities() { return Capabilities.of(true, true, false, 4_096, true); }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
            return chatStream(request, ProviderTransportHandoff.none());
        }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
            calls.incrementAndGet();
            Stream<TokenChunk> stream = compat.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff);
            var source = stream.spliterator();
            var tracked = new Spliterators.AbstractSpliterator<TokenChunk>(Long.MAX_VALUE, 0) {
                @Override
                public boolean tryAdvance(java.util.function.Consumer<? super TokenChunk> action) {
                    readEntered.countDown();
                    return source.tryAdvance(action);
                }
            };
            return StreamSupport.stream(tracked, false).peek(chunk -> {
                if (!chunk.text().isEmpty()) textChunks.incrementAndGet();
                if (chunk.toolCalls() != null && !chunk.toolCalls().isEmpty()) toolChunks.incrementAndGet();
            }).onClose(() -> {
                try {
                    stream.close();
                } finally {
                    streamClosed.countDown();
                }
            });
        }
        @Override public void close() { }
    }

    private static final class Ledger implements ProviderResponseCapture.Listener {
        final List<ProviderResponseCapture.TransportLifecycleEvent> transport = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.AttemptResponse> responses = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.GenerationCompletion> completions = new CopyOnWriteArrayList<>();
        final List<ProviderResponseCapture.GenerationAbandonment> abandonments = new CopyOnWriteArrayList<>();

        @Override public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) { transport.add(event); }
        @Override public ProviderResponseCapture.Receipt responseReturned(ProviderResponseCapture.AttemptResponse event) {
            responses.add(event);
            return null;
        }
        @Override public void generationCompleted(ProviderResponseCapture.GenerationCompletion event) { completions.add(event); }
        @Override public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment event) { abandonments.add(event); }
    }
}
