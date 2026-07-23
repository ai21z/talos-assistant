package dev.talos.engine.llamacpp;

import dev.talos.engine.llamacpp.profile.LlamaCppArtifactVerificationStore;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.ModelIdentitySnapshot;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Collects configured, artifact, engine, and live server facts into one resolved profile decision. */
public final class LlamaCppResolvedProfileService {
    @FunctionalInterface
    interface ArtifactSource {
        LlamaCppProfileResolver.ArtifactIdentity resolve(
                Path path,
                String alias,
                LlamaCppModelCatalog.ModelArtifact expected,
                Path talosHome,
                boolean verify) throws Exception;
    }

    @FunctionalInterface
    interface PropsSource {
        LlamaCppPropsClient.Result fetch(URI baseUri);
    }

    @FunctionalInterface
    interface EngineFactsSource {
        LlamaCppProfileResolver.EngineFacts resolve(
                LlamaCppConfig config,
                Optional<LlamaCppModelCatalog.Profile> declared,
                LlamaCppPropsClient.Props observed,
                boolean verify);
    }

    @FunctionalInterface
    interface CandidateSource {
        Optional<LlamaCppQualificationCandidateLoader.LoadedCandidate> resolve(
                LlamaCppConfig config,
                Path talosHome);
    }

    public record Snapshot(
            LlamaCppProfileResolver.ResolvedLlamaCppProfile profile,
            LlamaCppPropsClient.State propsState,
            String propsReason,
            String propsResponseSha256,
            String observedModelFile,
            String engineBuildInfo,
            boolean chatAvailable,
            boolean qualificationCandidate) {

        public Snapshot {
            Objects.requireNonNull(profile, "profile");
            propsState = propsState == null ? LlamaCppPropsClient.State.UNAVAILABLE : propsState;
            propsReason = bounded(propsReason, 200);
            propsResponseSha256 = clean(propsResponseSha256).toLowerCase(Locale.ROOT);
            observedModelFile = fileNameOnly(observedModelFile);
            engineBuildInfo = bounded(engineBuildInfo, 200);
        }

        public ModelIdentitySnapshot identity() {
            String resolvedProfile = profile.resolution() == LlamaCppProfileResolver.ResolutionState.QUALIFIED
                    ? profile.declaredAlias()
                    : "";
            List<String> reasons = new ArrayList<>(
                    profile.reasons().stream().map(Enum::name).toList());
            if (qualificationCandidate) reasons.add("QUALIFICATION_CANDIDATE");
            return ModelIdentitySnapshot.builder()
                    .backend(LlamaCppEngine.BACKEND)
                    .configuredModel(profile.configuredFacts().alias())
                    .configuredModelFile(profile.configuredFacts().canonicalModelPath())
                    .declaredProfile(profile.declaredAlias())
                    .resolvedProfile(resolvedProfile)
                    .observedModelFile(observedModelFile)
                    .artifactState(profile.artifactState().name())
                    .artifactVerificationIdentity(profile.artifactIdentity().verificationIdentity())
                    .engineLane(profile.engineFacts().lane())
                    .engineExecutableIdentity(profile.engineFacts().executableIdentity())
                    .servedIdentityState(profile.servedIdentity().name())
                    .resolution(profile.resolution().name())
                    .effectiveToolMode(profile.effectiveToolMode().name())
                    .effectiveRequestLayout(profile.effectiveRequestLayout().name())
                    .effectiveContextTokens(profile.effectiveContextTokens())
                    .disposition(profile.disposition().label())
                    .propsState(propsState.name())
                    .propsResponseSha256(propsResponseSha256)
                    .engineBuildInfo(engineBuildInfo)
                    .chatAvailable(chatAvailable)
                    .reasonCodes(reasons)
                    .build();
        }
    }

    private static final LlamaCppPropsClient.Props NO_PROPS = new LlamaCppPropsClient.Props(
            "", "", false, false, false, false, 0, "", "");

    private final LlamaCppConfig config;
    private final Path talosHome;
    private final ArtifactSource artifacts;
    private final PropsSource props;
    private final EngineFactsSource engines;
    private final Optional<LlamaCppModelCatalog.Profile> catalogProfile;
    private final Optional<LlamaCppQualificationCandidateLoader.LoadedCandidate> qualificationCandidate;

