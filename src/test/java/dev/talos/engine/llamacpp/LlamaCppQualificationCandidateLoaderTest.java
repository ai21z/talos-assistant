package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.Config;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.RequestMessageLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppQualificationCandidateLoaderTest {
    private static final String COMMIT = "3df57f2f" + "0".repeat(32);

    @TempDir
    Path temp;

    @Test
    void exactContainedDeclarationCreatesNonSelectableLabCapabilityCeiling() throws Exception {
        Fixture fixture = fixture();

        var loaded = loader().load(fixture.settings(), fixture.config(), fixture.talosHome()).orElseThrow();

        assertEquals("granite-4.1-3b", loaded.profile().alias());
        assertEquals("native", loaded.profile().runtime().toolMode());
        assertEquals(RequestMessageLayout.PRESERVE_AS_WRITTEN,
                loaded.profile().runtime().requestLayout());
        assertTrue(loaded.profile().runtime().nativeCalling());
        assertEquals(LlamaCppModelCatalog.Disposition.LAB_ONLY, loaded.profile().disposition());
        assertFalse(loaded.profile().selectable());
        assertFalse(loaded.profile().wizardDownload());
        assertEquals(5, loaded.profile().qualification().protocolRevision());
        assertEquals(fixture.rulesSha256(), loaded.declarationSha256());
        assertEquals(LlamaCppProfileResolver.ArtifactState.EXACT,
                loaded.artifactIdentity().state());
        assertEquals(sha256(fixture.engine()), loaded.engineExecutableSha256());
    }

    @Test
    void frozenCalibrationCanExplicitlySelectSingleLeadingSystemLayout() throws Exception {
        Fixture fixture = fixture(Map.of("requestLayout", "SINGLE_LEADING_SYSTEM"));

        var loaded = loader().load(fixture.settings(), fixture.config(), fixture.talosHome()).orElseThrow();

        assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                loaded.profile().runtime().requestLayout());
    }

    @Test
    void unknownRequestLayoutIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("requestLayout", "guess-from-model-name"));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void absentSettingsLeaveQualificationModeDisabled() throws Exception {
        Fixture fixture = fixture();

        assertTrue(loader().load(
                LlamaCppQualificationCandidateLoader.Settings.disabled(),
                fixture.config(), fixture.talosHome()).isEmpty());
    }

    @Test
    void partialSettingsAreRefused() throws Exception {
        Fixture fixture = fixture();
        var partial = new LlamaCppQualificationCandidateLoader.Settings(
                fixture.root(), fixture.rules(), "");

        assertThrows(IllegalStateException.class,
                () -> loader().load(partial, fixture.config(), fixture.talosHome()));
    }

    @Test
    void calibrationOutsideCampaignRootIsRefused() throws Exception {
        Fixture fixture = fixture();
        Path escaped = temp.resolve("escaped.json");
        Files.copy(fixture.rules(), escaped);
        var settings = new LlamaCppQualificationCandidateLoader.Settings(
                fixture.root(), escaped, sha256(escaped));

        assertThrows(IllegalStateException.class,
                () -> loader().load(settings, fixture.config(), fixture.talosHome()));
    }

    @Test
    void campaignRootMustOwnIsolatedUserHome() throws Exception {
        Fixture fixture = fixture();

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), temp.resolve("other/.talos")));
    }

    @Test
    void wrongCalibrationHashIsRefused() throws Exception {
        Fixture fixture = fixture();
        var settings = new LlamaCppQualificationCandidateLoader.Settings(
                fixture.root(), fixture.rules(), "a".repeat(64));

        assertThrows(IllegalStateException.class,
                () -> loader().load(settings, fixture.config(), fixture.talosHome()));
    }

    @Test
    void malformedCalibrationIsRefused() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.rules(), "{not-json");
        var settings = new LlamaCppQualificationCandidateLoader.Settings(
                fixture.root(), fixture.rules(), sha256(fixture.rules()));

        assertThrows(IllegalStateException.class,
                () -> loader().load(settings, fixture.config(), fixture.talosHome()));
    }

    @Test
    void wrongAliasIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("candidateAlias", "different-model"));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void wrongArtifactBytesAreRefused() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.model(), "changed-model");

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void wrongEngineBytesAreRefused() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.engine(), "changed-engine");

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void wrongContextIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("contextTokens", 4096));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void nonNativeToolModeIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("toolMode", "text-tool-prompt"));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void wrongTalosCommitIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("talosCommit", "f".repeat(40)));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void launchShapeDriftIsRefused() throws Exception {
        Fixture fixture = fixture(Map.of("launchArguments", List.of("-m", "wrong.gguf")));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    @Test
    void verificationLaunchUsesTheFrozenVerificationArguments() throws Exception {
        Fixture fixture = fixture();
        LlamaCppConfig verificationConfig = config(fixture.model(), fixture.engine(), true);

        var loaded = loader().load(
                fixture.settings(), verificationConfig, fixture.talosHome()).orElseThrow();

        assertEquals("granite-4.1-3b", loaded.profile().alias());
    }

    @Test
    void changedVerificationLaunchIsRefused() throws Exception {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode rules = (ObjectNode) mapper.readTree(fixture.rules().toFile());
        var verification = rules.withArray("verificationLaunchArguments");
        verification.set(verification.size() - 1, mapper.getNodeFactory().textNode("1"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(fixture.rules().toFile(), rules);
        var changedSettings = new LlamaCppQualificationCandidateLoader.Settings(
                fixture.root(), fixture.rules(), sha256(fixture.rules()));

        assertThrows(IllegalStateException.class, () -> loader().load(
                changedSettings,
                config(fixture.model(), fixture.engine(), true),
                fixture.talosHome()));
    }

    @Test
    void tunedConfigurationCannotUseTheStandardAdmissionOverlay() throws Exception {
        Fixture fixture = fixture(Map.of(
                "configurationKind", "TUNED",
                "baselineConfigurationId", "granite-standard-test"));

        assertThrows(IllegalStateException.class,
                () -> loader().load(fixture.settings(), fixture.config(), fixture.talosHome()));
    }

    private LlamaCppQualificationCandidateLoader loader() {
        return new LlamaCppQualificationCandidateLoader(() -> "3df57f2f");
    }

    private Fixture fixture() throws Exception {
        return fixture(Map.of());
    }

    private Fixture fixture(Map<String, Object> overrides) throws Exception {
        Path root = temp.resolve("campaign-" + System.nanoTime()).toAbsolutePath().normalize();
        Path talosHome = root.resolve("home/.talos");
        Path candidateRoot = root.resolve("candidates/granite-4.1-3b");
        Path model = root.resolve("models/granite-4.1-3b-Q4_K_M.gguf");
        Path engine = root.resolve("home/.talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe");
        Files.createDirectories(talosHome);
        Files.createDirectories(candidateRoot);
        Files.createDirectories(model.getParent());
        Files.createDirectories(engine.getParent());
        Files.writeString(model, "frozen-model");
        Files.writeString(engine, "frozen-engine");

        List<String> launch = List.of(
                "-m", model.toString().replace('\\', '/'),
                "-c", "8192", "--host", "127.0.0.1", "--port", "18161",
                "--jinja", "--alias", "granite-4.1-3b", "--parallel", "1", "--predict", "2048");
        List<String> verificationLaunch = new ArrayList<>(launch);
        verificationLaunch.add("-lv");
        verificationLaunch.add("4");
        LinkedHashMap<String, Object> rules = new LinkedHashMap<>();
        rules.put("schemaName", "talos.modelQualification.calibrationRules");
        rules.put("schemaVersion", 1);
        rules.put("configurationId", "granite-standard-test");
        rules.put("configurationKind", "STANDARD");
        rules.put("baselineConfigurationId", "");
        rules.put("candidateAlias", "granite-4.1-3b");
        rules.put("repository", "ibm-granite/granite-4.1-3b-GGUF");
        rules.put("revision", "a".repeat(40));
        rules.put("artifact", model.getFileName().toString());
        rules.put("artifactSizeBytes", Files.size(model));
        rules.put("artifactSha256", sha256(model));
        rules.put("license", "Apache-2.0");
        rules.put("architecture", "Granite dense text model");
        rules.put("totalParameters", 3_402_836_480L);
        rules.put("activeParameters", 3_402_836_480L);
        rules.put("talosCommit", COMMIT);
        rules.put("protocolVersion", 5);
        rules.put("llamaCppTag", "b9918");
        rules.put("executableSha256", sha256(engine));
        rules.put("backend", "cuda-13.3");
        rules.put("launchArguments", launch);
        rules.put("verificationLaunchArguments", verificationLaunch);
        rules.put("toolMode", "native");
        rules.put("chatTemplate", "embedded Granite role template");
        rules.put("reasoningBehavior", "thinking disabled");
        rules.put("contextTokens", 8192);
        rules.putAll(overrides);
        Path calibration = candidateRoot.resolve("calibration-rules.json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(calibration.toFile(), rules);
        String hash = sha256(calibration);
        LlamaCppConfig config = config(model, engine);
        return new Fixture(root, talosHome, calibration, model, engine, hash, config);
    }

    private LlamaCppConfig config(Path model, Path engine) {
        return config(model, engine, false);
    }

    private LlamaCppConfig config(Path model, Path engine, boolean verification) {
        Config cfg = new Config();
        cfg.data.put("llm", Map.of("default_backend", "llama_cpp", "model", "granite-4.1-3b"));
        cfg.data.put("engines", Map.of("llama_cpp", Map.ofEntries(
                Map.entry("mode", "managed"),
                Map.entry("server_path", engine.toString()),
                Map.entry("model_path", model.toString()),
                Map.entry("model", "granite-4.1-3b"),
                Map.entry("host", "http://127.0.0.1"),
                Map.entry("port", 18161),
                Map.entry("context", 8192),
                Map.entry("jinja", true),
                Map.entry("server_args", List.of()))));
        cfg.data.put("tools", Map.of("native_calling", true));
        if (verification) LlamaCppVerificationLaunch.mark(cfg);
        return LlamaCppConfig.from(cfg);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Fixture(
            Path root,
            Path talosHome,
            Path rules,
            Path model,
            Path engine,
            String rulesSha256,
            LlamaCppConfig config) {
        LlamaCppQualificationCandidateLoader.Settings settings() {
            return new LlamaCppQualificationCandidateLoader.Settings(root, rules, rulesSha256);
        }
    }
}
