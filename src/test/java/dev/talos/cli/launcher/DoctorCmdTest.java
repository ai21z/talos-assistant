package dev.talos.cli.launcher;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T784: `talos doctor` wiring and the exit-code contract of the run seam. */
class DoctorCmdTest {

    @TempDir Path tempDir;

    @Test
    void doctorIsARegisteredSubcommand() {
        CommandLine root = new CommandLine(new RootCmd());

        assertTrue(root.getSubcommands().containsKey("doctor"),
                "talos doctor must be registered on the root command");
    }

    @Test
    void cleanEnvironmentExitsZeroWithoutStartingAnything() throws Exception {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", touch("agent.gguf").toString(),
                "host", "http://127.0.0.1",
                "port", 1));  // nothing listens on port 1 - managed-not-running is a WARN
        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        int exit = DoctorCmd.run(cfg, tempDir.resolve("ws"), tempDir.resolve("home"),
                false, new PrintStream(bout, true, StandardCharsets.UTF_8));

        String report = bout.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, report);
        assertTrue(report.contains("PASS  engine-files"), report);
        assertTrue(report.contains("WARN  server"), report);
        assertTrue(report.contains("Environment is ready."), report);
    }

    @Test
    void missingModelFileExitsOne() throws Exception {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", tempDir.resolve("missing.gguf").toString(),
                "host", "http://127.0.0.1",
                "port", 1));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        int exit = DoctorCmd.run(cfg, tempDir.resolve("ws"), tempDir.resolve("home"),
                false, new PrintStream(bout, true, StandardCharsets.UTF_8));

        String report = bout.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, report);
        assertTrue(report.contains("FAIL  engine-files"), report);
        assertTrue(report.contains("re-run 'talos doctor'"), report);
    }

    @Test
    void workspaceResolutionPrefersExplicitRoot() {
        Path resolved = DoctorCmd.resolveWorkspace(tempDir.toString());

        assertEquals(tempDir.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void workspaceResolutionFallsBackToCwdWhenNoRootGiven() {
        // TALOS_WORKSPACE may or may not be set on the host; only assert the
        // no-arg result is absolute and normalized.
        Path resolved = DoctorCmd.resolveWorkspace(null);

        assertTrue(resolved.isAbsolute());
        assertEquals(resolved, resolved.normalize());
    }

    private Path touch(String filename) throws Exception {
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
        llm.put("model", "test-model");
        cfg.data.put("llm", llm);
        return cfg;
    }
}
