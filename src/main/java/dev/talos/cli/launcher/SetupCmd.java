package dev.talos.cli.launcher;

import dev.talos.cli.setup.LlamaCppEngineInstaller;
import dev.talos.cli.setup.LlamaCppModelDownloader;
import dev.talos.cli.setup.SetupWizardEnvironmentProbe;
import dev.talos.cli.setup.SetupWizardPlanner;
import dev.talos.cli.setup.SetupWizardRenderer;
import dev.talos.cli.setup.SetupWizardRunner;
import dev.talos.core.Config;
import dev.talos.engine.llamacpp.LlamaCppModelProfiles;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "setup", description = "Configure Talos local model engines")
public class SetupCmd implements Callable<Integer> {
    @CommandLine.Option(names="--install-ollama", description="Legacy: install Ollama via winget")
    boolean install;
 
    @CommandLine.Option(names="--models", description="Legacy Ollama: comma-separated list to pull")
    String models;

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Setup topic. Use 'models' for model setup or 'wizard' for guided setup.")
    String topic;

    @CommandLine.Option(names = "--dry-run", description = "Render setup wizard decisions without installing, downloading, writing config, or starting models")
    boolean dryRun;

    @CommandLine.Option(names = "--profile", description = "Managed llama.cpp profile, for example qwen2.5-coder-14b, gpt-oss-20b, qwen36vf-q6k, or deepseek-v2lite-q4km")
    String profile;

    @CommandLine.Option(names = "--server-path", description = "Path to llama-server.exe")
    Path serverPath;

    @CommandLine.Option(names = "--model-path", description = "Path to a user-owned local GGUF model")
    Path modelPath;

    @CommandLine.Option(names = "--cache-dir", description = "Talos-owned HF_HOME directory for managed downloads")
    Path cacheDir;

    @CommandLine.Option(names = "--port", description = "Managed llama.cpp localhost port")
    int port = 18115;

    @CommandLine.Option(names = "--embed-profile", description = "Optional managed llama.cpp embedding profile, for example bge-m3")
    String embedProfile;

    @CommandLine.Option(names = "--embed-port", description = "Managed llama.cpp embedding localhost port")
    int embedPort = 18116;

    @CommandLine.Option(names = "--write", description = "Write ~/.talos/config.yaml")
    boolean write;

    @CommandLine.Option(names = "--force", description = "Overwrite existing config after writing a backup")
    boolean force;

    @CommandLine.Option(names = "--config", hidden = true)
    Path configPath;

    private static final Map<String, ModelProfile> PROFILES = profiles();

    public static String setupSummary() {
        return "Talos uses configurable local model engines. The default path is llama.cpp: "
                + "run `talos setup models` to configure a tested managed model profile, "
                + "or set engines.llama_cpp.server_path and engines.llama_cpp.model_path in ~/.talos/config.yaml. "
                + "Ollama remains available only when explicitly selected as the backend.";
    }

    public static String modelsHelp() {
        StringBuilder out = new StringBuilder("""
                Talos managed llama.cpp model setup

                Accepted beta stability profiles:
                """);
        appendProfiles(out, LlamaCppModelProfiles.SupportTier.ACCEPTED_BETA);
        out.append("""

                Experimental selectable profiles:
                """);
        appendProfiles(out, LlamaCppModelProfiles.SupportTier.EXPERIMENTAL_SELECTABLE);
        out.append("""

                Repeatable configure -> test -> guide recipe:
                  1. talos setup models --profile <name> --server-path C:/path/to/llama-server.exe --write
                  2. Restart Talos after the config write.
                  3. talos status --verbose
                  4. talos doctor --start
                  5. Save the doctor output as evidence before calling that local setup verified.

                Talos-managed download/cache:
                  talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
                  talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
                  talos setup models --profile qwen2.5-coder-14b --embed-profile bge-m3 --server-path C:/path/to/llama-server.exe --write

                Talos sets HF_HOME to ~/.talos/models/huggingface for these profiles, so llama.cpp stores
                Hugging Face downloads under .talos/models on first model start.

                User-owned GGUF path:
                  talos setup models --profile my-agent --server-path C:/path/to/llama-server.exe --model-path D:/models/agent.gguf --write

                Existing configs are backed up when --force is used.
                Switching managed GGUF profiles rewrites the active config; restart Talos after the write.
                """);
        return out.toString();
    }

    private static void appendProfiles(StringBuilder out, LlamaCppModelProfiles.SupportTier tier) {
        for (var profile : LlamaCppModelProfiles.profiles().values()) {
            if (profile.supportTier() != tier) continue;
            out.append("  ")
                    .append(profile.alias())
                    .append("\n")
                    .append("    source: ").append(profile.hfRepo()).append("\n")
                    .append("    file: ").append(profile.hfFile()).append("\n")
                    .append("    tool mode: ").append(profile.toolMode()).append("\n")
                    .append("    guide: ").append(profile.guidePath()).append("\n")
                    .append("    evidence: ").append(profile.evidenceSummary()).append("\n");
        }
    }

