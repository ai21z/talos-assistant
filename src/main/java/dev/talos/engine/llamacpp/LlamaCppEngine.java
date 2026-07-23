package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.EngineException;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.TokenChunk;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
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
        Optional<LlamaCppQualificationLaunchExpectation> expectation =
                profileService.qualificationLaunchExpectation();
        if (preparedProfile != null) {
            if (expectation.isEmpty()) return capabilities(preparedProfile);
            try {
                serverManager.requireActiveQualificationReceipt();
                return capabilities(preparedProfile);
            } catch (dev.talos.spi.EngineException.ConnectionFailed stale) {
                preparedProfile = null;
            }
        }
        if (config.managed()) profileService.requirePrelaunch();
        if (expectation.isPresent()) {
            serverManager.ensureStarted(expectation.get());
        } else {
            serverManager.ensureStarted();
        }
        try {
            LlamaCppResolvedProfileService.Snapshot snapshot = expectation.isPresent()
                    ? profileService.resolveAvailable(
                            serverManager.fetchQualificationProps(expectation.get()), true)
                    : profileService.resolveAvailable(true);
            if (snapshot.profile().resolution()
                    == LlamaCppProfileResolver.ResolutionState.REFUSED) {
                throw new IllegalStateException(
                        "llama.cpp profile refused: " + snapshot.profile().reasons());
            }
            expectation.ifPresent(exact -> serverManager.bindQualificationReceipt(
                    exact,
                    snapshot.profile().declaredAlias(),
                    snapshot.profile().resolution()
                            == LlamaCppProfileResolver.ResolutionState.QUALIFIED));
            preparedProfile = snapshot;
            return capabilities(snapshot);
        } catch (RuntimeException | Error failure) {
            if (expectation.isPresent()) serverManager.close();
            throw failure;
        }
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
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chat(req, requestLayout(), invocation.handoff());
    }

    @Override
    public String chat(ChatRequest req, ProviderTransportHandoff handoff) throws Exception {
        prepareCapabilities();
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chat(req, requestLayout(), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        prepareCapabilities();
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chatStream(req, requestLayout(), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStream(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        prepareCapabilities();
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chatStream(req, requestLayout(), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        prepareCapabilities();
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chatStreamNonStreaming(
                req, requestLayout(), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        prepareCapabilities();
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chatStreamNonStreaming(
                req, requestLayout(), invocation.handoff());
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

    private RequestMessageLayout requestLayout() {
        LlamaCppResolvedProfileService.Snapshot snapshot = preparedProfile;
        return snapshot == null
                ? RequestMessageLayout.PRESERVE_AS_WRITTEN
                : snapshot.profile().effectiveRequestLayout();
    }

    private ChatInvocation invocation(ProviderTransportHandoff handoff) {
        ProviderTransportHandoff exact = handoff == null
                ? ProviderTransportHandoff.none()
                : handoff;
        if (profileService.qualificationLaunchExpectation().isEmpty()) {
            return new ChatInvocation(chatClient, exact);
        }
        LlamaCppServerManager.QualificationTransport transport =
                serverManager.requireActiveQualificationTransport();
        CompatChatClient processClient = new CompatChatClient(
                config.baseUrl(),
                config.catalogFallbackModel(),
                transport.httpClient(),
                new ObjectMapper());
        return new ChatInvocation(
                processClient,
                bindSelectedQualificationTransport(
                        exact,
                        transport,
                        serverManager::requireActiveQualificationReceipt));
    }

    static ProviderTransportHandoff bindSelectedQualificationTransport(
            ProviderTransportHandoff handoff,
            LlamaCppServerManager.QualificationTransport selectedTransport,
            Supplier<ProviderProcessLaunchReceipt> activeReceiptSupplier) {
        LlamaCppServerManager.QualificationTransport selected = Objects.requireNonNull(
                selectedTransport, "selected qualification transport");
        ProviderProcessLaunchReceipt selectedReceipt = selected.receipt();
        Supplier<ProviderProcessLaunchReceipt> activeReceipts = Objects.requireNonNull(
                activeReceiptSupplier, "active qualification receipt supplier");
        return ProviderTransportHandoff.bindProcessReceipt(handoff, () -> {
            ProviderProcessLaunchReceipt active = Objects.requireNonNull(
                    activeReceipts.get(), "active qualification process receipt");
            if (!selectedReceipt.equals(active)) {
                throw new EngineException.ConnectionFailed(
                        "selected qualification HTTP epoch no longer owns the active process receipt",
                        null);
            }
            return active;
        });
    }

    private record ChatInvocation(
            CompatChatClient client,
            ProviderTransportHandoff handoff) {}

    private static java.nio.file.Path defaultTalosHome() {
        return java.nio.file.Path.of(System.getProperty("user.home", "."), ".talos")
                .toAbsolutePath().normalize();
    }
}
