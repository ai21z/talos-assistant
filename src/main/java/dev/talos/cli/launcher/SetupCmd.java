package dev.talos.cli.launcher;
 
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

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Setup topic. Use 'models' for model setup.")
    String topic;

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
        return """
                Talos managed llama.cpp model setup

                Tested profiles:
                  qwen2.5-coder-14b     Qwen/Qwen2.5-Coder-14B-Instruct-GGUF q4_k_m        tool mode: native/default
                  gpt-oss-20b            ggml-org/gpt-oss-20b-GGUF mxfp4                   tool mode: native/default
                  qwen36vf-q4km          tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF Q4_K_M tool mode: native/default
                  qwen36vf-q6k           tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF Q6_K   tool mode: native/default
                  deepseek-v2lite-q4km   bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF Q4_K_M tool mode: text/tool-prompt

                Talos-managed download/cache:
                  talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
                  talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write

                Talos sets HF_HOME to ~/.talos/models/huggingface for these profiles, so llama.cpp stores
                Hugging Face downloads under .talos/models on first model start.

                User-owned GGUF path:
                  talos setup models --profile my-agent --server-path C:/path/to/llama-server.exe --model-path D:/models/agent.gguf --write

                Existing configs are backed up when --force is used.
                """;
    }

    public static String renderManagedLlamaCppProfileConfig(
            String profileName,
            Path serverPath,
            Path modelPath,
            Path cacheDir,
            int port) {
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

                embed:
                  provider: "disabled"
                  model: "none"
                  host: ""
                  allow_remote: false

                rag:
                  vectors:
                    enabled: false
                """.formatted(
                yamlScalar(alias),
                serverPath == null ? "" : yamlPath(serverPath),
                modelPathValue,
                yamlScalar(hfRepo),
                yamlScalar(hfFile),
                hfCacheDir,
                yamlScalar(alias),
                Math.max(1, port),
                nativeCalling);
    }
 
    @Override public Integer call() {
        try {
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
                port);

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
    }

    private static Map<String, ModelProfile> profiles() {
        Map<String, ModelProfile> out = new LinkedHashMap<>();
        out.put("qwen2.5-coder-14b", new ModelProfile(
                "qwen2.5-coder-14b",
                "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                "qwen2.5-coder-14b-instruct-q4_k_m.gguf",
                true));
        out.put("gpt-oss-20b", new ModelProfile(
                "gpt-oss-20b",
                "ggml-org/gpt-oss-20b-GGUF",
                "gpt-oss-20b-mxfp4.gguf",
                true));
        out.put("qwen36vf-q4km", new ModelProfile(
                "qwen36vf-q4km",
                "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf",
                true));
        out.put("qwen36vf-q6k", new ModelProfile(
                "qwen36vf-q6k",
                "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf",
                true));
        out.put("deepseek-v2lite-q4km", new ModelProfile(
                "deepseek-v2lite-q4km",
                "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                false));
        return Map.copyOf(out);
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
}
