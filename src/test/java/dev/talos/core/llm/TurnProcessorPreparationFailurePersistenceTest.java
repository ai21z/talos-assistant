package dev.talos.core.llm;

import dev.talos.cli.modes.AskMode;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnProcessorPreparationFailurePersistenceTest {

    @Test
    void realAskCapabilityPreparationFailurePersistsPreGenerationStage(@TempDir Path dir) {
        Config config = config();
        LlmClient client = new LlmClient(config, new ThrowingPreparationResolver());
        Context context = Context.builder(config).llm(client).build();
        ModeController modes = new ModeController().add(new AskMode());
        assertTrue(modes.setActive("ask"));
        JsonSessionStore store = new JsonSessionStore(dir.resolve("sessions"));
        TurnProcessor processor = new TurnProcessor(modes);
        processor.addListener(new JsonTurnLogAppender(store, "preparation"));

        assertThrows(IllegalStateException.class, () -> processor.process(
                new Session(dir, config),
                "Explain the purpose of README.md.",
                context));

        TurnRecord row = store.loadTurns("preparation").getFirst();
        LocalTurnTrace trace = store.loadTrace("preparation", row.traceId()).orElseThrow();
        var settlement = trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals("ENGINE_CAPABILITY_PREPARATION", settlement.data().get("stage"));
        assertTrue(trace.events().stream().noneMatch(event ->
                event.type().startsWith("GENERATION_")));
    }

    private static Config config() {
        Config config = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        llm.put("model", "test-model");
        config.data.put("llm", llm);
        config.data.put("tools", new LinkedHashMap<>(java.util.Map.of(
                "native_calling", true)));
        return config;
    }

    private static final class ThrowingPreparationResolver implements LlmEngineResolver {
        @Override public void select(String backend, String model) {}
        @Override public boolean capabilitiesAuthoritative() { return true; }
        @Override public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 8192);
        }
        @Override public Capabilities prepareCapabilities() {
            throw new IllegalStateException("managed preparation failed");
        }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("generation must not start");
        }
        @Override public void close() {}
    }
}
