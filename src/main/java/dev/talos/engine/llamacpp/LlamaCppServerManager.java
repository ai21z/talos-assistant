package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineException;
import dev.talos.spi.types.Health;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class LlamaCppServerManager implements AutoCloseable {
    private final LlamaCppConfig config;
    private final LlamaCppProcessLauncher launcher;
    private final HttpClient http;

    private LlamaCppProcess process;
    private String lastLaunchFailure = "";

    LlamaCppServerManager(LlamaCppConfig config, LlamaCppProcessLauncher launcher, HttpClient http) {
        this.config = Objects.requireNonNull(config);
        this.launcher = launcher == null ? new ProcessBuilderLlamaCppProcessLauncher() : launcher;
        this.http = http == null ? HttpClient.newHttpClient() : http;
    }

    synchronized void ensureStarted() {
        if (!config.managed()) return;
        if (process != null && process.isAlive()) return;

        String validation = managedValidationFailure();
        if (!validation.isBlank()) {
            throw new EngineException.ConnectionFailed(validation, null);
        }

        List<String> command = buildCommand();
        try {
            process = launcher.start(command);
            lastLaunchFailure = "";
        } catch (IOException e) {
            lastLaunchFailure = "failed to launch llama.cpp server: " + e.getMessage();
            throw new EngineException.ConnectionFailed("llama_cpp launch: " + e.getMessage(), e);
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
        command.add("-m");
        command.add(config.modelPath());
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
        if (config.modelPath() == null || config.modelPath().isBlank()
                || !Files.isRegularFile(Path.of(config.modelPath()))) {
            return "llama_cpp model_path is missing or not a file: "
                    + Objects.toString(config.modelPath(), "");
        }
        return "";
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

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
