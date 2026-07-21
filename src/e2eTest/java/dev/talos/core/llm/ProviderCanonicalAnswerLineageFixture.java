package dev.talos.core.llm;

import dev.talos.cli.modes.AskMode;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.JsonTurnLogAppender;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Test-only access to the package-private physical-provider resolver seam. */
public final class ProviderCanonicalAnswerLineageFixture {
    private ProviderCanonicalAnswerLineageFixture() {}

    public static CapturedTurn run(Path workspace, Path storeRoot) throws Exception {
        Config config = config();
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        String durableSessionId = "canonical-provider-lineage";
        ModeController modes = new ModeController().add(new AskMode());
        if (!modes.setActive("ask")) {
            throw new IllegalStateException("ask mode was not selectable");
        }

        try (LlmClient llm = new LlmClient(config, new FixedProviderResolver())) {
            Context context = Context.builder(config).llm(llm).build();
            TurnProcessor processor = new TurnProcessor(modes);
            processor.addListener(new JsonTurnLogAppender(store, durableSessionId));

            TurnResult result = processor.process(
                    new Session(workspace, config),
                    "What is two plus two?",
                    context);
            List<TurnRecord> records = store.loadTurns(durableSessionId);
            if (records.size() != 1) {
                throw new IllegalStateException(
                        "expected one durable turn record, found " + records.size());
            }
            String displayText = result.result() instanceof Result.Ok ok
                    ? ok.text
                    : result.result().toString();
            return new CapturedTurn(
                    displayText,
                    records.getFirst().assistantText(),
                    result.audit().localTrace());
        }
    }

    private static Config config() {
        Config config = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "fixture");
        llm.put("model", "provider-lineage-model");
        config.data.put("llm", llm);
        config.data.put("limits", Map.of(
                "response_max_chars", 1_000,
                "llm_timeout_ms", 5_000L,
                "llm_idle_ms", 0L));
        return config;
    }

    public record CapturedTurn(
            String displayText,
            String persistedAnswer,
            LocalTurnTrace trace) {}

    private static final class FixedProviderResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(TokenChunk.of("4"), TokenChunk.eos("stop"));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(
                    handoff,
                    "canonical-answer-lineage-provider-body",
                    request.tools);
            return chatStream(request);
        }

        @Override
        public void close() {}
    }
}
