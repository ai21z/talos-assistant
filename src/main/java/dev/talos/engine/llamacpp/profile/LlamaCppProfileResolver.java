package dev.talos.engine.llamacpp.profile;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.engine.llamacpp.ManagedContextSelector;
import dev.talos.spi.types.RequestMessageLayout;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves a declared llama.cpp profile into a runtime capability ceiling. */
public final class LlamaCppProfileResolver {
    private static final Set<Modality> TALOS_MODALITY_CEILING = Set.of(Modality.TEXT);

    private LlamaCppProfileResolver() {}

    public enum ResolutionState {
        REFUSED(0),
        UNVERIFIED(1),
        QUALIFIED(2);

        private final int rank;

        ResolutionState(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public enum MatchState {
        MATCHED,
        MISMATCHED,
        UNVERIFIED
    }

    public enum ArtifactState {
        EXACT,
        CUSTOM,
        UNCHECKED,
        MISSING,
        SIZE_MISMATCH,
        SHA256_MISMATCH
    }

    public enum ObservationState {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum ToolMode {
        CHAT_ONLY(0),
        TEXT_TOOL_PROMPT(1),
        NATIVE(2);

        private final int rank;

        ToolMode(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public enum Modality {
        TEXT,
        IMAGE,
        AUDIO
    }

    public enum HardwareSupportState {
        MEASURED_SUPPORTED,
        OUTSIDE_MEASURED_SUPPORT,
        UNVERIFIED
    }

    /** Stable reasons suitable for later bounded status and trace rendering. */
    public enum ReasonCode {
        CUSTOM_PROFILE,
        CONFIGURED_ALIAS_MISMATCH,
        ARTIFACT_CUSTOM,
        ARTIFACT_UNCHECKED,
        ARTIFACT_MISSING,
        ARTIFACT_SIZE_MISMATCH,
        ARTIFACT_SHA256_MISMATCH,
        ARTIFACT_PATH_MISMATCH,
        ARTIFACT_VERIFICATION_UNBOUND,
        ENGINE_LANE_UNVERIFIED,
        ENGINE_LANE_INCOMPATIBLE,
        ENGINE_EXECUTABLE_UNVERIFIED,
        ENGINE_EXECUTABLE_MISMATCH,
        ENGINE_EXECUTABLE_UNBOUND,
        SERVER_FACTS_UNAVAILABLE,
        SERVER_BACKEND_MISMATCH,
        SERVER_MODEL_UNVERIFIED,
        SERVER_MODEL_MISMATCH,
        TEMPLATE_UNVERIFIED,
        TEMPLATE_MISMATCH,
        NATIVE_TOOLS_UNAVAILABLE,
        TOOL_MODE_NARROWED,
        TOOL_CAPABILITY_CAPPED,
        REASONING_MODE_MISMATCH,
        REQUIRED_LAUNCH_ARGUMENTS_MISSING,
        CONTEXT_REDUCED,
        CONTEXT_CAPPED_TO_DECLARED,
        OBSERVED_CONTEXT_EXCEEDS_DECLARED,
        TEXT_MODALITY_UNAVAILABLE,
        UNDECLARED_MODALITY_IGNORED,
        HARDWARE_UNVERIFIED,
        OUTSIDE_MEASURED_HARDWARE
    }

    public record ConfiguredFacts(
            String alias,
            String canonicalModelPath,
            int contextTokens,
            String toolMode,
            String reasoningMode,
            List<String> launchArguments,
            boolean managed) {

        public ConfiguredFacts {
            alias = clean(alias);
            canonicalModelPath = clean(canonicalModelPath);
            contextTokens = Math.max(256, contextTokens);
            toolMode = clean(toolMode);
            reasoningMode = clean(reasoningMode);
            launchArguments = immutableStrings(launchArguments);
        }
    }

    public record ArtifactIdentity(
            ArtifactState state,
            String canonicalPath,
            long expectedSize,
            long actualSize,
            String expectedSha256,
            String actualSha256,
            String verificationIdentity) {

        public ArtifactIdentity {
            state = state == null ? ArtifactState.UNCHECKED : state;
            canonicalPath = clean(canonicalPath);
            expectedSha256 = normalizedHash(expectedSha256);
            actualSha256 = normalizedHash(actualSha256);
            verificationIdentity = clean(verificationIdentity);
        }
    }

    public record EngineFacts(
            String lane,
            String executableIdentity,
            MatchState executableVerification) {

        public EngineFacts {
            lane = clean(lane);
            executableIdentity = clean(executableIdentity);
            executableVerification = executableVerification == null
                    ? MatchState.UNVERIFIED
                    : executableVerification;
        }
    }

    public record ObservedServerFacts(
            ObservationState state,
            String canonicalModelPath,
            String modelAlias,
            String backend,
            String chatTemplate,
            MatchState templateIdentity,
            ToolMode toolMode,
            int contextTokens,
            Set<Modality> modalities,
            String offloadIdentity) {

        public ObservedServerFacts {
            state = state == null ? ObservationState.UNAVAILABLE : state;
            canonicalModelPath = clean(canonicalModelPath);
            modelAlias = clean(modelAlias);
            backend = clean(backend);
            chatTemplate = clean(chatTemplate);
            templateIdentity = templateIdentity == null ? MatchState.UNVERIFIED : templateIdentity;
            toolMode = toolMode == null ? ToolMode.CHAT_ONLY : toolMode;
            contextTokens = Math.max(0, contextTokens);
            modalities = immutableModalities(modalities);
            offloadIdentity = clean(offloadIdentity);
        }

        public static ObservedServerFacts unavailable() {
            return new ObservedServerFacts(
                    ObservationState.UNAVAILABLE,
                    "", "", "", "", MatchState.UNVERIFIED, ToolMode.CHAT_ONLY, 0, Set.of(), "");
        }
    }

    public record HardwareFacts(
            HardwareSupportState state,
            String evidenceIdentity) {

        public HardwareFacts {
            state = state == null ? HardwareSupportState.UNVERIFIED : state;
            evidenceIdentity = clean(evidenceIdentity);
        }
    }

    public record Request(
            Optional<LlamaCppModelCatalog.Profile> declaredProfile,
            ConfiguredFacts configured,
            ArtifactIdentity artifact,
            EngineFacts engine,
            ObservedServerFacts observed,
            HardwareFacts hardware) {

        public Request {
            declaredProfile = declaredProfile == null ? Optional.empty() : declaredProfile;
            configured = configured == null
                    ? new ConfiguredFacts("", "", ManagedContextSelector.DEFAULT_CONTEXT,
                            "", "", List.of(), true)
                    : configured;
            artifact = artifact == null
                    ? new ArtifactIdentity(ArtifactState.UNCHECKED, "", -1, -1, "", "", "")
                    : artifact;
            engine = engine == null
                    ? new EngineFacts("", "", MatchState.UNVERIFIED)
                    : engine;
            observed = observed == null ? ObservedServerFacts.unavailable() : observed;
            hardware = hardware == null
                    ? new HardwareFacts(HardwareSupportState.UNVERIFIED, "")
                    : hardware;
        }
    }

    public record ResolvedLlamaCppProfile(
            String declaredAlias,
            ConfiguredFacts configuredFacts,
            ArtifactIdentity artifactIdentity,
            EngineFacts engineFacts,
            ObservedServerFacts observedFacts,
            HardwareFacts hardwareFacts,
            ArtifactState artifactState,
            MatchState engineCompatibility,
            MatchState servedIdentity,
            ToolMode effectiveToolMode,
            RequestMessageLayout effectiveRequestLayout,
            String effectiveReasoningMode,
            int effectiveContextTokens,
            List<String> effectiveLaunchArguments,
            Set<Modality> effectiveModalities,
            HardwareSupportState hardwareSupport,
            LlamaCppModelCatalog.Disposition disposition,
            ResolutionState resolution,
            List<ReasonCode> reasons) {

        public ResolvedLlamaCppProfile {
            declaredAlias = clean(declaredAlias);
            configuredFacts = configuredFacts == null
                    ? new ConfiguredFacts("", "", ManagedContextSelector.DEFAULT_CONTEXT,
                            "", "", List.of(), true)
                    : configuredFacts;
            artifactIdentity = artifactIdentity == null
                    ? new ArtifactIdentity(ArtifactState.UNCHECKED, "", -1, -1, "", "", "")
                    : artifactIdentity;
            engineFacts = engineFacts == null
                    ? new EngineFacts("", "", MatchState.UNVERIFIED)
                    : engineFacts;
            observedFacts = observedFacts == null ? ObservedServerFacts.unavailable() : observedFacts;
            hardwareFacts = hardwareFacts == null
                    ? new HardwareFacts(HardwareSupportState.UNVERIFIED, "")
                    : hardwareFacts;
            artifactState = artifactState == null ? ArtifactState.UNCHECKED : artifactState;
            engineCompatibility = engineCompatibility == null ? MatchState.UNVERIFIED : engineCompatibility;
            servedIdentity = servedIdentity == null ? MatchState.UNVERIFIED : servedIdentity;
            effectiveToolMode = effectiveToolMode == null ? ToolMode.CHAT_ONLY : effectiveToolMode;
            effectiveRequestLayout = effectiveRequestLayout == null
                    ? RequestMessageLayout.PRESERVE_AS_WRITTEN
                    : effectiveRequestLayout;
            effectiveReasoningMode = clean(effectiveReasoningMode);
            effectiveContextTokens = Math.max(256, effectiveContextTokens);
            effectiveLaunchArguments = immutableStrings(effectiveLaunchArguments);
            effectiveModalities = immutableModalities(effectiveModalities);
            hardwareSupport = hardwareSupport == null ? HardwareSupportState.UNVERIFIED : hardwareSupport;
            disposition = disposition == null
                    ? LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED
                    : disposition;
            resolution = resolution == null ? ResolutionState.UNVERIFIED : resolution;
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    public static ResolvedLlamaCppProfile resolve(Request request) {
        Request safe = request == null
                ? new Request(Optional.empty(), null, null, null, null, null)
                : request;
        Optional<LlamaCppModelCatalog.Profile> declared = safe.declaredProfile();
        ConfiguredFacts configured = safe.configured();
        ArtifactIdentity artifact = safe.artifact();
        EngineFacts engine = safe.engine();
        ObservedServerFacts observed = safe.observed();
        HardwareFacts hardware = safe.hardware();
        LinkedHashSet<ReasonCode> reasons = new LinkedHashSet<>();
        DecisionFlags flags = new DecisionFlags();

        String declaredAlias = declared.map(LlamaCppModelCatalog.Profile::alias).orElse("");
        ArtifactState artifactState = resolveArtifactState(declared, configured, artifact, reasons, flags);
        MatchState engineCompatibility = resolveEngineCompatibility(declared, engine, reasons, flags);
        MatchState servedIdentity = resolveServedIdentity(configured, artifact, observed, reasons, flags);

        if (declared.isEmpty()) {
            flags.unverified();
            reasons.add(ReasonCode.CUSTOM_PROFILE);
        } else if (!normalized(configured.alias()).equals(normalized(declaredAlias))) {
            flags.refuse();
            reasons.add(ReasonCode.CONFIGURED_ALIAS_MISMATCH);
        }

        LlamaCppModelCatalog.RuntimeDefaults runtime = declared
                .map(LlamaCppModelCatalog.Profile::runtime)
                .orElse(null);
        int effectiveContext = resolveContext(configured, observed, runtime, reasons, flags);
        List<String> effectiveArguments = configured.launchArguments();
        String effectiveReasoning = resolveReasoning(configured, runtime, reasons, flags);

        if (runtime != null && !containsContiguousSubsequence(
                configured.launchArguments(), runtime.managedServerArgs())) {
            flags.refuse();
            reasons.add(ReasonCode.REQUIRED_LAUNCH_ARGUMENTS_MISSING);
        }

        ToolMode effectiveTools = resolveTools(configured, observed, runtime, reasons, flags);
        boolean profileIdentityBound = declared.isPresent()
                && artifactState == ArtifactState.EXACT
                && !reasons.contains(ReasonCode.ARTIFACT_PATH_MISMATCH)
                && !reasons.contains(ReasonCode.ARTIFACT_VERIFICATION_UNBOUND)
                && engineCompatibility == MatchState.MATCHED
                && servedIdentity == MatchState.MATCHED;
        if (!profileIdentityBound) {
            effectiveTools = ToolMode.CHAT_ONLY;
        }
        Set<Modality> effectiveModalities = resolveModalities(observed, reasons, flags);
        HardwareSupportState effectiveHardware = resolveHardware(hardware, reasons);

        ResolutionState resolution = flags.resolution();
        RequestMessageLayout effectiveRequestLayout = resolution == ResolutionState.QUALIFIED
                && profileIdentityBound
                && runtime != null
                ? runtime.requestLayout()
                : RequestMessageLayout.PRESERVE_AS_WRITTEN;
        LlamaCppModelCatalog.Disposition disposition = resolution == ResolutionState.QUALIFIED
                ? declared.map(LlamaCppModelCatalog.Profile::disposition)
                        .orElse(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED)
                : LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED;
        if (resolution == ResolutionState.REFUSED) {
            effectiveTools = ToolMode.CHAT_ONLY;
        }

        return new ResolvedLlamaCppProfile(
                declaredAlias,
                configured,
                artifact,
                engine,
                observed,
                hardware,
                artifactState,
                engineCompatibility,
                servedIdentity,
                effectiveTools,
                effectiveRequestLayout,
                effectiveReasoning,
                effectiveContext,
                effectiveArguments,
                effectiveModalities,
                effectiveHardware,
                disposition,
                resolution,
                List.copyOf(reasons));
    }

    private static ArtifactState resolveArtifactState(
            Optional<LlamaCppModelCatalog.Profile> declared,
            ConfiguredFacts configured,
            ArtifactIdentity artifact,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        ArtifactState state = artifact.state();
        if (declared.isPresent() && state == ArtifactState.EXACT) {
            var profile = declared.get();
            if (artifact.expectedSize() != profile.artifactSizeBytes()
                    || artifact.actualSize() != profile.artifactSizeBytes()) {
                state = ArtifactState.SIZE_MISMATCH;
            } else if (!artifact.expectedSha256().equals(profile.artifactSha256())
                    || !artifact.actualSha256().equals(profile.artifactSha256())) {
                state = ArtifactState.SHA256_MISMATCH;
            } else if (!configured.canonicalModelPath().isBlank()
                    && !configured.canonicalModelPath().equals(artifact.canonicalPath())) {
                flags.refuse();
                reasons.add(ReasonCode.ARTIFACT_PATH_MISMATCH);
            } else if (artifact.verificationIdentity().isBlank()) {
                flags.unverified();
                reasons.add(ReasonCode.ARTIFACT_VERIFICATION_UNBOUND);
            }
        }
        switch (state) {
            case EXACT -> {
                if (declared.isEmpty()) {
                    flags.unverified();
                    reasons.add(ReasonCode.CUSTOM_PROFILE);
                }
            }
            case CUSTOM -> {
                flags.unverified();
                reasons.add(ReasonCode.ARTIFACT_CUSTOM);
            }
            case UNCHECKED -> {
                flags.unverified();
                reasons.add(ReasonCode.ARTIFACT_UNCHECKED);
            }
            case MISSING -> {
                flags.refuse();
                reasons.add(ReasonCode.ARTIFACT_MISSING);
            }
            case SIZE_MISMATCH -> {
                flags.refuse();
                reasons.add(ReasonCode.ARTIFACT_SIZE_MISMATCH);
            }
            case SHA256_MISMATCH -> {
                flags.refuse();
                reasons.add(ReasonCode.ARTIFACT_SHA256_MISMATCH);
            }
        }
        return state;
    }

    private static MatchState resolveEngineCompatibility(
            Optional<LlamaCppModelCatalog.Profile> declared,
            EngineFacts engine,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        if (engine.executableVerification() == MatchState.MISMATCHED) {
            flags.refuse();
            reasons.add(ReasonCode.ENGINE_EXECUTABLE_MISMATCH);
            return MatchState.MISMATCHED;
        }
        if (declared.isEmpty()) {
            return MatchState.UNVERIFIED;
        }
        if (engine.lane().isBlank()) {
            flags.unverified();
            reasons.add(ReasonCode.ENGINE_LANE_UNVERIFIED);
            return MatchState.UNVERIFIED;
        }
        if (!declared.get().runtime().compatibleEngineLanes().contains(engine.lane())) {
            flags.refuse();
            reasons.add(ReasonCode.ENGINE_LANE_INCOMPATIBLE);
            return MatchState.MISMATCHED;
        }
        if (engine.executableVerification() == MatchState.UNVERIFIED) {
            flags.unverified();
            reasons.add(ReasonCode.ENGINE_EXECUTABLE_UNVERIFIED);
            return MatchState.UNVERIFIED;
        }
        if (engine.executableIdentity().isBlank()) {
            flags.unverified();
            reasons.add(ReasonCode.ENGINE_EXECUTABLE_UNBOUND);
            return MatchState.UNVERIFIED;
        }
        return MatchState.MATCHED;
    }

    private static MatchState resolveServedIdentity(
            ConfiguredFacts configured,
            ArtifactIdentity artifact,
            ObservedServerFacts observed,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        if (observed.state() == ObservationState.UNAVAILABLE) {
            flags.unverified();
            reasons.add(ReasonCode.SERVER_FACTS_UNAVAILABLE);
            return MatchState.UNVERIFIED;
        }
        if (!"llama_cpp".equals(normalized(observed.backend()))) {
            flags.refuse();
            reasons.add(ReasonCode.SERVER_BACKEND_MISMATCH);
            return MatchState.MISMATCHED;
        }
        boolean pathPresent = !observed.canonicalModelPath().isBlank();
        boolean aliasPresent = !observed.modelAlias().isBlank();
        String expectedPath = configured.canonicalModelPath().isBlank()
                ? artifact.canonicalPath()
                : configured.canonicalModelPath();
        boolean pathMismatch = pathPresent
                && !expectedPath.isBlank()
                && !observed.canonicalModelPath().equals(expectedPath);
        boolean aliasMismatch = aliasPresent
                && !normalized(observed.modelAlias()).equals(normalized(configured.alias()));
        if (pathMismatch || aliasMismatch) {
            flags.refuse();
            reasons.add(ReasonCode.SERVER_MODEL_MISMATCH);
            return MatchState.MISMATCHED;
        }
        if (!pathPresent || (expectedPath.isBlank() && !aliasPresent)) {
            flags.unverified();
            reasons.add(ReasonCode.SERVER_MODEL_UNVERIFIED);
            return MatchState.UNVERIFIED;
        }
        return MatchState.MATCHED;
    }

    private static int resolveContext(
            ConfiguredFacts configured,
            ObservedServerFacts observed,
            LlamaCppModelCatalog.RuntimeDefaults runtime,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        int declaredDefault = runtime == null
                ? ManagedContextSelector.DEFAULT_CONTEXT
                : runtime.contextTokens();
        int declaredCeiling = runtime == null
                ? ManagedContextSelector.DEFAULT_CONTEXT
                : runtime.maxContextTokens();
        int effective = Math.min(configured.contextTokens(), declaredCeiling);
        if (configured.contextTokens() < declaredDefault) {
            reasons.add(ReasonCode.CONTEXT_REDUCED);
        } else if (configured.contextTokens() > declaredCeiling) {
            flags.unverified();
            reasons.add(ReasonCode.CONTEXT_CAPPED_TO_DECLARED);
        }
        if (observed.state() == ObservationState.AVAILABLE && observed.contextTokens() > 0) {
            if (observed.contextTokens() < effective) {
                effective = observed.contextTokens();
                reasons.add(ReasonCode.CONTEXT_REDUCED);
            } else if (observed.contextTokens() > declaredCeiling) {
                flags.unverified();
                reasons.add(ReasonCode.OBSERVED_CONTEXT_EXCEEDS_DECLARED);
            }
        }
        return effective;
    }

    private static String resolveReasoning(
            ConfiguredFacts configured,
            LlamaCppModelCatalog.RuntimeDefaults runtime,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        if (runtime == null) return "unverified";
        if (!configured.reasoningMode().equals(runtime.reasoningMode())) {
            flags.refuse();
            reasons.add(ReasonCode.REASONING_MODE_MISMATCH);
        }
        return runtime.reasoningMode();
    }

    private static ToolMode resolveTools(
            ConfiguredFacts configured,
            ObservedServerFacts observed,
            LlamaCppModelCatalog.RuntimeDefaults runtime,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        if (runtime == null || observed.state() == ObservationState.UNAVAILABLE) {
            return ToolMode.CHAT_ONLY;
        }
        ToolMode declaredMode = toolMode(runtime.toolMode());
        ToolMode configuredMode = toolMode(configured.toolMode());
        ToolMode observedMode = observed.toolMode();
        ToolMode effective = narrowest(declaredMode, configuredMode, observedMode);

        if (observed.templateIdentity() == MatchState.UNVERIFIED) {
            flags.unverified();
            reasons.add(ReasonCode.TEMPLATE_UNVERIFIED);
            return ToolMode.CHAT_ONLY;
        }
        if (observed.templateIdentity() == MatchState.MISMATCHED) {
            flags.unverified();
            reasons.add(ReasonCode.TEMPLATE_MISMATCH);
            return ToolMode.CHAT_ONLY;
        }
        if (configuredMode.rank() < declaredMode.rank()) {
            flags.unverified();
            reasons.add(ReasonCode.TOOL_MODE_NARROWED);
        } else if (configuredMode.rank() > declaredMode.rank()) {
            reasons.add(ReasonCode.TOOL_CAPABILITY_CAPPED);
        }
        if (observedMode.rank() < declaredMode.rank()) {
            flags.unverified();
            if (declaredMode == ToolMode.NATIVE) {
                reasons.add(ReasonCode.NATIVE_TOOLS_UNAVAILABLE);
            } else {
                reasons.add(ReasonCode.TOOL_MODE_NARROWED);
            }
        } else if (observedMode.rank() > declaredMode.rank()) {
            reasons.add(ReasonCode.TOOL_CAPABILITY_CAPPED);
        }
        return effective;
    }

    private static Set<Modality> resolveModalities(
            ObservedServerFacts observed,
            Set<ReasonCode> reasons,
            DecisionFlags flags) {
        if (observed.state() == ObservationState.UNAVAILABLE) return Set.of();
        if (!observed.modalities().contains(Modality.TEXT)) {
            flags.refuse();
            reasons.add(ReasonCode.TEXT_MODALITY_UNAVAILABLE);
            return Set.of();
        }
        if (!TALOS_MODALITY_CEILING.containsAll(observed.modalities())) {
            reasons.add(ReasonCode.UNDECLARED_MODALITY_IGNORED);
        }
        return Set.of(Modality.TEXT);
    }

    private static HardwareSupportState resolveHardware(
            HardwareFacts hardware,
            Set<ReasonCode> reasons) {
        HardwareSupportState effective = hardware.state();
        if (effective == HardwareSupportState.MEASURED_SUPPORTED
                && hardware.evidenceIdentity().isBlank()) {
            effective = HardwareSupportState.UNVERIFIED;
        }
        if (effective == HardwareSupportState.UNVERIFIED) {
            reasons.add(ReasonCode.HARDWARE_UNVERIFIED);
        } else if (effective == HardwareSupportState.OUTSIDE_MEASURED_SUPPORT) {
            reasons.add(ReasonCode.OUTSIDE_MEASURED_HARDWARE);
        }
        return effective;
    }

    private static ToolMode narrowest(ToolMode... modes) {
        ToolMode result = ToolMode.NATIVE;
        for (ToolMode mode : modes) {
            if (mode.rank() < result.rank()) result = mode;
        }
        return result;
    }

    private static ToolMode toolMode(String value) {
        String normalized = normalized(value).replace('_', '-');
        if (normalized.startsWith("native")) return ToolMode.NATIVE;
        if (normalized.contains("tool-prompt") || normalized.contains("text/tool")) {
            return ToolMode.TEXT_TOOL_PROMPT;
        }
        return ToolMode.CHAT_ONLY;
    }

    private static boolean containsContiguousSubsequence(List<String> actual, List<String> required) {
        if (required.isEmpty()) return true;
        if (actual.size() < required.size()) return false;
        for (int start = 0; start <= actual.size() - required.size(); start++) {
            boolean matched = true;
            for (int offset = 0; offset < required.size(); offset++) {
                if (!actual.get(start + offset).equals(required.get(offset))) {
                    matched = false;
                    break;
                }
            }
            if (matched) return true;
        }
        return false;
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(LlamaCppProfileResolver::clean).toList();
    }

    private static Set<Modality> immutableModalities(Set<Modality> values) {
        if (values == null || values.isEmpty()) return Set.of();
        EnumSet<Modality> copy = EnumSet.copyOf(values);
        return Set.copyOf(copy);
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }

    private static String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizedHash(String value) {
        return normalized(value);
    }

    private static final class DecisionFlags {
        private boolean refused;
        private boolean unverified;

        void refuse() {
            refused = true;
        }

        void unverified() {
            unverified = true;
        }

        ResolutionState resolution() {
            if (refused) return ResolutionState.REFUSED;
            if (unverified) return ResolutionState.UNVERIFIED;
            return ResolutionState.QUALIFIED;
        }
    }
}
