package dev.talos.core.engine;

import dev.talos.core.Config;
import dev.talos.spi.*;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EngineRegistryManagedSwitchTest {
    @Test
    void replacementReceivesNewConfigurationAndStopsOldEngineBeforePreparation() throws Exception {
        Fixture fixture = new Fixture();
        ModelEngine old = fixture.registry.engine();
        EngineConfig target = target("second");
        Capabilities result = fixture.registry.switchManagedModel(target, "second");
        assertEquals(List.of("create:first", "create:second", "close:first", "prepare:second"), fixture.events);
        assertSame(target, fixture.provider.configurations.get(1));
        assertNotSame(old, fixture.registry.engine());
        assertEquals(4096, result.contextWindow());
        assertEquals("first", fixture.cfg.data().get("marker"));
        assertFalse(fixture.registry.selectionUnavailable());
    }

    @Test
    void failedOldShutdownRetainsOwnershipAndBlocksRequests() throws Exception {
        Fixture fixture = new Fixture();
        FakeEngine old = (FakeEngine) fixture.registry.engine();
        old.failClose = true;
        assertThrows(IllegalStateException.class, () -> fixture.registry.switchManagedModel(target("second"), "second"));
        assertFalse(fixture.events.contains("prepare:second"));
        assertTrue(fixture.registry.selectionUnavailable());
        assertThrows(IllegalStateException.class, fixture.registry::engine);
        old.failClose = false;
        fixture.registry.close();
        assertEquals(2, Collections.frequency(fixture.events, "close:first"));
    }

    @Test
    void genericBackendChangeCannotDiscardUnsettledOwnedEngine() throws Exception {
        Fixture fixture = new Fixture();
        FakeEngine old = (FakeEngine) fixture.registry.engine();
        old.failClose = true;
        assertThrows(IllegalStateException.class, () -> fixture.registry.select("ollama", "other"));
        assertTrue(fixture.registry.selectionUnavailable());
        assertThrows(IllegalStateException.class, fixture.registry::engine);
        old.failClose = false;
        fixture.registry.switchManagedModel(target("second"), "second");
        assertFalse(fixture.registry.selectionUnavailable());
    }

    @Test
    void explicitManagedReturnFromAnotherBackendUsesTargetConfiguration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.engine();
        fixture.registry.select("ollama", "other");
        fixture.registry.switchManagedModel(target("second"), "second");
        assertEquals("second", fixture.registry.engine().modelIdentity().configuredModel());
        assertEquals(1, Collections.frequency(fixture.events, "close:first"));
    }

    @Test
    void failedReadinessClosesCandidateAndDoesNotRestartPreviousModel() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.engine();
        fixture.provider.failPrepare = true;
        assertThrows(IllegalStateException.class, () -> fixture.registry.switchManagedModel(target("second"), "second"));
        assertEquals(List.of("create:first", "create:second", "close:first", "prepare:second", "close:second"), fixture.events);
        assertTrue(fixture.registry.selectionUnavailable());
        assertThrows(IllegalStateException.class, fixture.registry::engine);
        fixture.provider.failPrepare = false;
        fixture.registry.switchManagedModel(target("third"), "third");
        assertEquals("third", fixture.registry.engine().modelIdentity().configuredModel());
    }

    @Test
    void failedCandidateShutdownRetainsItsOwnershipForExplicitRecovery() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.engine();
        fixture.provider.failPrepare = true;
        fixture.provider.failClose = true;
        Exception error = assertThrows(IllegalStateException.class,
                () -> fixture.registry.switchManagedModel(target("second"), "second"));
        assertEquals(1, error.getSuppressed().length);
        assertThrows(IllegalStateException.class, fixture.registry::engine);
        fixture.provider.last.failClose = false;
        fixture.registry.close();
        assertEquals(2, Collections.frequency(fixture.events, "close:second"));
    }

    @Test
    void healthyEndpointWithoutObservedIdentityDoesNotPass() throws Exception {
        for (String badState : List.of("UNAVAILABLE", "MISMATCHED", "WRONG_ALIAS", "REFUSED")) {
            Fixture fixture = new Fixture();
            fixture.registry.engine();
            fixture.provider.identityState = badState;
            assertThrows(IllegalStateException.class,
                    () -> fixture.registry.switchManagedModel(target("second"), "second"), badState);
            assertTrue(fixture.registry.selectionUnavailable(), badState);
            assertTrue(fixture.events.contains("close:second"), badState);
        }
    }

    @Test
    void customMatchedModelCanRemainUnverified() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.switchManagedModel(target("custom"), "custom");
        assertEquals("UNVERIFIED", fixture.registry.engine().modelIdentity().resolution());
    }

    @Test
    void explicitSameSelectionReplacesCachedIdentityWithFreshPreparation() throws Exception {
        Fixture fixture = new Fixture();
        EngineConfig target = target("second");
        fixture.registry.switchManagedModel(target, "second");
        ModelEngine engine = fixture.registry.engine();
        fixture.registry.switchManagedModel(target, "second");
        assertNotSame(engine, fixture.registry.engine());
        assertEquals(2, fixture.provider.configurations.size());
        assertTrue(fixture.events.contains("close:second"));
    }

    @Test
    void liveStreamPreventsAnySwitchUntilClosed() throws Exception {
        Fixture fixture = new Fixture();
        Stream<TokenChunk> stream = fixture.registry.chatStream(null, ProviderTransportHandoff.none(), false);
        assertThrows(IllegalStateException.class,
                () -> fixture.registry.switchManagedModel(target("second"), "second"));
        assertEquals(List.of("create:first"), fixture.events);
        assertFalse(fixture.registry.selectionUnavailable());
        stream.close();
        fixture.registry.switchManagedModel(target("second"), "second");
        assertEquals("second", fixture.registry.engine().modelIdentity().configuredModel());
    }

    @Test
    void failedStreamOpeningReleasesSelectionLease() throws Exception {
        Fixture fixture = new Fixture();
        fixture.provider.failStream = true;
        assertThrows(IllegalStateException.class,
                () -> fixture.registry.chatStream(null, ProviderTransportHandoff.none(), true));
        fixture.registry.switchManagedModel(target("second"), "second");
        assertFalse(fixture.registry.selectionUnavailable());
    }

    @Test
    void externalEndpointIsNotReplaced() throws Exception {
        Fixture fixture = new Fixture();
        fixture.cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "connect_only")));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.registry.switchManagedModel(target("second"), "second"));
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void aliasAndConfigurationMustAgreeBeforeAnyLifecycleWork() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.registry.switchManagedModel(target("second"), "other"));
        assertTrue(fixture.events.isEmpty());
    }

    private static EngineConfig target(String alias) {
        return () -> Map.of("engines", Map.of("llama_cpp", Map.of(
                "mode", "managed", "model", alias, "model_path", alias + ".gguf")));
    }

    private static final class Fixture {
        final Config cfg = new Config();
        final List<String> events = new ArrayList<>();
        final Provider provider = new Provider(events);
        final EngineRegistry registry;
        Fixture() throws Exception {
            cfg.data.put("llm", Map.of("default_backend", "llama_cpp", "model", "first"));
            cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "first")));
            cfg.data.put("marker", "first");
            registry = new EngineRegistry(cfg);
            var field = EngineRegistry.class.getDeclaredField("providers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") var providers = (Map<String, ModelEngineProvider>) field.get(registry);
            providers.put("llama_cpp", provider);
        }
    }

    private static final class Provider implements ModelEngineProvider {
        final List<String> events;
        final List<EngineConfig> configurations = new ArrayList<>();
        boolean failPrepare, failClose, failStream;
        String identityState = "MATCHED";
        FakeEngine last;
        Provider(List<String> events) { this.events = events; }
        public String id() { return "llama_cpp"; }
        public ModelEngine create(EngineConfig cfg) {
            configurations.add(cfg);
            String alias = Objects.toString(dev.talos.core.CfgUtil.map(
                    dev.talos.core.CfgUtil.map(cfg.data().get("engines")).get("llama_cpp")).get("model"));
            events.add("create:" + alias);
            last = new FakeEngine(alias, this);
            last.failClose = failClose;
            return last;
        }
    }

    private static final class FakeEngine implements ModelEngine {
        final String alias;
        final Provider provider;
        boolean failClose;
        FakeEngine(String alias, Provider provider) { this.alias = alias; this.provider = provider; }
        public String id() { return "llama_cpp"; }
        public Capabilities caps() { return Capabilities.of(true, true, false, 4096); }
        public Capabilities prepareCapabilities() {
            provider.events.add("prepare:" + alias);
            if (provider.failPrepare) throw new IllegalStateException("fixture readiness failure");
            return caps();
        }
        public ModelIdentitySnapshot modelIdentity() {
            return ModelIdentitySnapshot.builder().backend("llama_cpp")
                    .configuredModel("WRONG_ALIAS".equals(provider.identityState) ? "other" : alias)
                    .propsState("UNAVAILABLE".equals(provider.identityState) ? "UNAVAILABLE" : "AVAILABLE")
                    .servedIdentityState("MISMATCHED".equals(provider.identityState) ? "MISMATCHED" : "MATCHED")
                    .resolution("REFUSED".equals(provider.identityState) ? "REFUSED" : "UNVERIFIED")
                    .chatAvailable(true).build();
        }
        public Health health() { return Health.ok("fixture", true); }
        public String chat(ChatRequest request) {
            if (provider.failStream) throw new IllegalStateException("fixture stream failure");
            return "fixture";
        }
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (provider.failStream) throw new IllegalStateException("fixture stream failure");
            return Stream.of(TokenChunk.eos());
        }
        public EmbeddingResult embed(List<String> text) { throw new UnsupportedOperationException(); }
        public void close() {
            provider.events.add("close:" + alias);
            if (failClose) throw new IllegalStateException("fixture unsettled shutdown");
        }
    }
}
