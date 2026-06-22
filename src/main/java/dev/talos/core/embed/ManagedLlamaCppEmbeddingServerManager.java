package dev.talos.core.embed;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class ManagedLlamaCppEmbeddingServerManager implements ManagedEmbeddingEndpoint {
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_READINESS_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final int LOG_EXCERPT_BYTES = 1600;

    private final ManagedLlamaCppEmbeddingConfig config;
    private final ManagedProcessLauncher launcher;
    private final HttpClient http;
    private final Duration readinessTimeout;
    private final Duration readinessPollInterval;
    private final Path logDir;

    private ManagedProcess process;
    private boolean ready;
    private String lastLaunchFailure = "";

    ManagedLlamaCppEmbeddingServerManager(ManagedLlamaCppEmbeddingConfig config) {
        this(config,
                new ProcessBuilderManagedProcessLauncher(),
                HttpClient.newHttpClient(),
                DEFAULT_READINESS_TIMEOUT,
                DEFAULT_READINESS_POLL_INTERVAL,
                defaultLogDir());
    }

    ManagedLlamaCppEmbeddingServerManager(
            ManagedLlamaCppEmbeddingConfig config,
            ManagedProcessLauncher launcher,
            HttpClient http,
            Duration readinessTimeout,
            Duration readinessPollInterval,
            Path logDir) {
        this.config = Objects.requireNonNull(config);
        this.launcher = launcher == null ? new ProcessBuilderManagedProcessLauncher() : launcher;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.readinessTimeout = readinessTimeout == null ? DEFAULT_READINESS_TIMEOUT : readinessTimeout;
        this.readinessPollInterval = readinessPollInterval == null
                ? DEFAULT_READINESS_POLL_INTERVAL
                : readinessPollInterval;
        this.logDir = logDir == null ? defaultLogDir() : logDir;
    }

    @Override
    public synchronized void ensureStarted() {
        if (!config.enabled()) return;
        if (process != null && process.isAlive()) {
            if (ready) return;
            waitForReadiness(logPath());
            return;
        }
        ready = false;

        String validation = validationFailure();
        if (!validation.isBlank()) {
            throw new EngineException.ConnectionFailed(validation, null);
        }

        List<String> command = buildCommand();
        Map<String, String> environment = buildEnvironment();
        Path logPath = logPath();
        try {
            prepareLog(logPath);
            prepareModelCacheDir();
            appendLifecycleLog(logPath, "Talos managed llama.cpp embedding server starting on "
                    + config.listenHost() + ":" + config.port());
            process = launcher.start(command, logPath, environment);
            appendLifecycleLog(logPath, "Talos managed llama.cpp embedding server process launched");
            lastLaunchFailure = "";
        } catch (IOException e) {
            lastLaunchFailure = "failed to launch llama.cpp embedding server: " + e.getMessage();
            throw new EngineException.ConnectionFailed("llama_cpp embedding launch: " + e.getMessage(), e);
        }

        try {
            waitForReadiness(logPath);
        } catch (RuntimeException e) {
            stopManagedProcess(logPath, "readiness failed");
            throw e;
        }
    }

    Health health() {
        String validation = validationFailure();
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
            if (!config.hfFile().isBlank()) {
                command.add("--hf-file");
                command.add(config.hfFile());
            }
        } else {
            command.add("-m");
            command.add(config.modelPath());
        }
        command.add("--embedding");
        command.add("--pooling");
        command.add(config.pooling());
        command.add("--host");
        command.add(config.listenHost());
        command.add("--port");
        command.add(String.valueOf(config.port()));
        command.addAll(config.serverArgs());
        return command;
    }

    Map<String, String> buildEnvironment() {
        if (!config.hasHfSource()) return Map.of();
        if (config.hfCacheDir().isBlank()) return Map.of();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HF_HOME", config.hfCacheDir());
        return environment;
    }

    private String validationFailure() {
        if (!config.enabled()) return "";
        if (config.serverPath().isBlank() || !Files.isRegularFile(Path.of(config.serverPath()))) {
            return "llama_cpp embedding server_path is missing or not a file: " + config.serverPath();
        }
        if (!config.hasHfSource()
                && (config.modelPath().isBlank() || !Files.isRegularFile(Path.of(config.modelPath())))) {
            return "llama_cpp embedding model_path or hf_repo is missing. model_path is not a file: "
                    + config.modelPath();
        }
        return "";
    }

    private void waitForReadiness(Path logPath) {
        long deadline = System.nanoTime() + Math.max(1L, readinessTimeout.toNanos());
        String lastHealth = "not checked";
        while (System.nanoTime() <= deadline) {
            if (process == null || !process.isAlive()) {
                lastLaunchFailure = "llama.cpp embedding server exited before readiness. "
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
        lastLaunchFailure = "llama.cpp embedding server did not become ready within "
                + readinessTimeout.toSeconds()
                + "s; last health: " + lastHealth
                + ". " + logExcerptSuffix(logPath);
        throw new EngineException.ConnectionFailed(lastLaunchFailure, null);
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
                return Health.ok("llama_cpp_embedding", true);
            }
            return Health.down("llama.cpp embedding health check failed: HTTP " + response.statusCode());
        } catch (ConnectException e) {
            return Health.down("llama.cpp embedding health check failed: connection refused");
        } catch (Exception e) {
            return Health.down("llama.cpp embedding health check failed: " + e.getMessage());
        }
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(Math.max(1L, readinessPollInterval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastLaunchFailure = "interrupted while waiting for llama.cpp embedding readiness";
            throw new EngineException.ConnectionFailed(lastLaunchFailure, e);
        }
    }

    private void prepareModelCacheDir() throws IOException {
        if (!config.hasHfSource()) return;
        if (config.hfCacheDir().isBlank()) return;
        Files.createDirectories(Path.of(config.hfCacheDir()));
    }

    private Path logPath() {
        return logDir.resolve("llama_cpp-embedding-" + config.port() + ".log");
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

    private static void appendLifecycleLog(Path logPath, String message) {
        if (logPath == null || message == null || message.isBlank()) return;
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath,
                    "[" + Instant.now() + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Lifecycle diagnostics are best-effort and must not mask embedding errors.
        }
    }

    private static String logExcerptSuffix(Path logPath) {
        String excerpt = logExcerpt(logPath);
        return excerpt.isBlank()
                ? "No llama.cpp embedding server log excerpt available."
                : "Log excerpt: " + excerpt;
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
        stopManagedProcess(logPath(), "close");
    }

    private void stopManagedProcess(Path logPath, String reason) {
        if (process == null) return;
        ManagedProcess ownedProcess = process;
        appendLifecycleLog(logPath, "Talos managed llama.cpp embedding server stopping: "
                + Objects.toString(reason, ""));
        try {
            if (ownedProcess.isAlive()) {
                ownedProcess.destroy();
                if (!waitForExit(ownedProcess, DEFAULT_SHUTDOWN_TIMEOUT) && ownedProcess.isAlive()) {
                    ownedProcess.destroyForcibly();
                }
            }
        } finally {
            process = null;
            ready = false;
            appendLifecycleLog(logPath, "Talos managed llama.cpp embedding server stopped");
        }
    }

    private static boolean waitForExit(ManagedProcess process, Duration timeout) {
        if (process == null || !process.isAlive()) return true;
        try {
            return process.waitFor(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return !process.isAlive();
        }
    }

    interface ManagedProcessLauncher {
        ManagedProcess start(List<String> command, Path logPath, Map<String, String> environment) throws IOException;
    }

    interface ManagedProcess {
        boolean isAlive();
        void destroy();
        default boolean waitFor(Duration timeout) throws InterruptedException {
            return !isAlive();
        }
        default void destroyForcibly() {
            destroy();
        }
    }

    private static final class ProcessBuilderManagedProcessLauncher implements ManagedProcessLauncher {
        @Override
        public ManagedProcess start(List<String> command, Path logPath, Map<String, String> environment)
                throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            builder.redirectErrorStream(true);
            if (logPath != null) {
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
            }
            Process process = builder.start();
            return new ProcessAdapter(process);
        }
    }

    private record ProcessAdapter(Process process) implements ManagedProcess {
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void destroy() { process.destroy(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            long millis = timeout == null ? 0L : Math.max(1L, timeout.toMillis());
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
    }
}
