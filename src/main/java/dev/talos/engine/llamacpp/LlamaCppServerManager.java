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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LlamaCppServerManager implements AutoCloseable {
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_READINESS_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_FORCED_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final int LOG_EXCERPT_BYTES = 1600;
    private static final String DEFAULT_AGENT_PARALLEL = "1";
    private static final String DEFAULT_AGENT_PREDICT = "2048";
    private static final String VERIFICATION_LOG_VERBOSITY = "4";
    private static final List<String> PARALLEL_FLAGS = List.of("--parallel", "-np");
    private static final List<String> PREDICT_FLAGS = List.of("--predict", "--n-predict", "-n");
    private static final List<String> VERBOSITY_FLAGS = List.of("-lv", "--verbosity", "--log-verbosity");

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
        Map<String, String> environment = buildEnvironment();
        Path logPath = logPath();
        try {
            prepareLog(logPath);
            prepareModelCacheDir();
            appendLifecycleLog(logPath, "Talos managed llama.cpp server starting on "
                    + config.listenHost() + ":" + config.port());
            process = launcher.start(command, logPath, environment);
            appendLifecycleLog(logPath, "Talos managed llama.cpp server process launched");
            lastLaunchFailure = "";
        } catch (IOException e) {
            lastLaunchFailure = "failed to launch llama.cpp server: " + e.getMessage();
            throw new EngineException.ConnectionFailed("llama_cpp launch: " + e.getMessage(), e);
        }
        try {
            waitForReadiness(logPath);
        } catch (RuntimeException e) {
            stopManagedProcess(logPath, "readiness failed");
            throw e;
        }
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
        appendManagedAgentDefault(command, config.serverArgs(), PARALLEL_FLAGS, "--parallel", DEFAULT_AGENT_PARALLEL);
        appendManagedAgentDefault(command, config.serverArgs(), PREDICT_FLAGS, "--predict", DEFAULT_AGENT_PREDICT);
        // Debug log verbosity is scoped to verification launches (doctor
        // --start, tune): it puts the offload and rate evidence lines in the
        // server log, but it also writes prompt and workspace content there,
        // so ordinary sessions stay at llama.cpp's normal verbosity. A
        // user-configured verbosity in server_args always wins.
        if (config.verificationLogging()) {
            appendManagedAgentDefault(command, config.serverArgs(), VERBOSITY_FLAGS, "-lv", VERIFICATION_LOG_VERBOSITY);
        }
        command.addAll(LlamaCppContextArgs.sanitize(config.serverArgs()));
        return command;
    }

    Map<String, String> buildEnvironment() {
        if (!config.hasHfSource()) return Map.of();
        if (config.hfCacheDir() == null || config.hfCacheDir().isBlank()) return Map.of();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HF_HOME", config.hfCacheDir());
        return environment;
    }

    private void prepareModelCacheDir() throws IOException {
        if (!config.hasHfSource()) return;
        if (config.hfCacheDir() == null || config.hfCacheDir().isBlank()) return;
        Files.createDirectories(Path.of(config.hfCacheDir()));
    }

    private static void appendManagedAgentDefault(List<String> command,
                                                  List<String> serverArgs,
                                                  List<String> overrideFlags,
                                                  String flag,
                                                  String value) {
        if (hasOverrideFlag(serverArgs, overrideFlags)) return;
        command.add(flag);
        command.add(value);
    }

    private static boolean hasOverrideFlag(List<String> serverArgs, List<String> flags) {
        if (serverArgs == null || serverArgs.isEmpty()) return false;
        for (String raw : serverArgs) {
            String arg = raw == null ? "" : raw.trim();
            if (arg.isBlank()) continue;
            for (String flag : flags) {
                if (arg.equals(flag) || arg.startsWith(flag + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String managedValidationFailure() {
        // Single source of truth shared with the doctor preflight (T784);
        // the failure strings are pinned by both test suites.
        return LlamaCppPreflight.validationFailure(config);
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
        return LlamaCppRuntimePaths.managedLogFile(logDir, config.port());
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
            // Lifecycle diagnostics are best-effort and must not mask engine errors.
        }
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
        stopManagedProcess(logPath(), "close");
    }

    private void stopManagedProcess(Path logPath, String reason) {
        if (process != null) {
            LlamaCppProcess ownedProcess = process;
            appendLifecycleLog(logPath, "Talos managed llama.cpp server stopping: " + Objects.toString(reason, ""));
            try {
                if (ownedProcess.isAlive()) {
                    ownedProcess.destroy();
                    if (!waitForExit(ownedProcess, DEFAULT_SHUTDOWN_TIMEOUT) && ownedProcess.isAlive()) {
                        appendLifecycleLog(logPath, "Talos managed llama.cpp server still alive; forcing stop");
                        ownedProcess.destroyForcibly();
                        waitForExit(ownedProcess, DEFAULT_FORCED_SHUTDOWN_TIMEOUT);
                    }
                }
            } finally {
                if (ownedProcess.isAlive()) {
                    appendLifecycleLog(logPath, "Talos managed llama.cpp server may still be running after stop attempt");
                } else {
                    appendLifecycleLog(logPath, "Talos managed llama.cpp server stopped");
                }
            }
            process = null;
            ready = false;
        }
    }

    private static boolean waitForExit(LlamaCppProcess process, Duration timeout) {
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
}
