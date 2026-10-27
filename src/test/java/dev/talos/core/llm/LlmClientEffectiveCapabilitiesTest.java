package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientEffectiveCapabilitiesTest {

    @Test
    void placeholderClientPreservesConfiguredNativeToolSimulation() {
        Config config = new Config();
        config.data.put("llm", new LinkedHashMap<>(java.util.Map.of("transport", "placeholder")));
        assertTrue(new LlmClient(config).nativeToolsCurrentlyEnabled());
        assertFalse(new LlmClient(config).supportsJsonSchemaResponse());
    }

    @Test
    void schemaSupportRequiresAnEffectiveQualifiedCapabilityWithoutDispatch() throws Exception {
        AuthoritativeResolver resolver = new AuthoritativeResolver(capabilities(true));
        LlmClient client = new LlmClient(engineConfig(true), resolver);
        assertFalse(client.supportsJsonSchemaResponse());
        assertEquals(0, resolver.prepareCalls.get());
        assertEquals(null, resolver.lastRequest);
        assertTrue(client.prepareNativeTools());
        assertTrue(client.supportsJsonSchemaResponse());
        assertEquals(1, resolver.prepareCalls.get());
        assertEquals(null, resolver.lastRequest);
    }

    @Test
    void preparedChatOnlyDecisionRemovesNativeToolsFromRequest() throws Exception {
        AuthoritativeResolver resolver = new AuthoritativeResolver(capabilities(false));
        LlmClient client = new LlmClient(engineConfig(true), resolver);

        assertFalse(client.prepareNativeTools());
        client.chatFull(messages(), List.of(readSpec()));

        assertEquals(1, resolver.prepareCalls.get());
        assertEquals(List.of(), resolver.lastRequest.tools);
    }

    @Test
    void preparedQualifiedDecisionKeepsNativeToolsInRequest() throws Exception {
        AuthoritativeResolver resolver = new AuthoritativeResolver(capabilities(true));
        LlmClient client = new LlmClient(engineConfig(true), resolver);

        assertTrue(client.prepareNativeTools());
        client.chatFull(messages(), List.of(readSpec()));

        assertEquals(List.of("talos.read_file"),
                resolver.lastRequest.tools.stream().map(ToolSpec::name).toList());
    }

    @Test
    void configurationCanNarrowQualifiedNativeCapability() throws Exception {
        AuthoritativeResolver resolver = new AuthoritativeResolver(capabilities(true));
        LlmClient client = new LlmClient(engineConfig(false), resolver);

        assertFalse(client.prepareNativeTools());
        client.chatFull(messages(), List.of(readSpec()));

        assertEquals(0, resolver.prepareCalls.get());
        assertEquals(List.of(), resolver.lastRequest.tools);
    }

    @Test
    void exposesTheSelectedEnginesBoundedModelIdentity() {
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .declaredProfile("qwen3-8b")
                .resolvedProfile("qwen3-8b")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(8192)
                .build();
        AuthoritativeResolver resolver = new AuthoritativeResolver(capabilities(true), identity);
        LlmClient client = new LlmClient(engineConfig(true), resolver);

        assertEquals(identity, client.modelIdentity());
    }

    @Test
    void directCancellationEscapesEveryBegunTurnCapabilityFallbackUnchanged() {
        assertCapabilityFallbacksPropagate(
                new CancellationException("stop capability inspection"));
    }

    @Test
    void genericWrappedCancellationEscapesEveryBegunTurnCapabilityFallbackUnchanged() {
        assertCapabilityFallbacksPropagate(new RuntimeException(
                new CancellationException("stop capability inspection")));
    }

    @Test
    void typedEngineFailureWithCancellationCauseRetainsBestEffortFallbacks() {
        RuntimeException failure = new EngineException.ConnectionFailed(
                "local engine",
                new CancellationException("transport detail"));
        LlmClient client = new LlmClient(
                engineConfig(true),
                new FailingCapabilityResolver(failure));

        assertFalse(client.nativeToolsCurrentlyEnabled());
        assertEquals(ModelIdentitySnapshot.empty(), client.modelIdentity());
        assertFalse(client.supportsRequiredToolChoice());
        assertFalse(client.supportsNamedToolChoice());
        assertFalse(client.supportsJsonSchemaResponse());
        assertEquals(0, client.contextWindowDiagnostics().engineWindowTokens());
    }

    private static void assertCapabilityFallbacksPropagate(RuntimeException failure) {
        LlmClient client = new LlmClient(
                engineConfig(true),
                new FailingCapabilityResolver(failure));

        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::nativeToolsCurrentlyEnabled));
        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::modelIdentity));
        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::supportsRequiredToolChoice));
        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::supportsNamedToolChoice));
        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::supportsJsonSchemaResponse));
        assertSame(failure, assertThrows(
                RuntimeException.class,
                client::contextWindowDiagnostics));
    }

    private static Capabilities capabilities(boolean nativeTools) {
        return Capabilities.of(
                true, true, false, 8192,
                nativeTools, nativeTools, nativeTools,
                nativeTools, nativeTools, true, true);
    }

    private static Config engineConfig(boolean nativeCalling) {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        llm.put("model", "qwen3-8b");
        cfg.data.put("llm", llm);
        cfg.data.put("tools", new LinkedHashMap<>(java.util.Map.of("native_calling", nativeCalling)));
        return cfg;
    }

    private static List<ChatMessage> messages() {
        return List.of(ChatMessage.system("system"), ChatMessage.user("read README.md"));
    }

    private static ToolSpec readSpec() {
        return new ToolSpec("talos.read_file", "Read", "{}");
    }

    private static final class AuthoritativeResolver implements LlmEngineResolver {
        private final Capabilities prepared;
        private final ModelIdentitySnapshot identity;
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private volatile ChatRequest lastRequest;

        private AuthoritativeResolver(Capabilities prepared) {
            this(prepared, ModelIdentitySnapshot.empty());
        }

        private AuthoritativeResolver(Capabilities prepared, ModelIdentitySnapshot identity) {
            this.prepared = prepared;
            this.identity = identity;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Capabilities capabilities() {
            return LlmClientEffectiveCapabilitiesTest.capabilities(false);
        }

        @Override
        public Capabilities prepareCapabilities() {
            prepareCalls.incrementAndGet();
            return prepared;
        }

        @Override
        public ModelIdentitySnapshot modelIdentity() {
            return identity;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            lastRequest = request;
            return Stream.of(TokenChunk.of("ok"), TokenChunk.eos());
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingCapabilityResolver implements LlmEngineResolver {
        private final RuntimeException failure;

        private FailingCapabilityResolver(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Capabilities capabilities() {
            throw failure;
        }

        @Override
        public ModelIdentitySnapshot modelIdentity() {
            throw failure;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("capability tests must not dispatch a provider request");
        }

        @Override
        public void close() {
        }
    }
}
