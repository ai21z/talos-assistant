package dev.talos.engine.llamacpp.profile;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.spi.types.RequestMessageLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.ArtifactState;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.HardwareSupportState;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.MatchState;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.Modality;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.ObservationState;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.ReasonCode;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.ResolutionState;
import static dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver.ToolMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppProfileResolverTest {
    private static final String MODEL_PATH = "C:/models/Qwen3-8B-Q4_K_M.gguf";
    private static final String LANE = "win-x64-cuda-13.3:b9918";

    @Test
    void exactDeclaredAndObservedFactsPreserveTheQualifiedCeiling() {
        var profile = qwen3();
        var request = exactRequest(profile);

        var resolved = LlamaCppProfileResolver.resolve(request);

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(profile.disposition(), resolved.disposition());
        assertEquals(ArtifactState.EXACT, resolved.artifactState());
        assertEquals(MatchState.MATCHED, resolved.engineCompatibility());
        assertEquals(MatchState.MATCHED, resolved.servedIdentity());
        assertEquals(ToolMode.NATIVE, resolved.effectiveToolMode());
        assertEquals(RequestMessageLayout.PRESERVE_AS_WRITTEN,
                resolved.effectiveRequestLayout());
        assertEquals(profile.runtime().reasoningMode(), resolved.effectiveReasoningMode());
        assertEquals(profile.runtime().contextTokens(), resolved.effectiveContextTokens());
        assertEquals(profile.runtime().managedServerArgs(), resolved.effectiveLaunchArguments());
        assertEquals(Set.of(Modality.TEXT), resolved.effectiveModalities());
        assertEquals(HardwareSupportState.MEASURED_SUPPORTED, resolved.hardwareSupport());
        assertEquals(request.configured(), resolved.configuredFacts());
        assertEquals(request.artifact(), resolved.artifactIdentity());
        assertEquals(request.engine(), resolved.engineFacts());
        assertEquals(request.observed(), resolved.observedFacts());
        assertEquals(request.hardware(), resolved.hardwareFacts());
        assertTrue(resolved.reasons().isEmpty(), resolved.reasons().toString());
    }

    @Test
    void exactBoundProfileCanSelectSingleLeadingSystemLayout() {
        var profile = withRequestLayout(qwen3(), RequestMessageLayout.SINGLE_LEADING_SYSTEM);

        var resolved = LlamaCppProfileResolver.resolve(exactRequest(profile));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                resolved.effectiveRequestLayout());
    }

    @Test
    void unboundProfileCannotActivateSingleLeadingSystemLayout() {
        var profile = withRequestLayout(qwen3(), RequestMessageLayout.SINGLE_LEADING_SYSTEM);
        var exact = exactRequest(profile);
        var unverifiedEngine = new LlamaCppProfileResolver.Request(
                exact.declaredProfile(), exact.configured(), exact.artifact(),
                new LlamaCppProfileResolver.EngineFacts(LANE, "", MatchState.UNVERIFIED),
                exact.observed(), exact.hardware());

        var resolved = LlamaCppProfileResolver.resolve(unverifiedEngine);

        assertEquals(RequestMessageLayout.PRESERVE_AS_WRITTEN,
                resolved.effectiveRequestLayout());
    }

    @Test
    void qwen14HardwareSelected16kContextRemainsQualified() {
        var profile = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        var request = exactRequest(profile, "C:/models/" + profile.artifactFile(), LANE);
        var configured = new LlamaCppProfileResolver.ConfiguredFacts(
                profile.alias(), request.configured().canonicalModelPath(), 16_384,
                profile.toolMode(), profile.runtime().reasoningMode(),
                profile.runtime().managedServerArgs(), true);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                request.observed().canonicalModelPath(), profile.alias(), "llama_cpp",
                "embedded-template", MatchState.MATCHED, ToolMode.NATIVE, 16_384,
                Set.of(Modality.TEXT), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), configured, request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(16_384, resolved.effectiveContextTokens());
        assertFalse(resolved.reasons().contains(ReasonCode.CONTEXT_CAPPED_TO_DECLARED));
        assertFalse(resolved.reasons().contains(ReasonCode.OBSERVED_CONTEXT_EXCEEDS_DECLARED));
    }

    @Test
    void everySelectableCatalogProfileIsPreservedWhenAllFactsAgree() {
        for (var profile : LlamaCppModelCatalog.selectableProfiles()) {
            String modelPath = "C:/models/" + profile.artifactFile();
            String lane = profile.runtime().compatibleEngineLanes().getFirst();

            var resolved = LlamaCppProfileResolver.resolve(exactRequest(profile, modelPath, lane));

            assertEquals(ResolutionState.QUALIFIED, resolved.resolution(), profile.alias());
            assertEquals(profile.disposition(), resolved.disposition(), profile.alias());
            assertEquals(profile.nativeCalling() ? ToolMode.NATIVE : ToolMode.TEXT_TOOL_PROMPT,
                    resolved.effectiveToolMode(), profile.alias());
            assertTrue(resolved.reasons().isEmpty(), profile.alias() + ": " + resolved.reasons());
        }
    }

    @Test
    void customModelRunsOnlyAsUnverifiedWithoutInheritedToolClaims() {
        var request = new LlamaCppProfileResolver.Request(
                Optional.empty(),
                new LlamaCppProfileResolver.ConfiguredFacts(
                        "my-model", "C:/models/my-model.gguf", 16_384,
                        "native/default", "custom", List.of("--jinja"), true),
                new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.CUSTOM, "C:/models/my-model.gguf",
                        -1, 42, "", "abc", "custom-cache-entry"),
                new LlamaCppProfileResolver.EngineFacts(LANE, "engine-sha", MatchState.MATCHED),
                new LlamaCppProfileResolver.ObservedServerFacts(
                        ObservationState.AVAILABLE,
                        "C:/models/my-model.gguf", "my-model", "llama_cpp", "custom-template",
                        MatchState.MATCHED, ToolMode.NATIVE, 16_384,
                        Set.of(Modality.TEXT), "49 layers offloaded"),
                new LlamaCppProfileResolver.HardwareFacts(
                        HardwareSupportState.UNVERIFIED, ""));

        var resolved = LlamaCppProfileResolver.resolve(request);

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED, resolved.disposition());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertEquals(8_192, resolved.effectiveContextTokens());
        assertTrue(resolved.reasons().contains(ReasonCode.CUSTOM_PROFILE));
        assertTrue(resolved.reasons().contains(ReasonCode.HARDWARE_UNVERIFIED));
    }

    @Test
    void changedArtifactRefusesTheDeclaredProfile() {
        var profile = qwen3();
        var request = replaceArtifact(
                exactRequest(profile),
                new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.SHA256_MISMATCH,
                        MODEL_PATH,
                        profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                        profile.artifactSha256(), "0".repeat(64), "changed-copy"));

        var resolved = LlamaCppProfileResolver.resolve(request);

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertEquals(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED, resolved.disposition());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.ARTIFACT_SHA256_MISMATCH));
    }

    @Test
    void exactArtifactWithoutAVerificationIdentityIsNotQualified() {
        var profile = qwen3();
        var request = replaceArtifact(
                exactRequest(profile),
                new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.EXACT, MODEL_PATH,
                        profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                        profile.artifactSha256(), profile.artifactSha256(), ""));

        var resolved = LlamaCppProfileResolver.resolve(request);

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED, resolved.disposition());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.ARTIFACT_VERIFICATION_UNBOUND));
    }

    @Test
    void declaredProfileWithCustomArtifactStateHasAnExplicitDowngradeReason() {
        var profile = qwen3();
        var request = replaceArtifact(
                exactRequest(profile),
                new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.CUSTOM, MODEL_PATH,
                        -1, profile.artifactSizeBytes(), "", profile.artifactSha256(), "custom"));

        var resolved = LlamaCppProfileResolver.resolve(request);

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.ARTIFACT_CUSTOM));
    }

    @Test
    void wrongConfiguredAliasRefusesTheDeclaredProfile() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var configured = new LlamaCppProfileResolver.ConfiguredFacts(
                "qwen2.5-coder-14b", MODEL_PATH, profile.runtime().contextTokens(),
                profile.toolMode(), profile.runtime().reasoningMode(),
                profile.runtime().managedServerArgs(), true);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), configured, request.artifact(), request.engine(),
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertTrue(resolved.reasons().contains(ReasonCode.CONFIGURED_ALIAS_MISMATCH));
    }

    @Test
    void wrongServedModelRefusesTheDeclaredProfile() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                "C:/models/other.gguf", "other-model", "llama_cpp", "embedded-template",
                MatchState.MATCHED, ToolMode.NATIVE, 8_192,
                Set.of(Modality.TEXT), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertEquals(MatchState.MISMATCHED, resolved.servedIdentity());
        assertTrue(resolved.reasons().contains(ReasonCode.SERVER_MODEL_MISMATCH));
    }

    @Test
    void incompatibleEngineLaneRefusesBeforeAQualifiedClaim() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var engine = new LlamaCppProfileResolver.EngineFacts(
                "win-x64-cpu:b9918", "engine-sha", MatchState.MATCHED);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), engine,
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertEquals(MatchState.MISMATCHED, resolved.engineCompatibility());
        assertTrue(resolved.reasons().contains(ReasonCode.ENGINE_LANE_INCOMPATIBLE));
    }

    @Test
    void missingEngineLaneIsUnknownRatherThanAFalseMismatch() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var engine = new LlamaCppProfileResolver.EngineFacts(
                "", "engine-sha", MatchState.MATCHED);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), engine,
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(MatchState.UNVERIFIED, resolved.engineCompatibility());
        assertTrue(resolved.reasons().contains(ReasonCode.ENGINE_LANE_UNVERIFIED));
        assertFalse(resolved.reasons().contains(ReasonCode.ENGINE_LANE_INCOMPATIBLE));
    }

    @Test
    void matchedExecutableWithoutAnIdentityIsNotQualified() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var engine = new LlamaCppProfileResolver.EngineFacts(LANE, "", MatchState.MATCHED);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), engine,
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(MatchState.UNVERIFIED, resolved.engineCompatibility());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.ENGINE_EXECUTABLE_UNBOUND));
    }

    @Test
    void missingLiveFactsDowngradeWithoutInventingNativeTools() {
        var profile = qwen3();
        var request = exactRequest(profile);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                LlamaCppProfileResolver.ObservedServerFacts.unavailable(), request.hardware()));

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(MatchState.UNVERIFIED, resolved.servedIdentity());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertEquals(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED, resolved.disposition());
        assertTrue(resolved.reasons().contains(ReasonCode.SERVER_FACTS_UNAVAILABLE));
    }

    @Test
    void observedReducedContextNarrowsWithoutDemotingTheProfile() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                MODEL_PATH, profile.alias(), "llama_cpp", "embedded-template",
                MatchState.MATCHED, ToolMode.NATIVE, 4_096,
                Set.of(Modality.TEXT), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(profile.disposition(), resolved.disposition());
        assertEquals(4_096, resolved.effectiveContextTokens());
        assertTrue(resolved.reasons().contains(ReasonCode.CONTEXT_REDUCED));
    }

    @Test
    void unavailableNativeToolsCannotSurviveTheObservedTemplate() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                MODEL_PATH, profile.alias(), "llama_cpp", "embedded-template",
                MatchState.MATCHED, ToolMode.CHAT_ONLY, 8_192,
                Set.of(Modality.TEXT), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.NATIVE_TOOLS_UNAVAILABLE));
    }

    @Test
    void mismatchedTemplateCannotInheritNativeTools() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                MODEL_PATH, profile.alias(), "llama_cpp", "generic-template",
                MatchState.MISMATCHED, ToolMode.NATIVE, 8_192,
                Set.of(Modality.TEXT), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.UNVERIFIED, resolved.resolution());
        assertEquals(ToolMode.CHAT_ONLY, resolved.effectiveToolMode());
        assertEquals("generic-template", resolved.observedFacts().chatTemplate());
        assertTrue(resolved.reasons().contains(ReasonCode.TEMPLATE_MISMATCH));
    }

    @Test
    void textToolProfileCannotExpandToNativeCalling() {
        var profile = LlamaCppModelCatalog.byAlias("deepseek-v2lite-q4km").orElseThrow();
        var request = exactRequest(profile, "C:/models/" + profile.artifactFile(),
                profile.runtime().compatibleEngineLanes().getFirst());
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                request.configured().canonicalModelPath(), profile.alias(), "llama_cpp", "deepseek-template",
                MatchState.MATCHED, ToolMode.NATIVE, profile.runtime().contextTokens(),
                Set.of(Modality.TEXT), "offload observed");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(ToolMode.TEXT_TOOL_PROMPT, resolved.effectiveToolMode());
        assertTrue(resolved.reasons().contains(ReasonCode.TOOL_CAPABILITY_CAPPED));
    }

    @Test
    void missingTextModalityRefusesTheRuntimeIdentity() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                MODEL_PATH, profile.alias(), "llama_cpp", "embedded-template",
                MatchState.MATCHED, ToolMode.NATIVE, 8_192,
                Set.of(Modality.IMAGE), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertTrue(resolved.effectiveModalities().isEmpty());
        assertTrue(resolved.reasons().contains(ReasonCode.TEXT_MODALITY_UNAVAILABLE));
    }

    @Test
    void observedExtraModalitiesAreIgnoredRatherThanClaimed() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var observed = new LlamaCppProfileResolver.ObservedServerFacts(
                ObservationState.AVAILABLE,
                MODEL_PATH, profile.alias(), "llama_cpp", "embedded-template",
                MatchState.MATCHED, ToolMode.NATIVE, 8_192,
                Set.of(Modality.TEXT, Modality.IMAGE), "49 layers offloaded");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                observed, request.hardware()));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(Set.of(Modality.TEXT), resolved.effectiveModalities());
        assertTrue(resolved.reasons().contains(ReasonCode.UNDECLARED_MODALITY_IGNORED));
    }

    @Test
    void hardwareSupportDoesNotRewriteModelDisposition() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var hardware = new LlamaCppProfileResolver.HardwareFacts(
                HardwareSupportState.OUTSIDE_MEASURED_SUPPORT, "different-gpu-lane");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                request.observed(), hardware));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(profile.disposition(), resolved.disposition());
        assertEquals(HardwareSupportState.OUTSIDE_MEASURED_SUPPORT, resolved.hardwareSupport());
        assertTrue(resolved.reasons().contains(ReasonCode.OUTSIDE_MEASURED_HARDWARE));
    }

    @Test
    void measuredHardwareWithoutAPacketIdentityIsReportedAsUnverified() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var hardware = new LlamaCppProfileResolver.HardwareFacts(
                HardwareSupportState.MEASURED_SUPPORTED, "");

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                request.observed(), hardware));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(profile.disposition(), resolved.disposition());
        assertEquals(HardwareSupportState.UNVERIFIED, resolved.hardwareSupport());
        assertTrue(resolved.reasons().contains(ReasonCode.HARDWARE_UNVERIFIED));
    }

    @Test
    void missingRequiredReasoningArgumentsRefusesTheQualifiedProfile() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var configured = new LlamaCppProfileResolver.ConfiguredFacts(
                profile.alias(), MODEL_PATH, profile.runtime().contextTokens(),
                profile.toolMode(), profile.runtime().reasoningMode(), List.of(), true);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), configured, request.artifact(), request.engine(),
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.REFUSED, resolved.resolution());
        assertTrue(resolved.reasons().contains(ReasonCode.REQUIRED_LAUNCH_ARGUMENTS_MISSING));
    }

    @Test
    void observedManagedDownloadPathCanBindBlankConfiguredPathToExactArtifact() {
        var profile = qwen3();
        var request = exactRequest(profile);
        var configured = new LlamaCppProfileResolver.ConfiguredFacts(
                profile.alias(), "", profile.runtime().contextTokens(),
                profile.toolMode(), profile.runtime().reasoningMode(),
                profile.runtime().managedServerArgs(), true);

        var resolved = LlamaCppProfileResolver.resolve(new LlamaCppProfileResolver.Request(
                request.declaredProfile(), configured, request.artifact(), request.engine(),
                request.observed(), request.hardware()));

        assertEquals(ResolutionState.QUALIFIED, resolved.resolution());
        assertEquals(MatchState.MATCHED, resolved.servedIdentity());
        assertEquals(ToolMode.NATIVE, resolved.effectiveToolMode());
    }

    @Test
    void degradedObservedFactsCanOnlyPreserveOrNarrowTheDeclaredCeiling() {
        var profile = qwen3();
        var baseline = LlamaCppProfileResolver.resolve(exactRequest(profile));
        var request = exactRequest(profile);
        List<LlamaCppProfileResolver.Request> degradations = List.of(
                replaceArtifact(request, new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.UNCHECKED, MODEL_PATH,
                        profile.artifactSizeBytes(), -1, profile.artifactSha256(), "", "")),
                new LlamaCppProfileResolver.Request(
                        request.declaredProfile(), request.configured(), request.artifact(),
                        new LlamaCppProfileResolver.EngineFacts(LANE, "", MatchState.UNVERIFIED),
                        request.observed(), request.hardware()),
                new LlamaCppProfileResolver.Request(
                        request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                        LlamaCppProfileResolver.ObservedServerFacts.unavailable(), request.hardware()),
                new LlamaCppProfileResolver.Request(
                        request.declaredProfile(), request.configured(), request.artifact(), request.engine(),
                        new LlamaCppProfileResolver.ObservedServerFacts(
                                ObservationState.AVAILABLE, MODEL_PATH, profile.alias(), "llama_cpp",
                                "embedded-template",
                                MatchState.MATCHED, ToolMode.CHAT_ONLY, 4_096,
                                Set.of(Modality.TEXT, Modality.IMAGE), ""),
                        request.hardware()));

        for (var degradation : degradations) {
            var resolved = LlamaCppProfileResolver.resolve(degradation);
            assertTrue(resolved.effectiveToolMode().rank() <= baseline.effectiveToolMode().rank(),
                    resolved.toString());
            assertTrue(resolved.effectiveContextTokens() <= baseline.effectiveContextTokens(),
                    resolved.toString());
            assertFalse(moreTrusted(resolved.resolution(), baseline.resolution()), resolved.toString());
            assertFalse(moreTrusted(resolved.disposition(), baseline.disposition()), resolved.toString());
            assertTrue(resolved.effectiveModalities().size() <= baseline.effectiveModalities().size(),
                    resolved.toString());
        }
    }

    private static LlamaCppModelCatalog.Profile qwen3() {
        return LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
    }

    private static LlamaCppModelCatalog.Profile withRequestLayout(
            LlamaCppModelCatalog.Profile source,
            RequestMessageLayout layout) {
        var runtime = source.runtime();
        return new LlamaCppModelCatalog.Profile(
                source.alias(),
                source.artifact(),
                new LlamaCppModelCatalog.RuntimeDefaults(
                        runtime.architecture(), runtime.parameterShape(), runtime.nativeCalling(),
                        runtime.toolMode(), runtime.reasoningMode(), runtime.chatTemplate(),
                        runtime.contextTokens(), runtime.maxContextTokens(), runtime.samplingProfile(),
                        layout, runtime.managedServerArgs(), runtime.compatibleEngineLanes(),
                        runtime.hardwareEnvelope(), runtime.requiresLocalModelPath()),
                source.qualification(), source.guidePath(), source.ramGuidance());
    }

    private static LlamaCppProfileResolver.Request exactRequest(LlamaCppModelCatalog.Profile profile) {
        return exactRequest(profile, MODEL_PATH, LANE);
    }

    private static LlamaCppProfileResolver.Request exactRequest(
            LlamaCppModelCatalog.Profile profile,
            String modelPath,
            String lane) {
        return new LlamaCppProfileResolver.Request(
                Optional.of(profile),
                new LlamaCppProfileResolver.ConfiguredFacts(
                        profile.alias(), modelPath, profile.runtime().contextTokens(),
                        profile.toolMode(), profile.runtime().reasoningMode(),
                        profile.runtime().managedServerArgs(), true),
                new LlamaCppProfileResolver.ArtifactIdentity(
                        ArtifactState.EXACT, modelPath,
                        profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                        profile.artifactSha256(), profile.artifactSha256(), "verified-cache-entry"),
                new LlamaCppProfileResolver.EngineFacts(lane, "engine-sha", MatchState.MATCHED),
                new LlamaCppProfileResolver.ObservedServerFacts(
                        ObservationState.AVAILABLE,
                        modelPath, profile.alias(), "llama_cpp", "embedded-template",
                        MatchState.MATCHED,
                        profile.nativeCalling() ? ToolMode.NATIVE : ToolMode.TEXT_TOOL_PROMPT,
                        profile.runtime().contextTokens(), Set.of(Modality.TEXT), "offload observed"),
                new LlamaCppProfileResolver.HardwareFacts(
                        HardwareSupportState.MEASURED_SUPPORTED, "reference-lane-packet"));
    }

    private static LlamaCppProfileResolver.Request replaceArtifact(
            LlamaCppProfileResolver.Request request,
            LlamaCppProfileResolver.ArtifactIdentity artifact) {
        return new LlamaCppProfileResolver.Request(
                request.declaredProfile(), request.configured(), artifact, request.engine(),
                request.observed(), request.hardware());
    }

    private static boolean moreTrusted(ResolutionState candidate, ResolutionState ceiling) {
        return candidate.rank() > ceiling.rank();
    }

    private static boolean moreTrusted(
            LlamaCppModelCatalog.Disposition candidate,
            LlamaCppModelCatalog.Disposition ceiling) {
        return dispositionRank(candidate) > dispositionRank(ceiling);
    }

    private static int dispositionRank(LlamaCppModelCatalog.Disposition disposition) {
        return switch (disposition) {
            case ACCEPTED_BETA -> 2;
            case EXPERIMENTAL_SELECTABLE -> 1;
            case CUSTOM_UNVERIFIED, REJECTED_TOOL_USE, LAB_ONLY -> 0;
        };
    }
}
