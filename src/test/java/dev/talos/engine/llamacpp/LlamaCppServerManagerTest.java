package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Health;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
                    Map.entry("context", 12288),
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
            assertContainsPair(command, "-c", "12288");
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
    void managedModeRaisesSmallConfiguredContextToAgentMinimum() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "context", 4096));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            assertEquals(1, launcher.commands.size());
            assertContainsPair(launcher.commands.get(0), "-c", "8192");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeDefaultsToSingleAgentSlotAndBoundedPrediction() throws Exception {
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

            List<String> command = launcher.commands.get(0);
            assertContainsPair(command, "--parallel", "1");
            assertContainsPair(command, "--predict", "2048");
            assertContainsPair(command, "-lv", "4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeHonorsParallelAndPredictionOverridesFromServerArgs() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "server_args", List.of("-np", "2", "-n", "512")));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            List<String> command = launcher.commands.get(0);
            assertContainsPair(command, "-np", "2");
            assertContainsPair(command, "-n", "512");
            assertFalse(command.contains("--parallel"), "must not add default --parallel when -np is configured: " + command);
            assertFalse(command.contains("--predict"), "must not add default --predict when -n is configured: " + command);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-lv", "--verbosity", "--log-verbosity", "--verbosity=2", "--log-verbosity=2"})
    void managedModeHonorsVerbosityOverrideAliasesFromServerArgs(String verbosityArg) throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            List<String> serverArgs = verbosityArg.contains("=") ? List.of(verbosityArg) : List.of(verbosityArg, "2");
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "server_args", serverArgs));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            List<String> command = launcher.commands.get(0);
            assertTrue(command.contains(verbosityArg), command::toString);
            assertFalse(containsPair(command, "-lv", "4"),
                    "must not add default -lv 4 when verbosity is configured: " + command);
            if (!"-lv".equals(verbosityArg)) {
                assertFalse(command.contains("-lv"), "must not add default -lv when another alias is configured: " + command);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeRecognizesEqualsFormServerArgOverrides() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort(),
                    "server_args", List.of("--parallel=3", "--n-predict=1024")));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            List<String> command = launcher.commands.get(0);
            assertTrue(command.contains("--parallel=3"));
            assertTrue(command.contains("--n-predict=1024"));
            assertFalse(command.contains("--parallel"), "must not add default --parallel when --parallel= is configured: " + command);
            assertFalse(command.contains("--predict"), "must not add default --predict when --n-predict= is configured: " + command);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeLaunchesHuggingFaceRepoSourceWithoutLocalModelPath() throws Exception {
        Path exe = touch("llama-server.exe");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.ofEntries(
                    Map.entry("mode", "managed"),
                    Map.entry("server_path", exe.toString()),
                    Map.entry("hf_repo", "ggml-org/gpt-oss-20b-GGUF"),
                    Map.entry("hf_file", "gpt-oss-20b-mxfp4.gguf"),
                    Map.entry("model", "gpt-oss-20b"),
                    Map.entry("host", "http://127.0.0.1"),
                    Map.entry("port", server.getAddress().getPort()),
                    Map.entry("context", 8192),
                    Map.entry("jinja", true)));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            assertEquals(1, launcher.commands.size());
            List<String> command = launcher.commands.get(0);
            assertFalse(command.contains("-m"), "HF source must not also require a local -m model path: " + command);
            assertContainsPair(command, "--hf-repo", "ggml-org/gpt-oss-20b-GGUF");
            assertContainsPair(command, "--hf-file", "gpt-oss-20b-mxfp4.gguf");
            assertContainsPair(command, "--alias", "gpt-oss-20b");
            assertContainsPair(command, "-c", "8192");
            assertTrue(command.contains("--jinja"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedModeSetsHfHomeWhenHuggingFaceCacheDirIsConfigured() throws Exception {
        Path exe = touch("llama-server.exe");
        Path hfHome = tempDir.resolve("talos-model-cache");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.ofEntries(
                    Map.entry("mode", "managed"),
                    Map.entry("server_path", exe.toString()),
                    Map.entry("hf_repo", "ggml-org/gpt-oss-20b-GGUF"),
                    Map.entry("hf_file", "gpt-oss-20b-mxfp4.gguf"),
                    Map.entry("hf_cache_dir", hfHome.toString()),
                    Map.entry("model", "gpt-oss-20b"),
                    Map.entry("host", "http://127.0.0.1"),
                    Map.entry("port", server.getAddress().getPort())));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();

            assertEquals(hfHome.toString(), launcher.environments.get(0).get("HF_HOME"));
            assertTrue(Files.isDirectory(hfHome), "Talos should create the configured HF_HOME directory before launch");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void catalogFallbackModelUsesHuggingFaceRepoWhenNoAliasOrModelPath() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "hf_repo", "ggml-org/gpt-oss-20b-GGUF"));

        LlamaCppConfig config = LlamaCppConfig.from(cfg);

        assertEquals("gpt-oss-20b-GGUF", config.catalogFallbackModel());
    }

    @Test
    void modelSourceNoteDoesNotExposeMalformedConfiguredModelPath() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "model", "custom-agent",
                "model_path", "C:/Users/arisz/private-secret\u0000/model.gguf"));

        LlamaCppConfig config = LlamaCppConfig.from(cfg);

        assertEquals("", config.modelSourceNote());
    }

    @Test
    void connectOnlyKeepsConfiguredContextWindowForExternalServer() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", 18080,
                "context", 4096));

        LlamaCppConfig config = LlamaCppConfig.from(cfg);

        assertEquals(4096, config.context());
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
        assertTrue(health.message().contains("model_path or hf_repo"));
    }

    @Test
    void managedModeRejectsUnsupportedOllamaGptOssGgufBeforeLaunch() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = writeGgufWithArchitecture("gptoss");
        HttpServer server = startHealthServer(200, "ok");
        try {
            Config cfg = config(Map.of(
                    "mode", "managed",
                    "server_path", exe.toString(),
                    "model_path", model.toString(),
                    "model", "gpt-oss-20b",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));
            FakeLauncher launcher = new FakeLauncher();
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            EngineException.ConnectionFailed error =
                    assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);
            Health health = manager.health();

            assertTrue(launcher.commands.isEmpty(), "unsupported GGUF variant must fail before process launch");
            assertTrue(error.getMessage().contains("unsupported GGUF architecture 'gptoss'"), error.getMessage());
            assertTrue(error.getMessage().contains("gpt-oss-20b"), error.getMessage());
            assertTrue(error.getMessage().contains(model.toString()), error.getMessage());
            assertFalse(health.ok());
            assertTrue(health.message().contains("unsupported GGUF architecture 'gptoss'"), health.message());
        } finally {
            server.stop(0);
        }
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

    @Test
    void failedReadinessDestroysManagedOwnedProcess() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(503, "loading");
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
                    Duration.ofMillis(40), Duration.ofMillis(5), tempDir.resolve("logs"));

            assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);

            assertTrue(launcher.process.destroyed,
                    "managed process must be cleaned up when readiness fails after launch");
            assertFalse(launcher.process.alive,
                    "readiness failure cleanup must leave the fake managed process stopped");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeForcesManagedProcessThatIgnoresGracefulDestroy() throws Exception {
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
            launcher.process.destroyLeavesAlive = true;
            LlamaCppServerManager manager = new LlamaCppServerManager(
                    LlamaCppConfig.from(cfg), launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(2), Duration.ofMillis(10), tempDir.resolve("logs"));

            manager.ensureStarted();
            manager.close();

            assertTrue(launcher.process.destroyed,
                    "close should first request graceful process termination");
            assertTrue(launcher.process.forceDestroyed,
                    "close should force-stop a managed process that remains alive");
            assertFalse(launcher.process.alive,
                    "close must leave the managed process stopped");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedLifecycleWritesStartAndStopDiagnosticsToLog() throws Exception {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        HttpServer server = startHealthServer(200, "ok");
        Path logDir = tempDir.resolve("logs");
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
                    Duration.ofSeconds(2), Duration.ofMillis(10), logDir);

            manager.ensureStarted();
            manager.close();

            String log = Files.readString(logDir.resolve("llama_cpp-" + server.getAddress().getPort() + ".log"),
                    StandardCharsets.UTF_8);
            assertTrue(log.contains("Talos managed llama.cpp server starting"),
                    "managed server log should include Talos-owned startup diagnostics");
            assertTrue(log.contains("Talos managed llama.cpp server stopped"),
                    "managed server log should include Talos-owned shutdown diagnostics");
        } finally {
            server.stop(0);
        }
    }

    private Path touch(String filename) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private Path writeGgufWithArchitecture(String architecture) throws IOException {
        Path path = tempDir.resolve("model-" + architecture + ".gguf");
        byte[] key = "general.architecture".getBytes(StandardCharsets.UTF_8);
        byte[] value = architecture.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 8 + 8 + 8 + key.length + 4 + 8 + value.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'G').put((byte) 'G').put((byte) 'U').put((byte) 'F');
        buffer.putInt(3);
        buffer.putLong(0);
        buffer.putLong(1);
        buffer.putLong(key.length);
        buffer.put(key);
        buffer.putInt(8);
        buffer.putLong(value.length);
        buffer.put(value);
        Files.write(path, buffer.array());
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

    private static boolean containsPair(List<String> command, String flag, String value) {
        int index = command.indexOf(flag);
        return index >= 0 && index + 1 < command.size() && value.equals(command.get(index + 1));
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
        private final List<Map<String, String>> environments = new ArrayList<>();
        private final FakeProcess process = new FakeProcess();
        private IOException failure;
        private String logContentOnStart = "";

        @Override
        public LlamaCppProcess start(List<String> command, Path logPath) throws IOException {
            return start(command, logPath, Map.of());
        }

        @Override
        public LlamaCppProcess start(List<String> command, Path logPath, Map<String, String> environment) throws IOException {
            commands.add(List.copyOf(command));
            environments.add(environment == null ? Map.of() : Map.copyOf(environment));
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
        private boolean destroyLeavesAlive;
        private boolean forceDestroyed;

        @Override public boolean isAlive() { return alive; }

        @Override
        public void destroy() {
            destroyed = true;
            if (!destroyLeavesAlive) {
                alive = false;
            }
        }

        @Override
        public void destroyForcibly() {
            forceDestroyed = true;
            alive = false;
        }
    }
}
