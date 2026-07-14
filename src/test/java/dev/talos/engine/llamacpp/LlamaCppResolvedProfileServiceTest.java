package dev.talos.engine.llamacpp;

import dev.talos.core.Config;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppResolvedProfileServiceTest {
    @TempDir
    Path temp;

    @Test
    void matchingLiveFactsProjectQualifiedNativeCapabilities() {
        LlamaCppConfig config = config("qwen3-8b", "managed", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var service = service(config, exactArtifact(profile), matchedEngine(profile), availableProps(modelPath(), true));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(LlamaCppProfileResolver.MatchState.MATCHED, snapshot.profile().servedIdentity());
        assertEquals(LlamaCppPropsClient.State.AVAILABLE, snapshot.propsState());
        assertEquals("props-hash", snapshot.propsResponseSha256());
        ModelIdentitySnapshot identity = snapshot.identity();
        assertEquals("qwen3-8b", identity.configuredModel());
        assertEquals("qwen3-8b", identity.declaredProfile());
        assertEquals("qwen3-8b", identity.resolvedProfile());
        assertEquals("Qwen3-8B-Q4_K_M.gguf", identity.observedModelFile());
        assertEquals("QUALIFIED", identity.resolution());
        assertEquals("NATIVE", identity.effectiveToolMode());
        assertTrue(identity.qualified());
    }

    @Test
    void matchingGraniteFactsProjectTheDurableExperimentalProfile() {
        var profile = LlamaCppModelCatalog.byAlias("granite-4.1-3b").orElseThrow();
        Path graniteModel = temp.resolve("models").resolve(profile.artifactFile()).toAbsolutePath().normalize();
        LlamaCppConfig config = config(
                profile.alias(), "managed", graniteModel, serverPath(), true, List.of());
        var service = service(
                config,
                exactArtifact(profile, graniteModel),
                matchedEngine(profile),
                availableProps(graniteModel, true));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                snapshot.profile().disposition());
        assertEquals("granite-4.1-3b", snapshot.identity().configuredModel());
        assertEquals("granite-4.1-3b", snapshot.identity().declaredProfile());
        assertEquals("granite-4.1-3b", snapshot.identity().resolvedProfile());
        assertEquals("granite-4.1-3b-Q4_K_M.gguf", snapshot.identity().observedModelFile());
        assertEquals("experimental selectable", snapshot.identity().disposition());
        assertEquals("NATIVE", snapshot.identity().effectiveToolMode());
        assertTrue(snapshot.identity().qualified());
        assertTrue(!snapshot.identity().reasonCodes().contains("QUALIFICATION_CANDIDATE"));
    }

    @Test
    void matchingGraniteEightBFactsProjectTheExactExperimentalProfile() {
        var profile = LlamaCppModelCatalog.byAlias("granite-4.1-8b").orElseThrow();
        Path model = temp.resolve("models").resolve(profile.artifactFile()).toAbsolutePath().normalize();
        LlamaCppConfig config = config(
                profile.alias(), "managed", model, serverPath(), true, List.of());
        var service = service(
                config,
                exactArtifact(profile, model),
                matchedEngine(profile),
                availableProps(model, true));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                snapshot.profile().disposition());
        assertEquals("granite-4.1-8b", snapshot.identity().configuredModel());
        assertEquals("granite-4.1-8b", snapshot.identity().declaredProfile());
        assertEquals("granite-4.1-8b", snapshot.identity().resolvedProfile());
        assertEquals("granite-4.1-8b-Q4_K_M.gguf", snapshot.identity().observedModelFile());
        assertEquals("experimental selectable", snapshot.identity().disposition());
        assertEquals("NATIVE", snapshot.identity().effectiveToolMode());
        assertEquals(8192, snapshot.identity().effectiveContextTokens());
        assertTrue(snapshot.identity().qualified());
        assertTrue(!snapshot.identity().reasonCodes().contains("QUALIFICATION_CANDIDATE"));
    }

    @Test
    void matchingQwen3FourBFactsProjectTheControlledExperimentalProfile() {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-4b").orElseThrow();
        Path model = temp.resolve("models").resolve(profile.artifactFile()).toAbsolutePath().normalize();
        LlamaCppConfig config = config(
                profile.alias(), "managed", model, serverPath(), true, List.of("--reasoning", "off"));
        var service = service(
                config,
                exactArtifact(profile, model),
                matchedEngine(profile),
                availableProps(model, true));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                snapshot.profile().disposition());
        assertEquals("qwen3-4b", snapshot.identity().configuredModel());
        assertEquals("qwen3-4b", snapshot.identity().declaredProfile());
        assertEquals("qwen3-4b", snapshot.identity().resolvedProfile());
        assertEquals("qwen3-4b-instruct-2507-Q4_K_M.gguf", snapshot.identity().observedModelFile());
        assertEquals("experimental selectable", snapshot.identity().disposition());
        assertEquals("NATIVE", snapshot.identity().effectiveToolMode());
        assertEquals(8192, snapshot.identity().effectiveContextTokens());
        assertTrue(snapshot.identity().qualified());
        assertTrue(!snapshot.identity().reasonCodes().contains("QUALIFICATION_CANDIDATE"));
    }

    @Test
    void qwen14Selected16kContextProjectsQualifiedIdentity() {
        var profile = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        Path model = temp.resolve("models").resolve(profile.artifactFile()).toAbsolutePath().normalize();
        LlamaCppConfig config = config(
                profile.alias(), "managed", model, serverPath(), true, List.of(), 16_384);
        var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.EXACT,
                model.toString(), profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                profile.artifactSha256(), profile.artifactSha256(), "verified-record");
        var service = service(
                config,
                artifact,
                matchedEngine(profile),
                availableProps(model, true, 16_384));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(16_384, snapshot.profile().effectiveContextTokens());
        assertEquals("qwen2.5-coder-14b", snapshot.identity().resolvedProfile());
        assertTrue(snapshot.identity().qualified());
    }

    @Test
    void servedModelMismatchRefusesAndRemovesTools() {
        LlamaCppConfig config = config("qwen3-8b", "managed", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var props = availableProps(temp.resolve("different.gguf"), true);
        var service = service(config, exactArtifact(profile), matchedEngine(profile), props);

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.REFUSED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
        assertTrue(snapshot.profile().reasons().contains(
                LlamaCppProfileResolver.ReasonCode.SERVER_MODEL_MISMATCH));
        assertTrue(snapshot.identity().resolvedProfile().isBlank());
        assertEquals("REFUSED", snapshot.identity().resolution());
        assertTrue(!snapshot.chatAvailable());
    }

    @Test
    void templateWithoutToolCapabilityNarrowsNativeProfileToChatOnly() {
        LlamaCppConfig config = config("qwen3-8b", "managed", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var service = service(config, exactArtifact(profile), matchedEngine(profile), availableProps(modelPath(), false));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
        assertTrue(snapshot.profile().reasons().contains(
                LlamaCppProfileResolver.ReasonCode.TEMPLATE_MISMATCH));
    }

    @Test
    void customProfileRemainsChatCapableButUnverified() {
        LlamaCppConfig config = config("my-local-model", "managed", modelPath(), serverPath(), true, List.of());
        var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.CUSTOM,
                modelPath().toString(), -1, 123, "", "", "");
        var service = service(config, artifact,
                new LlamaCppProfileResolver.EngineFacts("", "exe-hash",
                        LlamaCppProfileResolver.MatchState.UNVERIFIED),
                availableProps(modelPath(), true));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.UNVERIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED, snapshot.profile().disposition());
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
        assertTrue(snapshot.chatAvailable());
    }

    @Test
    void exactQualificationCandidateCanExposeNativeToolsWithoutCatalogPromotion() {
        LlamaCppConfig config = config(
                "test-qualification-candidate", "managed", modelPath(), serverPath(), true, List.of());
        var candidate = qualificationCandidateProfile();
        var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.EXACT,
                modelPath().toString(), candidate.artifactSizeBytes(), candidate.artifactSizeBytes(),
                candidate.artifactSha256(), candidate.artifactSha256(), "candidate-record");
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> artifact,
                base -> availableProps(modelPath(), true),
                (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        candidate.runtime().compatibleEngineLanes().getFirst(),
                        "engine-hash", LlamaCppProfileResolver.MatchState.MATCHED),
                (cfg, home) -> Optional.of(new LlamaCppQualificationCandidateLoader.LoadedCandidate(
                        candidate, "b".repeat(64), artifact, "engine-hash")));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.QUALIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.NATIVE, snapshot.profile().effectiveToolMode());
        assertEquals(LlamaCppModelCatalog.Disposition.LAB_ONLY, snapshot.profile().disposition());
        assertTrue(snapshot.identity().reasonCodes().contains("QUALIFICATION_CANDIDATE"));
        assertEquals("test-qualification-candidate", snapshot.identity().resolvedProfile());
        assertTrue(LlamaCppModelCatalog.byAlias("test-qualification-candidate").isEmpty());
    }

    @Test
    void changedEngineAfterQualificationDeclarationLoadIsRefused() {
        LlamaCppConfig config = config(
                "test-qualification-candidate", "managed", modelPath(), serverPath(), true, List.of());
        var candidate = qualificationCandidateProfile();
        var artifact = new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.EXACT,
                modelPath().toString(), candidate.artifactSizeBytes(), candidate.artifactSizeBytes(),
                candidate.artifactSha256(), candidate.artifactSha256(), "candidate-record");
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> artifact,
                base -> availableProps(modelPath(), true),
                (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        candidate.runtime().compatibleEngineLanes().getFirst(),
                        "changed-engine", LlamaCppProfileResolver.MatchState.MATCHED),
                (cfg, home) -> Optional.of(new LlamaCppQualificationCandidateLoader.LoadedCandidate(
                        candidate, "b".repeat(64), artifact, "expected-engine")));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.REFUSED, snapshot.profile().resolution());
        assertTrue(snapshot.identity().reasonCodes().contains("ENGINE_EXECUTABLE_MISMATCH"));
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
    }

    @Test
    void changedModelAfterQualificationDeclarationLoadIsRefused() {
        LlamaCppConfig config = config(
                "test-qualification-candidate", "managed", modelPath(), serverPath(), true, List.of());
        var candidate = qualificationCandidateProfile();
        var changedArtifact = new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.SHA256_MISMATCH,
                modelPath().toString(), candidate.artifactSizeBytes(), candidate.artifactSizeBytes(),
                candidate.artifactSha256(), "c".repeat(64), "");
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> changedArtifact,
                base -> availableProps(modelPath(), true),
                (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        candidate.runtime().compatibleEngineLanes().getFirst(),
                        "engine-hash", LlamaCppProfileResolver.MatchState.MATCHED),
                (cfg, home) -> Optional.of(new LlamaCppQualificationCandidateLoader.LoadedCandidate(
                        candidate, "b".repeat(64), changedArtifact, "engine-hash")));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.REFUSED, snapshot.profile().resolution());
        assertTrue(snapshot.identity().reasonCodes().contains("ARTIFACT_SHA256_MISMATCH"));
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
    }

    @Test
    void qualificationCandidateCannotShadowDurableCatalogProfile() {
        LlamaCppConfig config = config("qwen3-8b", "managed", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> exactArtifact(profile),
                base -> availableProps(modelPath(), true),
                (cfg, declared, observed, verify) -> matchedEngine(profile),
                (cfg, home) -> { throw new AssertionError("catalog profile must win"); });

        var snapshot = service.resolveAvailable(true);

        assertEquals("qwen3-8b", snapshot.identity().resolvedProfile());
        assertTrue(!snapshot.identity().reasonCodes().contains("QUALIFICATION_CANDIDATE"));
    }

    @Test
    void connectOnlyWithoutPropsStaysChatCapableAndUnverified() {
        LlamaCppConfig config = config("qwen3-8b", "connect_only", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var service = service(config, exactArtifact(profile),
                new LlamaCppProfileResolver.EngineFacts("", "",
                        LlamaCppProfileResolver.MatchState.UNVERIFIED),
                LlamaCppPropsClient.Result.unavailable("endpoint absent"));

        var snapshot = service.resolveAvailable(false);

        assertEquals(LlamaCppProfileResolver.ResolutionState.UNVERIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
        assertTrue(snapshot.chatAvailable());
        assertEquals(LlamaCppPropsClient.State.UNAVAILABLE, snapshot.propsState());
        assertEquals("UNVERIFIED", snapshot.identity().resolution());
        assertTrue(snapshot.identity().resolvedProfile().isBlank());
    }

    @Test
    void connectOnlyKnownProfileWithoutLocalModelPathIsUnverifiedRatherThanRefused() {
        Config raw = new Config();
        raw.data.put("llm", Map.of("default_backend", "llama_cpp", "model", "gpt-oss-20b"));
        raw.data.put("engines", Map.of("llama_cpp", Map.of(
                "mode", "connect_only",
                "model", "gpt-oss-20b",
                "host", "http://127.0.0.1",
                "port", 18115,
                "context", 8192)));
        raw.data.put("tools", Map.of("native_calling", true));
        LlamaCppConfig config = LlamaCppConfig.from(raw);
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> {
                    throw new AssertionError("connect-only config without a local path must not verify local bytes");
                },
                base -> LlamaCppPropsClient.Result.unavailable("endpoint absent"),
                (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        "", "", LlamaCppProfileResolver.MatchState.UNVERIFIED));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ResolutionState.UNVERIFIED, snapshot.profile().resolution());
        assertEquals(LlamaCppProfileResolver.ArtifactState.UNCHECKED, snapshot.profile().artifactState());
        assertEquals(LlamaCppProfileResolver.ToolMode.CHAT_ONLY, snapshot.profile().effectiveToolMode());
        assertTrue(snapshot.chatAvailable());
    }

    @Test
    void connectOnlyPropsCannotSelectAHostFileForArtifactVerification() throws Exception {
        Path untrustedObservedPath = temp.resolve("private.txt");
        java.nio.file.Files.writeString(untrustedObservedPath, "must not be read");
        Config raw = new Config();
        raw.data.put("llm", Map.of("default_backend", "llama_cpp", "model", "qwen3-8b"));
        raw.data.put("engines", Map.of("llama_cpp", Map.of(
                "mode", "connect_only",
                "model", "qwen3-8b",
                "host", "http://127.0.0.1",
                "port", 18115,
                "context", 8192,
                "server_args", List.of("--reasoning", "off"))));
        raw.data.put("tools", Map.of("native_calling", true));
        LlamaCppConfig config = LlamaCppConfig.from(raw);
        var service = new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> {
                    throw new AssertionError("connect-only /props must not select a local file to hash");
                },
                base -> availableProps(untrustedObservedPath, true),
                (cfg, declared, observed, verify) -> new LlamaCppProfileResolver.EngineFacts(
                        "", "", LlamaCppProfileResolver.MatchState.UNVERIFIED));

        var snapshot = service.resolveAvailable(true);

        assertEquals(LlamaCppProfileResolver.ArtifactState.UNCHECKED, snapshot.profile().artifactState());
        assertEquals(LlamaCppProfileResolver.MatchState.UNVERIFIED, snapshot.profile().servedIdentity());
        assertEquals(LlamaCppProfileResolver.ResolutionState.UNVERIFIED, snapshot.profile().resolution());
        assertTrue(snapshot.chatAvailable());
    }

    @Test
    void prelaunchRejectsIncompatibleManagedEngineLane() {
        LlamaCppConfig config = config("qwen3-8b", "managed", modelPath(), serverPath(), true,
                List.of("--reasoning", "off"));
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        var incompatible = new LlamaCppProfileResolver.EngineFacts(
                "win-x64-cpu:b9918", "exe-hash", LlamaCppProfileResolver.MatchState.MATCHED);
        var service = service(config, exactArtifact(profile), incompatible,
                LlamaCppPropsClient.Result.unavailable("not started"));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::requirePrelaunch);
        assertTrue(error.getMessage().contains("ENGINE_LANE_INCOMPATIBLE"), error.getMessage());
    }

    private LlamaCppResolvedProfileService service(
            LlamaCppConfig config,
            LlamaCppProfileResolver.ArtifactIdentity artifact,
            LlamaCppProfileResolver.EngineFacts engine,
            LlamaCppPropsClient.Result props) {
        return new LlamaCppResolvedProfileService(
                config,
                temp.resolve("home"),
                (path, alias, expected, home, verify) -> artifact,
                base -> props,
                (cfg, declared, observed, verify) -> engine);
    }

    private LlamaCppProfileResolver.ArtifactIdentity exactArtifact(LlamaCppModelCatalog.Profile profile) {
        return new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.EXACT,
                modelPath().toString(),
                profile.artifactSizeBytes(),
                profile.artifactSizeBytes(),
                profile.artifactSha256(),
                profile.artifactSha256(),
                "verified-record");
    }

    private LlamaCppProfileResolver.EngineFacts matchedEngine(LlamaCppModelCatalog.Profile profile) {
        return new LlamaCppProfileResolver.EngineFacts(
                profile.runtime().compatibleEngineLanes().getFirst(),
                "exe-hash",
                LlamaCppProfileResolver.MatchState.MATCHED);
    }

    private LlamaCppPropsClient.Result availableProps(Path model, boolean tools) {
        return availableProps(model, tools, 8192);
    }

    private LlamaCppPropsClient.Result availableProps(Path model, boolean tools, int context) {
        return LlamaCppPropsClient.Result.available(new LlamaCppPropsClient.Props(
                model.toString(),
                "embedded template",
                tools,
                tools,
                false,
                false,
                context,
                "b9918-test",
                "props-hash"));
    }

    private LlamaCppConfig config(
            String alias,
            String mode,
            Path model,
            Path server,
            boolean nativeCalling,
            List<String> args) {
        return config(alias, mode, model, server, nativeCalling, args, 8192);
    }

    private LlamaCppConfig config(
            String alias,
            String mode,
            Path model,
            Path server,
            boolean nativeCalling,
            List<String> args,
            int context) {
        Config cfg = new Config();
        cfg.data.put("llm", Map.of("default_backend", "llama_cpp", "model", alias));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of(
                "mode", mode,
                "server_path", server.toString(),
                "model_path", model.toString(),
                "model", alias,
                "host", "http://127.0.0.1",
                "port", 18115,
                "context", context,
                "jinja", true,
                "server_args", args)));
        cfg.data.put("tools", Map.of("native_calling", nativeCalling));
        return LlamaCppConfig.from(cfg);
    }

    private Path modelPath() {
        return temp.resolve("models").resolve("Qwen3-8B-Q4_K_M.gguf").toAbsolutePath().normalize();
    }

    private Path serverPath() {
        return temp.resolve(".talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe")
                .toAbsolutePath().normalize();
    }

    private LlamaCppModelCatalog.Profile qualificationCandidateProfile() {
        return new LlamaCppModelCatalog.Profile(
                "test-qualification-candidate",
                new LlamaCppModelCatalog.ModelArtifact(
                        "ibm-granite/granite-4.1-3b-GGUF", "a".repeat(40),
                        "granite-4.1-3b-Q4_K_M.gguf", "b".repeat(64), 123L, "Apache-2.0"),
                new LlamaCppModelCatalog.RuntimeDefaults(
                        "granite", "3.4B dense", true, "native", "none",
                        "embedded Granite role template", 8192, 8192,
                        "Talos standard request controls", List.of(),
                        List.of("win-x64-cuda-13.3:b9918"), "qualification host", true),
                new LlamaCppModelCatalog.QualificationEvidence(
                        LlamaCppModelCatalog.Disposition.LAB_ONLY, false, false, 5,
                        "qualification-candidate:test", "3df57f2f" + "0".repeat(32),
                        "not-scored", java.time.LocalDate.of(1970, 1, 1),
                        "Qualification candidate declaration, no support claim."),
                "", "Qualification host only");
    }

    private LlamaCppProfileResolver.ArtifactIdentity exactArtifact(
            LlamaCppModelCatalog.Profile profile,
            Path model) {
        return new LlamaCppProfileResolver.ArtifactIdentity(
                LlamaCppProfileResolver.ArtifactState.EXACT,
                model.toString(), profile.artifactSizeBytes(), profile.artifactSizeBytes(),
                profile.artifactSha256(), profile.artifactSha256(), "verified-record");
    }
}