    public LlamaCppResolvedProfileService(LlamaCppConfig config, Path talosHome) {
        this(config, talosHome, defaultArtifactSource(), new LlamaCppPropsClient()::fetch,
                LlamaCppResolvedProfileService::defaultEngineFacts,
                (candidateConfig, home) -> new LlamaCppQualificationCandidateLoader()
                        .loadFromSystemProperties(candidateConfig, home));
    }

    LlamaCppResolvedProfileService(
            LlamaCppConfig config,
            Path talosHome,
            ArtifactSource artifacts,
            PropsSource props,
            EngineFactsSource engines) {
        this(config, talosHome, artifacts, props, engines, (candidateConfig, home) -> Optional.empty());
    }

    LlamaCppResolvedProfileService(
            LlamaCppConfig config,
            Path talosHome,
            ArtifactSource artifacts,
            PropsSource props,
            EngineFactsSource engines,
            CandidateSource candidates) {
        this.config = Objects.requireNonNull(config, "config");
        this.talosHome = (talosHome == null ? defaultTalosHome() : talosHome).toAbsolutePath().normalize();
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.props = Objects.requireNonNull(props, "props");
        this.engines = Objects.requireNonNull(engines, "engines");
        CandidateSource candidateSource = Objects.requireNonNull(candidates, "candidates");
        this.catalogProfile = LlamaCppModelCatalog.byAlias(config.model());
        this.qualificationCandidate = Objects.requireNonNullElseGet(
                candidateSource.resolve(config, this.talosHome), Optional::empty);
        if (catalogProfile.isPresent() && qualificationCandidate.isPresent()) {
            requireCatalogCandidateCompatibility(
                    catalogProfile.get(), qualificationCandidate.get().profile());
        }
    }

    private static void requireCatalogCandidateCompatibility(
            LlamaCppModelCatalog.Profile catalog,
            LlamaCppModelCatalog.Profile candidate) {
        LlamaCppModelCatalog.ModelArtifact catalogArtifact = catalog.artifact();
        LlamaCppModelCatalog.ModelArtifact candidateArtifact = candidate.artifact();
        LlamaCppModelCatalog.RuntimeDefaults catalogRuntime = catalog.runtime();
        LlamaCppModelCatalog.RuntimeDefaults candidateRuntime = candidate.runtime();
        boolean compatible = Objects.equals(catalog.alias(), candidate.alias())
                && Objects.equals(catalogArtifact, candidateArtifact)
                && catalogRuntime.nativeCalling() == candidateRuntime.nativeCalling()
                && Objects.equals(catalogRuntime.toolMode(), candidateRuntime.toolMode())
                && Objects.equals(catalogRuntime.reasoningMode(), candidateRuntime.reasoningMode())
                && Objects.equals(catalogRuntime.chatTemplate(), candidateRuntime.chatTemplate())
                && catalogRuntime.requestLayout() == candidateRuntime.requestLayout()
                && Objects.equals(
                        catalogRuntime.managedServerArgs(),
                        candidateRuntime.managedServerArgs())
                && catalogRuntime.requiresLocalModelPath()
                        == candidateRuntime.requiresLocalModelPath()
                && candidateRuntime.contextTokens() > 0
                && candidateRuntime.contextTokens() <= catalogRuntime.maxContextTokens()
                && candidateRuntime.compatibleEngineLanes().size() == 1
                && catalogRuntime.compatibleEngineLanes().contains(
                        candidateRuntime.compatibleEngineLanes().getFirst());
        if (!compatible) {
            throw new IllegalStateException(
                    "qualification candidate execution facts differ from the durable catalog profile");
        }
    }

    /** Resolves cached and live facts without hashing the model artifact. */
    public Snapshot inspectAvailable() {
        return resolveAvailable(false);
    }

    /** Resolves live facts, optionally performing an exact artifact verification when needed. */
    public Snapshot resolveAvailable(boolean verifyArtifact) {
        LlamaCppPropsClient.Result result = props.fetch(URI.create(config.baseUrl()));
        return resolve(result, verifyArtifact, true);
    }

