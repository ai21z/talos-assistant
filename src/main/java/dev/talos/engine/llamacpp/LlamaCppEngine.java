package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.spi.ModelEngine;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.TokenChunk;

import java.net.http.HttpClient;
import java.util.List;
import java.util.stream.Stream;

final class LlamaCppEngine implements ModelEngine {
    static final String BACKEND = "llama_cpp";

    private final LlamaCppConfig config;
    private final LlamaCppServerManager serverManager;
    private final CompatChatClient chatClient;
    private final LlamaCppResolvedProfileService profileService;
    private volatile LlamaCppResolvedProfileService.Snapshot preparedProfile;

    LlamaCppEngine(LlamaCppConfig config, LlamaCppServerManager serverManager, HttpClient http) {
        this(config, serverManager, http, new LlamaCppResolvedProfileService(config, defaultTalosHome()));
    }

    LlamaCppEngine(
            LlamaCppConfig config,
            LlamaCppServerManager serverManager,
            HttpClient http,
            LlamaCppResolvedProfileService profileService) {
        this.config = config;
        this.serverManager = serverManager;
        this.profileService = profileService;
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
        LlamaCppResolvedProfileService.Snapshot snapshot = preparedProfile;
        if (snapshot == null) snapshot = profileService.inspectLocal();
        return capabilities(snapshot);
    }

    @Override
    public synchronized Capabilities prepareCapabilities() {
        if (preparedProfile != null) return capabilities(preparedProfile);
        if (config.managed()) profileService.requirePrelaunch();
        serverManager.ensureStarted();
        LlamaCppResolvedProfileService.Snapshot snapshot = profileService.resolveAvailable(true);
        if (snapshot.profile().resolution() == LlamaCppProfileResolver.ResolutionState.REFUSED) {
            throw new IllegalStateException("llama.cpp profile refused: " + snapshot.profile().reasons());
        }
        preparedProfile = snapshot;
        return capabilities(snapshot);
    }

    @Override public Health health() { return serverManager.health(); }

    @Override
    public ModelIdentitySnapshot modelIdentity() {
        LlamaCppResolvedProfileService.Snapshot snapshot = preparedProfile;
        if (snapshot == null) snapshot = profileService.inspectLocal();
        return snapshot.identity();
    }

    @Override
    public String chat(ChatRequest req) throws Exception {
        prepareCapabilities();
        return chatClient.chat(req);
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        prepareCapabilities();
        return chatClient.chatStream(req);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        prepareCapabilities();
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

    private Capabilities capabilities(LlamaCppResolvedProfileService.Snapshot snapshot) {
        LlamaCppProfileResolver.ResolvedLlamaCppProfile profile = snapshot.profile();
        boolean nativeTools = profile.resolution() == LlamaCppProfileResolver.ResolutionState.QUALIFIED
                && profile.effectiveToolMode() == LlamaCppProfileResolver.ToolMode.NATIVE;
        return Capabilities.of(
                snapshot.chatAvailable(),
                snapshot.chatAvailable(),
                false,
                profile.effectiveContextTokens(),
                nativeTools,
                nativeTools,
                nativeTools,
                nativeTools,
                nativeTools,
                true,
                config.managed());
    }

    private static java.nio.file.Path defaultTalosHome() {
        return java.nio.file.Path.of(System.getProperty("user.home", "."), ".talos")
                .toAbsolutePath().normalize();
    }
}