    public static String renderManagedLlamaCppProfileConfig(
            String profileName,
            Path serverPath,
            Path modelPath,
            Path cacheDir,
            int port) {
        return renderManagedLlamaCppProfileConfig(profileName, serverPath, modelPath, cacheDir, port, "", 18116);
    }

    public static String renderManagedLlamaCppProfileConfig(
            String profileName,
            Path serverPath,
            Path modelPath,
            Path cacheDir,
            int port,
            String embedProfile,
            int embedPort) {
        String normalizedProfile = normalizeProfile(profileName);
        boolean userOwnedModel = modelPath != null;
        ModelProfile known = PROFILES.get(normalizedProfile);
        if (!userOwnedModel && known == null) {
            throw new IllegalArgumentException("Unknown model profile: " + Objects.toString(profileName, ""));
        }
        String alias = userOwnedModel ? normalizedProfile : known.alias();
        String hfRepo = userOwnedModel ? "" : known.hfRepo();
        String hfFile = userOwnedModel ? "" : known.hfFile();
        String modelPathValue = userOwnedModel ? yamlPath(modelPath) : "";
        String hfCacheDir = userOwnedModel ? "" : yamlPath(cacheDir == null ? defaultHfCacheDir() : cacheDir);
        boolean nativeCalling = userOwnedModel || known.nativeCalling();
        EmbeddingSetupProfile embedding = embeddingProfile(embedProfile);
        String embeddingYaml = renderEmbeddingYaml(
                embedding,
                serverPath,
                cacheDir == null ? defaultHfCacheDir() : cacheDir,
                Math.max(1, embedPort));
        boolean vectorsEnabled = embedding != null;

        return """
                llm:
                  transport: "engine"
                  default_backend: "llama_cpp"
                  model: "%s"

                engines:
                  llama_cpp:
                    mode: "managed"
                    server_path: "%s"
                    model_path: "%s"
                    hf_repo: "%s"
                    hf_file: "%s"
                    hf_cache_dir: "%s"
                    model: "%s"
                    host: "http://127.0.0.1"
                    port: %d
                    context: 8192
                    jinja: true
                    server_args: []

                tools:
                  native_calling: %s

                %s

                rag:
                  vectors:
                    enabled: %s
                """.formatted(
                yamlScalar(alias),
                serverPath == null ? "" : yamlPath(serverPath),
                modelPathValue,
                yamlScalar(hfRepo),
                yamlScalar(hfFile),
                hfCacheDir,
                yamlScalar(alias),
                Math.max(1, port),
                nativeCalling,
                embeddingYaml.stripTrailing(),
                vectorsEnabled);
    }
 
    @Override public Integer call() {
        try {
            if ("wizard".equalsIgnoreCase(Objects.toString(topic, ""))) {
                return runWizard();
            }
            if ("models".equalsIgnoreCase(Objects.toString(topic, ""))) {
                runModelsSetup();
                return 0;
            }
            if (!install && (models == null || models.isBlank())) {
                System.out.println(setupSummary());
                return 0;
            }
            if (install) {
                new ProcessBuilder(
                        "winget", "install", "--exact", "Ollama.Ollama",
                        "--silent", "--accept-package-agreements", "--accept-source-agreements")
                        .inheritIO().start().waitFor();
            }
            if (models != null && !models.isBlank()) {
                for (String m : models.split(",")) {
                    String id = m.trim();
                    if (!id.isEmpty()) {
                        System.out.println("Pulling model: " + id);
                        new ProcessBuilder("ollama", "pull", id).inheritIO().start().waitFor();
                    }
                }
            }
            return 0;
        } catch (Exception e) {
            System.err.println("setup failed: " + e.getMessage());
            return 2;
        }
    }

    private int runWizard() {
        var snapshot = SetupWizardEnvironmentProbe.capture(configPath, serverPath);
        var plan = SetupWizardPlanner.plan(snapshot);
        if (dryRun) {
            System.out.println(SetupWizardRenderer.render(plan));
            return 0;
        }
        Path userHome = Path.of(System.getProperty("user.home"));
        var result = SetupWizardRunner.run(
                plan,
                System.in,
                System.out,
                (profileName, server, model, cache, setupPort) -> renderManagedLlamaCppProfileConfig(
                        profileName,
                        server,
                        model,
                        cache,
                        setupPort),
                cacheDir == null ? defaultHfCacheDir() : cacheDir,
                port,
                userHome,
                DoctorCmd.resolveWorkspace(null),
                new LlamaCppEngineInstaller()::install,
                new LlamaCppModelDownloader()::download,
                (writtenConfig, workspace, talosHome, doctorOut) -> DoctorCmd.run(
                        new Config(writtenConfig),
                        workspace,
                        talosHome,
                        true,
                        doctorOut));
        return result.exitCode();
    }

