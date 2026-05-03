package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Health;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlamaCppServerManagerTest {

    @TempDir Path tempDir;

    @Test
    void managedModeLaunchesConfiguredExecutableWithExpectedArguments() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.ofEntries(
                    Map.entry("mode", "managed"),
                    Map.entry("server_path", exe.toString()),
                    Map.entry("model_path", model.toString()),
                    Map.entry("model", "talos-agent"),
                    Map.entry("host", "http://127.0.0.1"),
                    Map.entry("port", server.getAddress().getPort()),
                    Map.entry("context", 4096),
                    Map.entry("jinja", true),
                    Map.entry("chat_template", "chatml"),
                    Map.entry("server_args", List.of("--no-webui", "--log-disable"))));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            assertEquals(1, launcher.commands.size());
            List<String> command = launcher.commands.get(0);
            assertEquals(exe.toString(), command.get(0));
            assertContainsPair(command, "-m", model.toString());
            assertContainsPair(command, "-c", "4096");
            assertContainsPair(command, "--host", "127.0.0.1");
            assertContainsPair(command, "--port", String.valueOf(server.getAddress().getPort()));
            assertContainsPair(command, "--alias", "talos-agent");
            assertContainsPair(command, "--chat-template", "chatml");
            assertTrue(command.contains("--jinja"));
            assertTrue(command.contains("--no-webui"));
            assertTrue(command.contains("--log-disable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectOnlyModeDoesNotLaunchProcess() throws Exception {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", 18080));
        FakeLauncher launcher = new FakeLauncher();
        LlamaCppServerManager manager = new LlamaCppServerManager(
                LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient());

        manager.ensureStarted();

        assertTrue(launcher.commands.isEmpty());
    }

    @Test
    void managedModeWaitsThroughLoadingHealthUntilReady() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        AtomicInteger healthCalls = new AtomicInteger();
        HttpServer server = startSequencedHealthServer(healthCalls, List.of(503, 503, 200));
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            assertEquals(1, launcher.commands.size());
            assertTrue(healthCalls.get() >= 3, "managed startup must wait until health is ready");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeReportsProcessExitBeforeReadinessWithLogExcerpt() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startSequencedHealthServer(new AtomicInteger(), List.of(503, 503, 503));
        Path logDir = tempDir.resolve("logs");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", 18080));
            FakeLauncher launcher = new FakeLauncher();
            launcher.process.alive = false;
            launcher.logContentOnStart = "llama_model_load: failed to load model\nout of device memory\n";
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), logDir);

            EngineException.ConnectionFailed error =
                    assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);
            Health health = manager.health();

            assertTrue(error.getMessage().contains("exited before readiness"));
            assertTrue(error.getMessage().contains("out of device memory"));
            assertFalse(health.ok());
            assertTrue(health.message().contains("failed to load model"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void healthReportsMissingBinarySeparately() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", tempDir.resolve("missing-server.exe").toString(),
                "model_path", tempDir.resolve("agent.gguf").toString()));
        LlamaCppServerManager manager = new LlamaCppServerManager(
                LlamaCppConfig.from(cfg), new FakeLauncher(), HttpClient.newHttpClient());

        Health health = manager.health();

        assertFalse(health.ok());
        assertTrue(health.message().contains("server_path"));
    }

    @Test
    void healthReportsMissingModelSeparately() throws Exception {
        Path exe = touch("llama-server.exe");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));
        LlamaCppServerManager manager = new LlamaCppServerManager(
                LlamaCppConfig.from(cfg), new FakeLauncher(), HttpClient.newHttpClient());

        Health health = manager.health();

        assertFalse(health.ok());
        assertTrue(health.message().contains("model_path"));
    }

    @Test
    void failedLaunchIsRecordedForHealth() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "model_path", model.toString()));
        FakeLauncher launcher = new FakeLauncher();
        launcher.failure = new IOException("cannot start");
        LlamaCppServerManager manager = new LlamaCppServerManager(
                LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient());

        assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);
        Health health = manager.health();

        assertFalse(health.ok());
        assertTrue(health.message().contains("failed to launch"));
        assertTrue(health.message().contains("cannot start"));
    }

    @Test
    void healthReportsFailedHttpHealthSeparately() throws Exception {
        HttpServer server = startHealthServer(503, "loading");
        try {
            Config cfg = config(Map.of(
                    "mode", "connect_only",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), new FakeLauncher(), HttpClient.newHttpClient());

            Health health = manager.health();

            assertFalse(health.ok());
            assertTrue(health.message().contains("HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeDestroysOnlyManagedOwnedProcess() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();
            manager.close();

            assertTrue(launcher.process.destroyed);
        } finally {
            server.stop(0);
        }
    }

    private Path touch(String filename) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private static Config config(Map<String, Object> llamaCpp) {
        Config cfg = new Config();
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", new LinkedHashMap<>(llamaCpp));
        cfg.data.put("engines", engines);
        return cfg;
    }

    private static void assertContainsPair(List<String> command, String flag, String value) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0, "missing flag " + flag + " in " + command);
        assertTrue(index + 1 < command.size(), "missing value for " + flag + " in " + command);
        assertEquals(value, command.get(index + 1));
    }

    private static HttpServer startHealthServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer startSequencedHealthServer(AtomicInteger calls, List<Integer> statuses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            int index = calls.getAndIncrement();
            int status = statuses.get(Math.min(index, statuses.size() - 1));
            byte[] bytes = (status == 200 ? "ok" : "loading").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final class FakeLauncher implements LlamaCppProcessLauncher {
        private final List<List<String>> commands = new ArrayList<>();
        private final FakeProcess process = new FakeProcess();
        private IOException failure;
        private String logContentOnStart = "";

        @Override
        public LlamaCppProcess start(List<String> command, Path logPath) throws IOException {
            commands.add(List.copyOf(command));
            if (failure != null) throw failure;
            if (logPath != null && !logContentOnStart.isBlank()) {
                Files.createDirectories(logPath.getParent());
                Files.writeString(logPath, logContentOnStart, StandardCharsets.UTF_8);
            }
            return process;
        }
    }

    private static final class FakeProcess implements LlamaCppProcess {
        private boolean alive = true;
        private boolean destroyed;

        @Override public boolean isAlive() { return alive; }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }
    }
}