    Snapshot resolveAvailable(
            LlamaCppPropsClient.Result result,
            boolean verifyArtifact) {
        return resolve(Objects.requireNonNull(result, "llama.cpp props result"),
                verifyArtifact, true);
    }

    /** Validates facts available before a managed process starts. It never calls the server. */
    public Snapshot requirePrelaunch() {
        Snapshot snapshot = resolve(LlamaCppPropsClient.Result.unavailable("server not started"), true, false);
        if (snapshot.profile().resolution() == LlamaCppProfileResolver.ResolutionState.REFUSED) {
            throw new IllegalStateException("llama.cpp profile refused: " + snapshot.profile().reasons());
        }
        return snapshot;
    }

    /** Resolves only local cached facts. It never calls the server or hashes the model. */
    public Snapshot inspectLocal() {
        return resolve(LlamaCppPropsClient.Result.unavailable("server facts not requested"), false, false);
    }

    /** Exact content identities frozen by the qualification loader for one managed launch. */
    Optional<LlamaCppQualificationLaunchExpectation> qualificationLaunchExpectation() {
        if (!config.managed() || qualificationCandidate.isEmpty()) return Optional.empty();
        LlamaCppQualificationCandidateLoader.LoadedCandidate candidate =
                qualificationCandidate.get();
        if (candidate.protocolVersion() != 6) return Optional.empty();
        LlamaCppProfileResolver.ArtifactIdentity artifact = candidate.artifactIdentity();
        if (artifact == null) {
            throw new IllegalStateException(
                    "qualification candidate lacks an artifact verification identity");
        }
        return Optional.of(new LlamaCppQualificationLaunchExpectation(
                candidate.declarationSha256(),
                candidate.engineExecutableSha256(),
                artifact.actualSha256(),
                candidate.effectiveEnvironmentSha256(),
                candidate.endpointObserverKind(),
                candidate.endpointObserverIdentitySha256(),
                config.verificationLogging()
                        ? dev.talos.spi.types.ProviderProcessLaunchReceipt.LaunchArgumentSet
                                .VERIFICATION
                        : dev.talos.spi.types.ProviderProcessLaunchReceipt.LaunchArgumentSet
                                .STANDARD));
    }

    private Snapshot resolve(
            LlamaCppPropsClient.Result propsResult,
            boolean verifyArtifact,
            boolean serverWasQueried) {
        Optional<LlamaCppModelCatalog.Profile> declared = catalogProfile.isPresent()
                ? catalogProfile
                : qualificationCandidate.map(LlamaCppQualificationCandidateLoader.LoadedCandidate::profile);
        LlamaCppPropsClient.Result safeResult = propsResult == null
                ? LlamaCppPropsClient.Result.unavailable("server facts unavailable")
                : propsResult;
        LlamaCppPropsClient.Props observedProps = safeResult.state() == LlamaCppPropsClient.State.AVAILABLE
                ? safeResult.props()
                : NO_PROPS;
        Path modelPath = modelPath(
                config.modelPath(),
                config.managed() ? observedProps.modelPath() : "");
        LlamaCppProfileResolver.ArtifactIdentity artifact = artifactIdentity(
                modelPath, declared, verifyArtifact);
        LlamaCppProfileResolver.EngineFacts engine = engines.resolve(
                config, declared, observedProps, verifyArtifact);
        if (verifyArtifact && qualificationCandidate.isPresent()) {
            String expectedEngine = qualificationCandidate.get().engineExecutableSha256();
            if (!expectedEngine.isBlank()
                    && !expectedEngine.equals(engine.executableIdentity())) {
                engine = new LlamaCppProfileResolver.EngineFacts(
                        engine.lane(),
                        engine.executableIdentity(),
                        LlamaCppProfileResolver.MatchState.MISMATCHED);
            }
        }
        LlamaCppProfileResolver.ObservedServerFacts observed = observedFacts(
                declared, safeResult, observedProps);
        LlamaCppProfileResolver.ConfiguredFacts configured = configuredFacts(declared, modelPath, artifact);

        LlamaCppProfileResolver.ResolvedLlamaCppProfile profile = LlamaCppProfileResolver.resolve(
                new LlamaCppProfileResolver.Request(
                        declared,
                        configured,
                        artifact,
                        engine,
                        observed,
                        new LlamaCppProfileResolver.HardwareFacts(
                                LlamaCppProfileResolver.HardwareSupportState.UNVERIFIED, "")));
        boolean chatAvailable = profile.resolution() != LlamaCppProfileResolver.ResolutionState.REFUSED;
        LlamaCppPropsClient.State state = serverWasQueried
                ? safeResult.state()
                : LlamaCppPropsClient.State.UNAVAILABLE;
        return new Snapshot(
                profile,
                state,
                safeResult.reason(),
                observedProps.responseSha256(),
                observedProps.modelPath(),
                observedProps.buildInfo(),
                chatAvailable,
                qualificationCandidate.isPresent());
    }

