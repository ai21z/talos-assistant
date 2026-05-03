package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineException;
import dev.talos.spi.types.Health;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class LlamaCppServerManager implements AutoCloseable {
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_READINESS_POLL_INTERVAL = Duration.ofMillis(500);
    private static final int LOG_EXCERPT_BYTES = 1600;

    private final LlamaCppConfig config;
    private final LlamaCppProcessLauncher launcher;
    private final HttpClient http;
    private final Duration readinessTimeout;
    private final Duration readinessPollInterval;
    private final Path logDir;

    private LlamaCppProcess process;
    private String lastLaunchFailure = "";
    private boolean ready;

    LlamaCppServerManager(LlamaCppConfig config, LlamaCppProcessLauncher launcher, HttpClient http) {
        this(config, launcher, http,
                DEFAULT_READINESS_TIMEOUT,
                DEFAULT_READINESS_POLL_INTERVAL,
                defaultLogDir());
    }

    LlamaCppServerManager(LlamaCppConfig config,
                          LlamaCppProcessLauncher launcher,
                          HttpClient http,
                          Duration readinessTimeout,
                          Duration readinessPollInterval,
                          Path logDir) {
        this.config = Objects.requireNonNull(config);
        this.launcher = launcher == null ? new ProcessBuilderLlamaCppProcessLauncher() : launcher;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.readinessTimeout = readinessTimeout == null ? DEFAULT_READINESS_TIMEOUT : readinessTimeout;
        this.readinessPollInterval = readinessPollInterval == null
                ? DEFAULT_READINESS_POLL_INTERVAL
                : readinessPollInterval;
        this.logDir = logDir == null ? defaultLogDir() : logDir;
    }

    synchronized void ensureStarted() {
        if (!config.managed()) return;
        if (process != null && process.isAlive()) {
            if (ready) return;
            waitForReadiness(logPath());
            return;
        }
        ready = false;

        String validation = managedValidationFailure();
        if (!validation.isBlank()) {
            throw new EngineException.ConnectionFailed(validation, null);
        }

        List<String> command = buildCommand();
        Path logPath = logPath();
        try {
            prepareLog(logPath);
            process = launcher.start(command, logPath);
            lastLaunchFailure = "";
        } catch (IOException e) {
            lastLaunchFailure = "failed to launch llama.cpp server: " + e.getMessage();
            throw new EngineException.ConnectionFailed("llama_cpp launch: " + e.getMessage(), e);
        }
        waitForReadiness(logPath);
    }

    Health health() {
        String validation = managedValidationFailure();
        if (!validation.isBlank()) return Health.down(validation);
        if (!lastLaunchFailure.isBlank()) return Health.down(lastLaunchFailure);
        return httpHealth();
    }

    List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add(config.serverPath());
        if (config.hasHfSource()) {
            command.add("--hf-repo");
            command.add(config.hfRepo());
            if (config.hfFile() != null && !config.hfFile().isBlank()) {
                command.add("--hf-file");
                command.add(config.hfFile());
            }
        } else {
            command.add("-m");
            command.add(config.modelPath());
        }
        command.add("-c");
        command.add(String.valueOf(config.context()));
        command.add("--host");
        command.add(config.listenHost());
        command.add("--port");
        command.add(String.valueOf(config.port()));
        if (config.jinja()) {
            command.add("--jinja");
        }
        if (config.model() != null && !config.model().isBlank()) {
            command.add("--alias");
            command.add(config.model());
        }
        if (config.chatTemplate() != null && !config.chatTemplate().isBlank()) {
            command.add("--chat-template");
            command.add(config.chatTemplate());
        }
        if (config.chatTemplateFile() != null && !config.chatTemplateFile().isBlank()) {
            command.add("--chat-template-file");
            command.add(config.chatTemplateFile());
        }
        command.addAll(config.serverArgs());
        return command;
    }

    private String managedValidationFailure() {
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
        String unsupportedModel = unsupportedModelMetadataFailure(Path.of(config.modelPath()));
        if (!unsupportedModel.isBlank()) return unsupportedModel;
        return "";
    }

    private String unsupportedModelMetadataFailure(Path modelPath) {
        String architecture = GgufMetadata.architecture(modelPath).orElse("");
        if (!"gptoss".equalsIgnoreCase(architecture)) return "";

        String model = config.catalogFallbackModel();
        return "llama_cpp model '" + model + "' at " + modelPath
                + " uses unsupported GGUF architecture 'gptoss'. "
                + "The managed llama.cpp runtime expects GPT-OSS GGUF architecture 'gpt-oss' "
                + "with matching GPT-OSS tensor metadata. Use a llama.cpp-compatible GPT-OSS 20B GGUF "
                + "or update the model artifact. No fallback model was selected.";
    }

    private Health httpHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return Health.ok("llama_cpp", true);
            }
            return Health.down("llama.cpp health check failed: HTTP " + response.statusCode());
        } catch (ConnectException e) {
            return Health.down("llama.cpp health check failed: connection refused");
        } catch (Exception e) {
            return Health.down("llama.cpp health check failed: " + e.getMessage());
        }
    }

    private void waitForReadiness(Path logPath) {
        long deadline = System.nanoTime() + Math.max(1L, readinessTimeout.toNanos());
        String lastHealth = "not checked";

        while (System.nanoTime() <= deadline) {
            if (process == null || !process.isAlive()) {
                lastLaunchFailure = "llama.cpp server exited before readiness. "
                        + logExcerptSuffix(logPath);
                throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
            }

            Health health = httpHealth();
            if (health.ok()) {
                lastLaunchFailure = "";
                ready = true;
                return;
            }
            lastHealth = health.message();
            sleepPollInterval();
        }

        lastLaunchFailure = "llama.cpp server did not become ready within "
                + readinessTimeout.toSeconds()
                + "s; last health: " + lastHealth
                + ". " + logExcerptSuffix(logPath);
        throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(Math.max(1L, readinessPollInterval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastLaunchFailure = "interrupted while waiting for llama.cpp readiness";
            throw new EngineException.ConnectionFailed(lastLaunchFailure, e);
        }
    }

    private Path logPath() {
        return logDir.resolve("llama_cpp-" + config.port() + ".log");
    }

    private static Path defaultLogDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        Path base = home == null || home.isBlank()
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(home);
        return base.resolve(".talos").resolve("logs");
    }

    private static void prepareLog(Path logPath) throws IOException {
        if (logPath == null) return;
        Files.createDirectories(logPath.getParent());
        Files.writeString(logPath, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String logExcerptSuffix(Path logPath) {
        String excerpt = logExcerpt(logPath);
        return excerpt.isBlank() ? "No llama.cpp server log excerpt available." : "Log excerpt: " + excerpt;
    }

    private static String logExcerpt(Path logPath) {
        if (logPath == null || !Files.isRegularFile(logPath)) return "";
        try {
            byte[] bytes = Files.readAllBytes(logPath);
            int start = Math.max(0, bytes.length - LOG_EXCERPT_BYTES);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroy();
            process = null;
            ready = false;
        }
    }
}
