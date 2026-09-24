package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.HostLocalityPolicy;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

final class LlamaCppEngine implements ModelEngine {
    static final String BACKEND = "llama_cpp";
    private static final String QWEN25_PREFILL_ALIAS = "qwen2.5-coder-14b";
    private static final String QWEN25_PREFILL_MODEL_SHA256 =
            "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940";
    private static final String QWEN25_PREFILL_EXECUTABLE_SHA256 =
            "ec94260223a88aa87a48fbbefa35d16a217a7df18da35eab9eef3e8790ceeb33";
    private static final String QWEN25_PREFILL_TEMPLATE_SHA256 =
            "55b2f4a26ac9ee719330a61a0c39d9e538b0e9322212f048326318cff4f8674a";

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
        prepareRequest(req);
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chat(req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
    }

    @Override
    public String chat(ChatRequest req, ProviderTransportHandoff handoff) throws Exception {
        prepareRequest(req);
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chat(req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        prepareRequest(req);
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chatStream(req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStream(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        prepareRequest(req);
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chatStream(req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        prepareRequest(req);
        ChatInvocation invocation = invocation(ProviderTransportHandoff.none());
        return invocation.client().chatStreamNonStreaming(
                req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        prepareRequest(req);
        ChatInvocation invocation = invocation(handoff);
        return invocation.client().chatStreamNonStreaming(
                req, requestLayout(), nativeToolPrefill(req), invocation.handoff());
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

    private void prepareRequest(ChatRequest request) {
        boolean denied = !Objects.requireNonNull(request, "chat request").contentRestrictions.artifactAllowed();
        if (denied) serverManager.denyOutputRetention();
        prepareCapabilities();
        if (denied) serverManager.requireOutputRetentionDenied();
    }

    private CompatChatClient.NativeToolPrefill nativeToolPrefill(ChatRequest request) {
        return nativeToolPrefill(config, preparedProfile, request);
    }

    /**
     * Selects only the demonstrated managed compatibility envelope. These are prepared
     * profile facts, not a per-request runtime-DLL attestation. The client independently
     * restricts the option to eligible required native-tool requests before serialization.
     */
    static CompatChatClient.NativeToolPrefill nativeToolPrefill(
            LlamaCppConfig config,
            LlamaCppResolvedProfileService.Snapshot snapshot,
            ChatRequest request) {
        if (config == null || !config.managed() || !config.nativeCallingRequested()
                || !config.jinja() || !HostLocalityPolicy.isLoopback(config.baseUrl())
                || snapshot == null || request == null
                || !BACKEND.equals(request.backend)
                || snapshot.propsState() != LlamaCppPropsClient.State.AVAILABLE
                || !snapshot.chatAvailable()
                || !"b9918-0512ef1e5".equals(snapshot.engineBuildInfo())) {
            return CompatChatClient.NativeToolPrefill.NONE;
        }
        String outboundModel = request.model.isBlank()
                ? config.catalogFallbackModel()
                : request.model;
        LlamaCppProfileResolver.ResolvedLlamaCppProfile profile = snapshot.profile();
        LlamaCppProfileResolver.ArtifactIdentity artifact = profile.artifactIdentity();
        LlamaCppProfileResolver.EngineFacts engine = profile.engineFacts();
        LlamaCppProfileResolver.ObservedServerFacts observed = profile.observedFacts();
        if (!QWEN25_PREFILL_ALIAS.equals(outboundModel)
                || !QWEN25_PREFILL_ALIAS.equals(config.model())
                || !QWEN25_PREFILL_ALIAS.equals(profile.declaredAlias())
                || !QWEN25_PREFILL_ALIAS.equals(profile.configuredFacts().alias())
                || !profile.configuredFacts().managed()
                || profile.resolution() != LlamaCppProfileResolver.ResolutionState.QUALIFIED
                || profile.effectiveToolMode() != LlamaCppProfileResolver.ToolMode.NATIVE
                || profile.artifactState() != LlamaCppProfileResolver.ArtifactState.EXACT
                || artifact.state() != LlamaCppProfileResolver.ArtifactState.EXACT
                || artifact.actualSize() != 8_988_110_272L
                || artifact.expectedSize() != artifact.actualSize()
                || !QWEN25_PREFILL_MODEL_SHA256.equals(artifact.actualSha256())
                || !QWEN25_PREFILL_MODEL_SHA256.equals(artifact.expectedSha256())
                || profile.servedIdentity() != LlamaCppProfileResolver.MatchState.MATCHED
                || profile.engineCompatibility() != LlamaCppProfileResolver.MatchState.MATCHED
                || engine.executableVerification() != LlamaCppProfileResolver.MatchState.MATCHED
                || !"win-x64-cuda-13.3:b9918".equals(engine.lane())
                || !QWEN25_PREFILL_EXECUTABLE_SHA256.equals(engine.executableIdentity())
                || observed.state() != LlamaCppProfileResolver.ObservationState.AVAILABLE
                || !BACKEND.equals(observed.backend())
                || observed.templateIdentity() != LlamaCppProfileResolver.MatchState.MATCHED
                || observed.toolMode() != LlamaCppProfileResolver.ToolMode.NATIVE) {
            return CompatChatClient.NativeToolPrefill.NONE;
        }
        byte[] template = observed.chatTemplate().getBytes(StandardCharsets.UTF_8);
        if (template.length != 2506) return CompatChatClient.NativeToolPrefill.NONE;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(template));
            return QWEN25_PREFILL_TEMPLATE_SHA256.equals(hash)
                    ? CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED
                    : CompatChatClient.NativeToolPrefill.NONE;
        } catch (NoSuchAlgorithmException unavailable) {
            return CompatChatClient.NativeToolPrefill.NONE;
        }
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
