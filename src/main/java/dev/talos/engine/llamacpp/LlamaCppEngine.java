package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.TokenChunk;

import java.net.http.HttpClient;
import java.util.List;
import java.util.stream.Stream;

final class LlamaCppEngine implements ModelEngine {
    static final String BACKEND = "llama_cpp";

    private final LlamaCppConfig config;
    private final LlamaCppServerManager serverManager;
    private final CompatChatClient chatClient;

    LlamaCppEngine(LlamaCppConfig config, LlamaCppServerManager serverManager, HttpClient http) {
        this.config = config;
        this.serverManager = serverManager;
        HttpClient client = http == null ? HttpClient.newHttpClient() : http;
        this.chatClient = new CompatChatClient(config.baseUrl(), config.catalogFallbackModel(), client, new ObjectMapper());
    }

    LlamaCppEngine(LlamaCppConfig config) {
        this(config,
                new LlamaCppServerManager(config, new ProcessBuilderLlamaCppProcessLauncher(), HttpClient.newHttpClient()),
                HttpClient.newHttpClient());
    }

    @Override public String id() { return BACKEND; }

    @Override
    public Capabilities caps() {
        return Capabilities.of(
                true,
                true,
                false,
                config.context(),
                true,
                true,
                true,
                true,
                true,
                true,
                config.managed());
    }

    @Override public Health health() { return serverManager.health(); }

    @Override
    public String chat(ChatRequest req) throws Exception {
        serverManager.ensureStarted();
        return chatClient.chat(req);
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        serverManager.ensureStarted();
        return chatClient.chatStream(req);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        serverManager.ensureStarted();
        return chatClient.chatStreamNonStreaming(req);
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        throw new UnsupportedOperationException("llama_cpp embeddings are not wired yet");
    }

    @Override
    public void close() {
        serverManager.close();
    }
}
