package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ProviderTransportTokenStream;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.impl.FileEditTool;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Test-only access to the package-private observed resolver; no production visibility change. */
public final class QuotedHtmlProviderFixture {
    private QuotedHtmlProviderFixture() {}

    public static LlmClient.StreamResult response(String prose, NativeToolCall proposal, TokenChunk terminal) {
        Config config = new Config(null);
        config.data.put("llm", Map.of("transport", "engine", "default_backend", "test"));
        config.data.put("limits", Map.of("response_max_chars", 10_000,
                "llm_timeout_ms", 5_000L, "llm_idle_ms", 0L));
        List<TokenChunk> chunks = List.of(TokenChunk.of(prose),
                TokenChunk.ofToolCalls(List.of(proposal)), terminal);
        try (LlmClient client = new LlmClient(config, new ObservedResolver(chunks, proposal.name()))) {
            return client.chatFull(List.of(ChatMessage.user("Fix the selector in script.js.")),
                    List.of(new ToolSpec(proposal.name(), "Edit a file",
                            new FileEditTool().descriptor().parametersSchema())),
                    ChatRequestControls.defaults().withToolAuthorityMode(
                            ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        }
    }

    private record ObservedResolver(List<TokenChunk> chunks, String toolName) implements LlmEngineResolver {
        @Override public void select(String backend, String model) {}
        @Override public Capabilities capabilities() { return Capabilities.of(true, true, false, 32_768); }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) { return chunks.stream(); }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) {
            ProviderTransportAttempt transport = handoff.admitTransport(
                    new ProviderTransportHandoff.DispatchEvidence("sha256:" + "a".repeat(64), List.of(toolName)));
            try {
                Stream<TokenChunk> source = transport.invokeSend(() -> chatStream(request));
                transport.responseHeadersObserved();
                transport.responseBodyOpened(source::close);
                return ProviderTransportTokenStream.track(source, transport);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        @Override public void close() {}
    }
}
