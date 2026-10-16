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
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Test-only access to the package-private physical-provider resolver seam. */
public final class ProviderCanonicalAnswerLineageFixture {
    private ProviderCanonicalAnswerLineageFixture() {}

    public static CapturedTurn run(Path workspace, Path storeRoot) throws Exception {
        return run(workspace, storeRoot, "stop");
    }

    public static CapturedTurn run(Path workspace, Path storeRoot, String finishReason)
            throws Exception {
        Config config = config();
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        String durableSessionId = JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-07-21T00:00:00Z"));
        ModeController modes = new ModeController().add(new AskMode());
        if (!modes.setActive("ask")) {
            throw new IllegalStateException("ask mode was not selectable");
        }

        try (var writer = store.acquireSessionWriter(durableSessionId);
             LlmClient llm = new LlmClient(config, new FixedProviderResolver(finishReason))) {
            Context context = Context.builder(config).llm(llm).build();
            TurnProcessor processor = new TurnProcessor(modes);
            processor.addListener(new JsonTurnLogAppender(writer, durableSessionId));

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
        private final ProviderProcessLaunchReceipt receipt = providerProcessReceipt();
        private final String finishReason;

        private FixedProviderResolver(String finishReason) {
            this.finishReason = finishReason;
        }

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public ModelIdentitySnapshot modelIdentity() {
            return ModelIdentitySnapshot.builder()
                    .backend("fixture")
                    .configuredModel("provider-lineage-model")
                    .resolvedProfile(receipt.resolvedProfile())
                    .artifactState("EXACT")
                    .engineExecutableIdentity(receipt.engineExecutableSha256())
                    .servedIdentityState("MATCHED")
                    .resolution("QUALIFIED")
                    .propsState("AVAILABLE")
                    .propsResponseSha256(receipt.propsResponseSha256())
                    .chatAvailable(true)
                    .build();
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(TokenChunk.of("4"), TokenChunk.eos(finishReason));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff processBound =
                    ProviderTransportHandoff.bindProcessReceipt(
                            handoff, () -> receipt);
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request,
                    "canonical-answer-lineage-provider-body",
                    processBound,
                    () -> chatStream(request));
        }

        @Override
        public void close() {}
    }

    private static ProviderProcessLaunchReceipt providerProcessReceipt() {
        return new ProviderProcessLaunchReceipt(
                42,
                1_000,
                1_001,
                1_002,
                "0123456789abcdef0123456789abcdef",
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD,
                "5".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract
                        .QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1",
                8080,
                true,
                "6".repeat(64),
                "provider-lineage",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "7".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }
}