    private void runModelsSetup() throws Exception {
        if (!write) {
            System.out.println(modelsHelp());
            return;
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("--profile is required when writing model setup");
        }
        if (serverPath == null) {
            throw new IllegalArgumentException("--server-path is required when writing model setup");
        }
        if (!Files.isRegularFile(serverPath)) {
            throw new IllegalArgumentException("llama-server path is not a file: " + serverPath);
        }
        if (modelPath != null && !Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("model path is not a file: " + modelPath);
        }

        Path target = configPath == null ? defaultConfigPath() : configPath;
        if (Files.exists(target) && !force) {
            throw new IllegalArgumentException("config already exists: " + target
                    + ". Re-run with --force to replace it after a backup.");
        }

        String yaml = renderManagedLlamaCppProfileConfig(
                profile,
                serverPath,
                modelPath,
                cacheDir == null ? defaultHfCacheDir() : cacheDir,
                port,
                embedProfile,
                embedPort);

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target)) {
            Path backup = target.resolveSibling(target.getFileName() + ".bak-" + safeTimestamp());
            Files.copy(target, backup);
            System.out.println("Backed up existing config to " + backup);
        }
        Files.writeString(target, yaml, StandardCharsets.UTF_8);
        System.out.println("Wrote Talos model config: " + target);
        System.out.println("Profile: " + normalizeProfile(profile));
        if (modelPath == null) {
            System.out.println("Model cache: " + (cacheDir == null ? defaultHfCacheDir() : cacheDir));
            System.out.println("The model downloads through managed llama.cpp on first start.");
        } else {
            System.out.println("Model path: " + modelPath);
        }
        if (embeddingProfile(embedProfile) != null) {
            System.out.println("Embedding profile: " + normalizeProfile(embedProfile));
            System.out.println("Embedding server: http://127.0.0.1:" + Math.max(1, embedPort));
        }
    }

    private static Map<String, ModelProfile> profiles() {
        // T902: sourced from the shared LlamaCppModelProfiles registry so the
        // canned profiles here and the /set model switch guidance never drift.
        Map<String, ModelProfile> out = new LinkedHashMap<>();
        for (var p : LlamaCppModelProfiles.profiles().values()) {
            out.put(p.alias(), new ModelProfile(p.alias(), p.hfRepo(), p.hfFile(), p.nativeCalling()));
        }
        return Map.copyOf(out);
    }

    private static EmbeddingSetupProfile embeddingProfile(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = normalizeProfile(value);
        if ("bge-m3".equals(normalized)) {
            return new EmbeddingSetupProfile(
                    "bge-m3",
                    "ggml-org/bge-m3-Q8_0-GGUF",
                    "bge-m3-q8_0.gguf",
                    1024,
                    "mean");
        }
        throw new IllegalArgumentException("Unknown embedding profile: " + value);
    }

    private static String renderEmbeddingYaml(
            EmbeddingSetupProfile embedding,
            Path serverPath,
            Path cacheDir,
            int embedPort) {
        if (embedding == null) {
            return """
                    embed:
                      provider: "disabled"
                      model: "none"
                      host: ""
                      allow_remote: false
                    """;
        }
        Path effectiveCache = cacheDir == null ? defaultHfCacheDir() : cacheDir;
        return """
                embed:
                  provider: "llama_cpp"
                  model: "%s"
                  host: "http://127.0.0.1:%d"
                  allow_remote: false
                  dimensions: %d
                  managed:
                    enabled: true
                    server_path: "%s"
                    model_path: ""
                    hf_repo: "%s"
                    hf_file: "%s"
                    hf_cache_dir: "%s"
                    host: "http://127.0.0.1"
                    port: %d
                    pooling: "%s"
                    server_args: []
                """.formatted(
                yamlScalar(embedding.alias()),
                embedPort,
                embedding.dimensions(),
                serverPath == null ? "" : yamlPath(serverPath),
                yamlScalar(embedding.hfRepo()),
                yamlScalar(embedding.hfFile()),
                yamlPath(effectiveCache),
                embedPort,
                yamlScalar(embedding.pooling()));
    }

    private static String normalizeProfile(String value) {
        String normalized = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("model profile is required");
        }
        normalized = normalized.replaceAll("[^a-z0-9._-]", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("model profile must contain at least one letter, number, dot, underscore, or dash");
        }
        return normalized;
    }

    private static Path defaultConfigPath() {
        return Path.of(System.getProperty("user.home"), ".talos", "config.yaml");
    }

    private static Path defaultHfCacheDir() {
        return Path.of(System.getProperty("user.home"), ".talos", "models", "huggingface");
    }

    private static String yamlPath(Path path) {
        if (path == null) return "";
        return yamlScalar(path.toAbsolutePath().normalize().toString().replace('\\', '/'));
    }

    private static String yamlScalar(String value) {
        return Objects.toString(value, "").replace("\\", "/").replace("\"", "\\\"");
    }

    private static String safeTimestamp() {
        return Instant.now().toString().replace(":", "").replace(".", "");
    }

    private record ModelProfile(String alias, String hfRepo, String hfFile, boolean nativeCalling) {}

    private record EmbeddingSetupProfile(String alias, String hfRepo, String hfFile, int dimensions, String pooling) {}
}