    private LlamaCppProfileResolver.ArtifactIdentity artifactIdentity(
            Path modelPath,
            Optional<LlamaCppModelCatalog.Profile> declared,
            boolean verifyArtifact) {
        if (modelPath == null) {
            if (config.managed()
                    && declared.isPresent()
                    && declared.get().requiresLocalModelPath()) {
                return new LlamaCppProfileResolver.ArtifactIdentity(
                        LlamaCppProfileResolver.ArtifactState.MISSING,
                        "", declared.get().artifactSizeBytes(), -1,
                        declared.get().artifactSha256(), "", "");
            }
            return new LlamaCppProfileResolver.ArtifactIdentity(
                    declared.isPresent()
                            ? LlamaCppProfileResolver.ArtifactState.UNCHECKED
                            : LlamaCppProfileResolver.ArtifactState.CUSTOM,
                    "", declared.map(LlamaCppModelCatalog.Profile::artifactSizeBytes).orElse(-1L), -1,
                    declared.map(LlamaCppModelCatalog.Profile::artifactSha256).orElse(""), "", "");
        }
        try {
            return artifacts.resolve(
                    modelPath,
                    config.model(),
                    declared.map(LlamaCppModelCatalog.Profile::artifact).orElse(null),
                    talosHome,
                    verifyArtifact);
        } catch (Exception error) {
            return new LlamaCppProfileResolver.ArtifactIdentity(
                    LlamaCppProfileResolver.ArtifactState.UNCHECKED,
                    canonicalText(modelPath),
                    declared.map(LlamaCppModelCatalog.Profile::artifactSizeBytes).orElse(-1L),
                    fileSize(modelPath),
                    declared.map(LlamaCppModelCatalog.Profile::artifactSha256).orElse(""),
                    "", "");
        }
    }

    private LlamaCppProfileResolver.ConfiguredFacts configuredFacts(
            Optional<LlamaCppModelCatalog.Profile> declared,
            Path modelPath,
            LlamaCppProfileResolver.ArtifactIdentity artifact) {
        String reasoning = declared
                .map(profile -> profile.runtime().reasoningMode())
                .orElse("unverified");
        String toolMode = config.nativeCallingRequested() ? "native/default" : "text/tool-prompt";
        String canonicalPath = modelPath == null ? artifact.canonicalPath() : canonicalText(modelPath);
        return new LlamaCppProfileResolver.ConfiguredFacts(
                config.model(),
                canonicalPath,
                config.context(),
                toolMode,
                reasoning,
                config.serverArgs(),
                config.managed());
    }

