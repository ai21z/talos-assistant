package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP is real and loopback-only. Process capture and model responses are synthetic. */
class LlamaCppArtifactRetentionTest {
    private static final String PRIVATE_CONTENT = "restricted-document-fixture-6418";
    private static final ContentRestrictions NO_ARTIFACTS =
            new ContentRestrictions(true, false, true, true);

    @TempDir Path tempDir;
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void closeFixtures() {
        ContentRestrictionScope.end();
        fixtures.forEach(Fixture::close);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void eachRouteSuppressesWarmOwnedCaptureBeforeSendingTypedRestrictedContent(Route route)
            throws Exception {
        Fixture fixture = fixture(true, true);
        fixture.engine.prepareCapabilities();
        assertNotNull(fixture.logPaths.getFirst());
        ChatRequest request = scopedRequest(NO_ARTIFACTS);
        assertTrue(ContentRestrictionScope.current().artifactAllowed());
        AtomicInteger handoffs = new AtomicInteger();

        assertEquals("ok", route.invoke(fixture.engine, request, handoffs::incrementAndGet));

        assertEquals(1, fixture.bodies.size());
        assertTrue(fixture.bodies.getFirst().contains(PRIVATE_CONTENT));
        assertEquals(List.of(true), fixture.suppressedAtDispatch);
        assertTrue(fixture.current.suppressionCalls > 0);
        assertEquals(List.of(), fixture.current.retainedOutput);
        assertEquals(route.hasHandoff ? 1 : 0, handoffs.get());
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void connectOnlyRejectsRestrictedRequestsBeforeAnyHttpOrHandoff(Route route)
            throws Exception {
        Fixture fixture = fixture(false, true);
        AtomicInteger handoffs = new AtomicInteger();

        assertThrows(ProviderTransportHandoff.ArtifactRetentionRejected.class,
                () -> route.invoke(fixture.engine, scopedRequest(NO_ARTIFACTS),
                        handoffs::incrementAndGet));

        assertEquals(0, fixture.httpCalls.get());
        assertEquals(0, handoffs.get());
        assertTrue(fixture.logPaths.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unrestrictedAndHistoryOnlyRestrictionsKeepOrdinaryCapture(boolean historyAllowed)
            throws Exception {
        Fixture fixture = fixture(true, true);
        ChatRequest request = historyAllowed
                ? request()
                : scopedRequest(new ContentRestrictions(true, true, false, true));

        assertEquals("ok", fixture.engine.chat(request));

        assertNotNull(fixture.logPaths.getFirst());
        assertEquals(0, fixture.current.suppressionCalls);
        assertEquals(List.of(false), fixture.suppressedAtDispatch);
        assertEquals(1, fixture.current.retainedOutput.size());
        assertTrue(fixture.current.retainedOutput.getFirst().contains(PRIVATE_CONTENT));
    }

    @Test
    void firstRestrictedRequestDisablesRetentionBeforeLaunch() throws Exception {
        Fixture fixture = fixture(true, true);

        assertEquals("ok", fixture.engine.chat(scopedRequest(NO_ARTIFACTS)));

        assertEquals(1, fixture.logPaths.size());
        assertNull(fixture.logPaths.getFirst());
        assertEquals(List.of(true), fixture.suppressedAtDispatch);
        assertTrue(fixture.current.retainedOutput.isEmpty());
    }

    @Test
    void requestRestrictionsFromMessagesSurviveScopeEnd() throws Exception {
        Fixture fixture = fixture(true, true);
        ChatRequest request = new ChatRequest("llama_cpp", "retention-fixture", "", "",
                List.of(), Duration.ofSeconds(5),
                List.of(ChatMessage.user(PRIVATE_CONTENT).withContentRestrictions(NO_ARTIFACTS)));

        assertFalse(request.contentRestrictions.artifactAllowed());
        assertEquals("ok", fixture.engine.chat(request));
        assertEquals(List.of(true), fixture.suppressedAtDispatch);
    }

    @Test
    void denialRemainsActiveForLaterPermissiveRequestsAndReplacementProcesses() throws Exception {
        Fixture fixture = fixture(true, true);
        fixture.engine.prepareCapabilities();
        CaptureProcess first = fixture.current;
        assertEquals("ok", fixture.engine.chat(scopedRequest(NO_ARTIFACTS)));
        assertEquals("ok", fixture.engine.chat(request()));

        first.destroy();
        fixture.manager.ensureStarted();
        fixture.manager.requireOutputRetentionDenied();
        assertEquals("ok", fixture.engine.chat(request()));

        assertEquals(2, fixture.logPaths.size());
        assertNotNull(fixture.logPaths.getFirst());
        assertNull(fixture.logPaths.getLast());
        assertEquals(List.of(true, true, true), fixture.suppressedAtDispatch);
        assertTrue(first.retainedOutput.isEmpty());
        assertTrue(fixture.current.retainedOutput.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void processWithoutSuppressionCapabilityIsRejectedBeforeDispatch(boolean alreadyPrepared)
            throws Exception {
        Fixture fixture = fixture(true, false);
        if (alreadyPrepared) fixture.engine.prepareCapabilities();
        int preparationCalls = fixture.httpCalls.get();
        AtomicInteger handoffs = new AtomicInteger();

        assertThrows(ProviderTransportHandoff.ArtifactRetentionRejected.class,
                () -> fixture.engine.chat(scopedRequest(NO_ARTIFACTS), handoffs::incrementAndGet));

        assertEquals(preparationCalls, fixture.httpCalls.get());
        assertEquals(0, handoffs.get());
        assertTrue(fixture.bodies.isEmpty());
        if (!alreadyPrepared) {
            assertNull(fixture.logPaths.getFirst());
            assertFalse(fixture.current.isAlive());
        }
    }

    @Test
    void suppressionFailureHasTypedNonTransientIdentityAndPreventsDispatch() throws Exception {
        Fixture fixture = fixture(true, true);
        fixture.engine.prepareCapabilities();
        fixture.current.failSuppression = true;
        int preparationCalls = fixture.httpCalls.get();
        AtomicInteger handoffs = new AtomicInteger();

        Throwable failure = assertThrows(ProviderTransportHandoff.ArtifactRetentionRejected.class,
                () -> fixture.engine.chat(scopedRequest(NO_ARTIFACTS), handoffs::incrementAndGet));

        assertFalse(failure instanceof EngineException.Transient);
        assertEquals(preparationCalls, fixture.httpCalls.get());
        assertEquals(0, handoffs.get());
        assertTrue(fixture.bodies.isEmpty());
    }

    private Fixture fixture(boolean managed, boolean suppressionSupported) throws IOException {
        Fixture fixture = new Fixture(managed, suppressionSupported);
        fixtures.add(fixture);
        return fixture;
    }

    private static ChatRequest request() {
        return new ChatRequest("llama_cpp", "retention-fixture", "", PRIVATE_CONTENT,
                List.of(), Duration.ofSeconds(5));
    }

    private static ChatRequest scopedRequest(ContentRestrictions restrictions) {
        ContentRestrictionScope.begin();
        try {
            ContentRestrictionScope.restrict(restrictions);
            return request();
        } finally {
            ContentRestrictionScope.end();
        }
    }

    private enum Route {
        CHAT(false), CHAT_HANDOFF(true), STREAM(false), STREAM_HANDOFF(true),
        NON_STREAMING(false), NON_STREAMING_HANDOFF(true);

        private final boolean hasHandoff;

        Route(boolean hasHandoff) { this.hasHandoff = hasHandoff; }

        String invoke(LlamaCppEngine engine, ChatRequest request, ProviderTransportHandoff handoff)
                throws Exception {
            if (this == CHAT) return engine.chat(request);
            if (this == CHAT_HANDOFF) return engine.chat(request, handoff);
            try (Stream<TokenChunk> chunks = switch (this) {
                case STREAM -> engine.chatStream(request);
                case STREAM_HANDOFF -> engine.chatStream(request, handoff);
                case NON_STREAMING -> engine.chatStreamNonStreaming(request);
                case NON_STREAMING_HANDOFF -> engine.chatStreamNonStreaming(request, handoff);
                default -> throw new AssertionError(this);
            }) {
                return chunks.map(TokenChunk::text).filter(text -> text != null)
                        .reduce("", String::concat);
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger httpCalls = new AtomicInteger();
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private final List<Boolean> suppressedAtDispatch = new CopyOnWriteArrayList<>();
        private final List<Path> logPaths = new ArrayList<>();
        private final LlamaCppServerManager manager;
        private final LlamaCppEngine engine;
        private volatile CaptureProcess current;

        Fixture(boolean managed, boolean suppressionSupported) throws IOException {
            Path directory = Files.createTempDirectory(tempDir, "retention-");
            Path executable = Files.writeString(directory.resolve("llama-server.fixture"), "fixture");
            Path model = Files.writeString(directory.resolve("model.gguf"), "fixture");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                httpCalls.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response;
                boolean streaming = false;
                if (exchange.getRequestURI().getPath().equals("/v1/chat/completions")) {
                    bodies.add(body);
                    CaptureProcess process = current;
                    boolean suppressed = process != null && process.suppressed;
                    suppressedAtDispatch.add(suppressed);
                    if (process != null && !suppressed) process.retainedOutput.add(body);
                    streaming = new ObjectMapper().readTree(body).path("stream").asBoolean();
                    response = streaming
                            ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n"
                            : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                                    + "\"finish_reason\":\"stop\"}]}";
                } else if (exchange.getRequestURI().getPath().equals("/props")) {
                    response = new ObjectMapper().writeValueAsString(Map.of(
                            "default_generation_settings", Map.of("n_ctx", 8192),
                            "model_path", model.toString(),
                            "chat_template", "fixture template",
                            "chat_template_caps", Map.of("supports_tools", false,
                                    "supports_tool_calls", false),
                            "modalities", Map.of("vision", false),
                            "build_info", "fixture-build"));
                } else {
                    response = "{}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            LlamaCppConfig config = new LlamaCppConfig(
                    managed ? LlamaCppConfig.Mode.MANAGED : LlamaCppConfig.Mode.CONNECT_ONLY,
                    executable.toString(), model.toString(), "", "", "", "retention-fixture",
                    "http://127.0.0.1", server.getAddress().getPort(), 8192,
                    false, true, "", "", List.of(), false, false);
            manager = SyntheticManagedServer.manager(config, (command, logPath) -> {
                logPaths.add(logPath);
                current = suppressionSupported ? new SuppressibleProcess() : new CaptureProcess();
                current.suppressed = logPath == null;
                return current;
            }, HttpClient.newHttpClient(), Duration.ofSeconds(2), Duration.ofMillis(5),
                    directory.resolve("logs"));
            LlamaCppResolvedProfileService profile = new LlamaCppResolvedProfileService(
                    config, directory,
                    (path, alias, expected, home, verify) -> new LlamaCppProfileResolver.ArtifactIdentity(
                            LlamaCppProfileResolver.ArtifactState.CUSTOM, model.toString(),
                            -1, 7, "", "", "fixture"),
                    new LlamaCppPropsClient()::fetch,
                    (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                            "", "", LlamaCppProfileResolver.MatchState.UNVERIFIED));
            engine = new LlamaCppEngine(config, manager, HttpClient.newHttpClient(), profile);
        }

        @Override
        public void close() {
            try {
                manager.close();
            } finally {
                server.stop(0);
            }
        }
    }

    private static class CaptureProcess implements LlamaCppProcess,
            LlamaCppProcessTerminationOwner.Target {
        private final LlamaCppProcessTerminationOwner.ProcessIdentity identity =
                new LlamaCppProcessTerminationOwner.ProcessIdentity(1L, 0L,
                        LlamaCppProcessTerminationOwner.IdentityAuthority.DIRECT_PROCESS_CAPABILITY);
        // Adoption captures the live process tree immediately.
        private boolean alive = true;
        private final LlamaCppProcessTerminationOwner.Lease lease =
                new LlamaCppProcessTerminationOwner().adopt(this);
        private final List<String> retainedOutput = new CopyOnWriteArrayList<>();
        volatile boolean suppressed;
        boolean failSuppression;
        int suppressionCalls;

        @Override public LlamaCppProcessTerminationOwner.ProcessIdentity identity() { return identity; }
        @Override public boolean identityStillMatches() { return true; }
        @Override public boolean isAlive() { return alive; }
        @Override public boolean outputCaptureHealthy() { return true; }
        @Override public List<LlamaCppProcessTerminationOwner.Target> descendants() { return List.of(); }
        @Override public void destroy() { alive = false; }
        @Override public void destroyForcibly() { alive = false; }
        @Override public boolean waitFor(Duration timeout) { return !alive; }
        @Override public LlamaCppProcessTerminationOwner.Lease terminationLease() { return lease; }
    }

    private static final class SuppressibleProcess extends CaptureProcess {
        @Override
        public boolean suppressOutputRetention() {
            suppressionCalls++;
            if (failSuppression) throw new IllegalStateException("synthetic sink-close failure");
            suppressed = true;
            return true;
        }
    }
}
