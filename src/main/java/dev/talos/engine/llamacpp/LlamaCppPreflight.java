package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Public, side-effect-free preflight view over the managed llama.cpp
 * configuration: the same static validation {@link LlamaCppServerManager}
 * runs before launching the server, exposed so diagnostics (the doctor
 * preflight, first-run) can verify the environment without starting
 * anything or string-matching {@code Health.message()}.
 *
 * <p>The manager delegates its validation here - there is exactly one
 * source of truth for these failure strings, which are pinned by
 * {@code LlamaCppServerManagerTest} and {@code LlamaCppPreflightTest}.
 */
public final class LlamaCppPreflight {

    /**
     * @param managed           true when the engine manages the server process itself
     * @param validationFailure blank when the managed files validate (or mode is
     *                          connect-only); otherwise the exact human-readable
     *                          failure the engine would raise before launch
     * @param baseUrl           the server base URL the engine would probe
     * @param port              the configured server port
     */
    public record Report(boolean managed, String validationFailure, String baseUrl, int port) {
        public Report {
            validationFailure = Objects.toString(validationFailure, "");
            baseUrl = Objects.toString(baseUrl, "");
        }

        /** True when the static file/metadata validation found no problem. */
        public boolean filesOk() {
            return validationFailure.isBlank();
        }
    }

    private LlamaCppPreflight() {}

    /** Run the static managed-mode validation against the given config. */
    public static Report check(EngineConfig cfg) {
        LlamaCppConfig config = LlamaCppConfig.from(cfg);
        return new Report(config.managed(), validationFailure(config), config.baseUrl(), config.port());
    }

    /**
     * The managed-mode validation previously private to
     * {@link LlamaCppServerManager#health()}: server binary exists, model
     * file exists (unless sourced from Hugging Face), and the GGUF
     * architecture is one the managed runtime supports.
     */
    static String validationFailure(LlamaCppConfig config) {
        if (!config.managed()) return "";
        if (config.serverPath() == null || config.serverPath().isBlank()
                || !Files.isRegularFile(Path.of(config.serverPath()))) {
            return "llama_cpp server_path is missing or not a file: "
                    + Objects.toString(config.serverPath(), "");
        }
        if (!config.hasHfSource()
                && (config.modelPath() == null || config.modelPath().isBlank()
                || !Files.isRegularFile(Path.of(config.modelPath())))) {
            return "llama_cpp model_path or hf_repo is missing. model_path is not a file: "
                    + Objects.toString(config.modelPath(), "");
        }
        if (config.hasHfSource()) {
            return "";
        }
        return unsupportedModelMetadataFailure(config, Path.of(config.modelPath()));
    }

    private static String unsupportedModelMetadataFailure(LlamaCppConfig config, Path modelPath) {
        String architecture = GgufMetadata.architecture(modelPath).orElse("");
        if (!"gptoss".equalsIgnoreCase(architecture)) return "";

        String model = config.catalogFallbackModel();
        return "llama_cpp model '" + model + "' at " + modelPath
                + " uses unsupported GGUF architecture 'gptoss'. "
                + "The managed llama.cpp runtime expects GPT-OSS GGUF architecture 'gpt-oss' "
                + "with matching GPT-OSS tensor metadata. Use a llama.cpp-compatible GPT-OSS 20B GGUF "
                + "or update the model artifact. No fallback model was selected.";
    }
}