    private static LlamaCppProfileResolver.ObservedServerFacts observedFacts(
            Optional<LlamaCppModelCatalog.Profile> declared,
            LlamaCppPropsClient.Result result,
            LlamaCppPropsClient.Props observed) {
        if (result.state() != LlamaCppPropsClient.State.AVAILABLE) {
            return LlamaCppProfileResolver.ObservedServerFacts.unavailable();
        }
        boolean known = declared.isPresent();
        boolean expectedNative = declared.map(LlamaCppModelCatalog.Profile::nativeCalling).orElse(false);
        LlamaCppProfileResolver.MatchState templateIdentity;
        if (!known) {
            templateIdentity = LlamaCppProfileResolver.MatchState.UNVERIFIED;
        } else if (observed.chatTemplate().isBlank()) {
            templateIdentity = LlamaCppProfileResolver.MatchState.MISMATCHED;
        } else if (expectedNative && !observed.supportsNativeTools()) {
            templateIdentity = LlamaCppProfileResolver.MatchState.MISMATCHED;
        } else {
            templateIdentity = LlamaCppProfileResolver.MatchState.MATCHED;
        }
        LlamaCppProfileResolver.ToolMode toolMode = observed.supportsNativeTools()
                ? LlamaCppProfileResolver.ToolMode.NATIVE
                : observed.chatTemplate().isBlank()
                        ? LlamaCppProfileResolver.ToolMode.CHAT_ONLY
                        : LlamaCppProfileResolver.ToolMode.TEXT_TOOL_PROMPT;
        Set<LlamaCppProfileResolver.Modality> modalities = EnumSet.of(
                LlamaCppProfileResolver.Modality.TEXT);
        if (observed.vision()) modalities.add(LlamaCppProfileResolver.Modality.IMAGE);
        return new LlamaCppProfileResolver.ObservedServerFacts(
                LlamaCppProfileResolver.ObservationState.AVAILABLE,
                canonicalText(pathOrNull(observed.modelPath())),
                "",
                "llama_cpp",
                observed.chatTemplate(),
                templateIdentity,
                toolMode,
                observed.contextTokens(),
                modalities,
                observed.responseSha256());
    }

    private static ArtifactSource defaultArtifactSource() {
        LlamaCppArtifactVerificationStore store = new LlamaCppArtifactVerificationStore();
        return (path, alias, expected, home, verify) -> verify
                ? store.verify(path, alias, expected, home)
                : store.inspect(path, alias, expected, home);
    }

    private static LlamaCppProfileResolver.EngineFacts defaultEngineFacts(
            LlamaCppConfig config,
            Optional<LlamaCppModelCatalog.Profile> declared,
            LlamaCppPropsClient.Props observed,
            boolean verify) {
        if (!config.managed()) {
            return new LlamaCppProfileResolver.EngineFacts(
                    "", "", LlamaCppProfileResolver.MatchState.UNVERIFIED);
        }
        Path executable = pathOrNull(config.serverPath());
        String lane = engineLane(executable);
        boolean compatible = declared.isEmpty()
                || declared.get().runtime().compatibleEngineLanes().contains(lane);
        if (!compatible) {
            return new LlamaCppProfileResolver.EngineFacts(
                    lane, "", LlamaCppProfileResolver.MatchState.MISMATCHED);
        }
        if (!verify || executable == null || !Files.isRegularFile(executable)) {
            return new LlamaCppProfileResolver.EngineFacts(
                    lane, "", LlamaCppProfileResolver.MatchState.UNVERIFIED);
        }
        String identity = sha256File(executable);
        return new LlamaCppProfileResolver.EngineFacts(
                lane,
                identity,
                identity.isBlank()
                        ? LlamaCppProfileResolver.MatchState.UNVERIFIED
                        : LlamaCppProfileResolver.MatchState.MATCHED);
    }

    static String engineLane(Path executable) {
        if (executable == null) return "";
        Path normalized = executable.toAbsolutePath().normalize();
        for (int i = 0; i + 2 < normalized.getNameCount(); i++) {
            if ("llama.cpp".equals(normalized.getName(i).toString())) {
                String tag = normalized.getName(i + 1).toString();
                String variant = normalized.getName(i + 2).toString();
                return variant + ":" + tag;
            }
        }
        return "";
    }

    private static Path modelPath(String configured, String observed) {
        Path configuredPath = pathOrNull(configured);
        return configuredPath != null ? configuredPath : pathOrNull(observed);
    }

    private static Path pathOrNull(String value) {
        String clean = clean(value);
        if (clean.isBlank()) return null;
        try {
            return Path.of(clean).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String canonicalText(Path path) {
        if (path == null) return "";
        try {
            return path.toRealPath().toString();
        } catch (Exception ignored) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static long fileSize(Path path) {
        try {
            return path == null ? -1 : Files.size(path);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String sha256File(Path path) {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Path defaultTalosHome() {
        return Path.of(System.getProperty("user.home", "."), ".talos").toAbsolutePath().normalize();
    }

    private static String fileNameOnly(String value) {
        Path path = pathOrNull(value);
        if (path == null || path.getFileName() == null) return "";
        return path.getFileName().toString();
    }

    private static String bounded(String value, int max) {
        String text = clean(value).replaceAll("[\\r\\n\\t]+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }
}
