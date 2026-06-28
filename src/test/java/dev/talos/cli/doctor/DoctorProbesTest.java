package dev.talos.cli.doctor;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T784: per-probe decision coverage for the doctor preflight. */
class DoctorProbesTest {

    @TempDir Path tempDir;

    // ── config ───────────────────────────────────────────────────────────

    @Test
    void configParseFailureFailsWithTheUserConfigPathInTheHint() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "C:/u/.talos/config.yaml",
                true, false, "mapping values are not allowed here", false, 0);

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("mapping values are not allowed here"));
        assertTrue(result.hint().contains("C:/u/.talos/config.yaml"));
    }

    @Test
    void configLoadedPasses() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "(none)",
                false, false, "", false, 3);

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("no user config (built-in defaults)"));
        assertTrue(result.detail().contains("3 defaulted key(s)"));
    }

    @Test
    void strictModeWithDefaultedKeysWarns() {
        ProbeResult result = ConfigProbe.decide(
                "classpath:config/default-config.yaml", "(none)",
                true, true, "", true, 2);

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("under strict mode"));
    }

    // ── runtime environment ─────────────────────────────────────────────

    @Test
    void runtimeEnvironmentReportsBoundedHardwareFactsWithoutGpuClaim() {
        ProbeResult result = new RuntimeEnvironmentProbe().run(ctx(new Config()));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("os="), result.detail());
        assertTrue(result.detail().contains("arch="), result.detail());
        assertTrue(result.detail().contains("java="), result.detail());
        assertTrue(result.detail().contains("cpu="), result.detail());
        assertTrue(result.detail().contains("jvmMaxMemoryMb="), result.detail());
        assertTrue(result.detail().contains("talosHomeFreeMb="), result.detail());
        assertTrue(result.detail().contains("GPU/VRAM not probed by Talos"), result.detail());
    }

    // ── engine-files ─────────────────────────────────────────────────────

    @Test
    void engineFilesPassWhenManagedFilesExist() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", touch("agent.gguf").toString()));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
    }

    @Test
    void engineFilesFailWhenModelMissing() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("model_path or hf_repo is missing"));
        assertTrue(result.hint().contains("talos setup models"));
    }

    @Test
    void engineFilesSkipInConnectOnlyMode() {
        Config cfg = llamaCppConfig(Map.of("mode", "connect_only"));

        ProbeResult result = new EngineFilesProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.SKIP, result.status());
        assertTrue(result.detail().contains("connect-only"));
    }

    // ── server ───────────────────────────────────────────────────────────

    @Test
    void serverRespondingPasses() throws IOException {
        HttpServer server = startHealthServer(200);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "managed",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));

            ProbeResult result = new ServerProbe(false).run(ctx(cfg));

            assertEquals(ProbeResult.Status.PASS, result.status());
            assertTrue(result.detail().contains("server responding"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverHttpErrorFails() throws IOException {
        HttpServer server = startHealthServer(503);
        try {
            Config cfg = llamaCppConfig(Map.of(
                    "mode", "managed",
                    "host", "http://127.0.0.1",
                    "port", server.getAddress().getPort()));

            ProbeResult result = new ServerProbe(false).run(ctx(cfg));

            assertEquals(ProbeResult.Status.FAIL, result.status());
            assertTrue(result.detail().contains("HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managedServerNotRunningIsAWarnNotAFailure() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "host", "http://127.0.0.1",
                "port", freePort()));

        ProbeResult result = new ServerProbe(false).run(ctx(cfg));

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("Talos starts it automatically"));
    }

    @Test
    void connectOnlyServerUnreachableFails() throws IOException {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", freePort()));

        ProbeResult result = new ServerProbe(false).run(ctx(cfg));

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("connect-only"));
        assertTrue(result.hint().contains("start your llama-server"));
    }

    @Test
    void startModeSkipsWhenManagedFilesAreInvalid() {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", tempDir.resolve("missing-server.exe").toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));

        ProbeResult result = new ServerProbe(true).run(ctx(cfg));

        assertEquals(ProbeResult.Status.SKIP, result.status());
        assertTrue(result.detail().contains("engine-files"));
    }

    // ── index-writable / home-writable ───────────────────────────────────

    @Test
    void indexDirectoryWritablePassesAndLeavesNoProbeFile() {
        DoctorContext ctx = ctx(new Config());

        ProbeResult result = new IndexWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.PASS, result.status());
        Path indices = ctx.talosHome().resolve("indices");
        assertTrue(Files.isDirectory(indices));
        try (var files = Files.walk(indices)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("doctor-")),
                    "probe temp file must be deleted");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void indexDirectoryUnwritableFails() throws IOException {
        // A talosHome path that is a FILE makes createDirectories throw on
        // every platform - more reliable than read-only directory bits on
        // Windows.
        Path fileAsHome = touch("not-a-directory");
        DoctorContext ctx = new DoctorContext(new Config(), tempDir, fileAsHome);

        ProbeResult result = new IndexWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.FAIL, result.status());
        assertTrue(result.detail().contains("not writable"));
    }

    @Test
    void homeWritablePassesAndCreatesLogsDir() {
        DoctorContext ctx = ctx(new Config());

        ProbeResult result = new HomeWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(Files.isDirectory(ctx.talosHome().resolve("logs")));
    }

    @Test
    void homeUnwritableFails() throws IOException {
        Path fileAsHome = touch("home-as-file");
        DoctorContext ctx = new DoctorContext(new Config(), tempDir, fileAsHome);

        ProbeResult result = new HomeWritableProbe().run(ctx);

        assertEquals(ProbeResult.Status.FAIL, result.status());
    }

    // ── retrieval / vectors ─────────────────────────────────────────────

    @Test
    void retrievalStateShowsBm25OnlyWhenVectorsDisabled() {
        Config cfg = new Config();
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("vectors", Map.of("enabled", false));
        cfg.data.put("rag", rag);
        cfg.data.put("embed", Map.of("provider", "disabled", "model", "none"));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("vectors=OFF"), result.detail());
        assertTrue(result.detail().contains("BM25-only"), result.detail());
        assertTrue(result.detail().contains("embedding=disabled/none"), result.detail());
        assertTrue(result.detail().contains("embedding dimension not probed by doctor"), result.detail());
        assertTrue(result.detail().contains("GPU/VRAM not probed by Talos"), result.detail());
    }

    @Test
    void retrievalStateShowsRemoteEmbeddingHostRejectedWithoutAllowRemote() {
        Config cfg = new Config();
        cfg.data.put("embed", Map.of(
                "provider", "compat",
                "model", "embedder",
                "host", "http://127.0.0.1.evil.com:8000",
                "allow_remote", false));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.WARN, result.status());
        assertTrue(result.detail().contains("locality=remote-rejected"), result.detail());
        assertTrue(result.detail().contains("BM25-only fallback likely"), result.detail());
    }

    @Test
    void retrievalStateSanitizesSecretShapedEmbeddingHost() {
        Config cfg = new Config();
        String secretHost = "https://user:sk-test-secretsecretsecretsecret@example.com";
        cfg.data.put("embed", Map.of(
                "provider", "compat",
                "model", "embedder",
                "host", secretHost,
                "allow_remote", true));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("host=[redacted]"), result.detail());
        assertTrue(result.detail().contains("locality=remote-allowed"), result.detail());
        assertTrue(result.detail().contains("mode=hybrid if embedding probe succeeds"), result.detail());
        assertTrue(result.detail().contains("model=embedder"), result.detail());
        assertTrue(!result.detail().contains("sk-test-secretsecretsecretsecret"), result.detail());
    }

    @Test
    void retrievalStateUsesManagedLlamaCppEmbeddingHostWhenEmbedHostIsBlank() {
        Config cfg = new Config();
        cfg.data.put("embed", Map.of(
                "provider", "llama_cpp",
                "model", "bge-m3",
                "host", "",
                "managed", Map.of(
                        "enabled", true,
                        "host", "http://127.0.0.1",
                        "port", 18116)));
        cfg.data.put("rag", Map.of("vectors", Map.of("enabled", true)));

        ProbeResult result = new RetrievalStateProbe().run(ctx(cfg));

        assertEquals(ProbeResult.Status.PASS, result.status());
        assertTrue(result.detail().contains("embedding=llama_cpp/bge-m3"), result.detail());
        assertTrue(result.detail().contains("host=http://127.0.0.1:18116"), result.detail());
        assertTrue(result.detail().contains("locality=local"), result.detail());
        assertTrue(result.detail().contains("mode=hybrid if embedding probe succeeds"), result.detail());
        assertFalse(result.detail().contains("11434"), result.detail());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private DoctorContext ctx(Config cfg) {
        return new DoctorContext(cfg, tempDir.resolve("workspace"), tempDir.resolve("talos-home"));
    }

    private Path touch(String filename) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, "fake", StandardCharsets.UTF_8);
        return path;
    }

    private static Config llamaCppConfig(Map<String, Object> llamaCpp) {
        Config cfg = new Config();
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("llama_cpp", new LinkedHashMap<>(llamaCpp));
        cfg.data.put("engines", engines);
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        return cfg;
    }

    private static HttpServer startHealthServer(int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
