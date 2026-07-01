package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T786: the REPL `/doctor` command runs the default (never-start) probe set. */
class DoctorCommandTest {

    @TempDir Path tempDir;

    @Test
    void specPlacesDoctorInTheDebugGroup() {
        CommandSpec spec = new DoctorCommand(tempDir).spec();

        assertEquals("doctor", spec.name());
        assertEquals("/doctor", spec.usage());
        assertEquals("Run environment preflight checks.", spec.summary());
        assertEquals(CommandGroup.DEBUG, spec.group());
    }

    @Test
    void executeRendersTheFullProbeReportWithoutStartingAnything() throws Exception {
        Config cfg = llamaCppConfig(Map.of(
                "mode", "managed",
                "server_path", touch("llama-server.exe").toString(),
                "model_path", touch("agent.gguf").toString(),
                "host", "http://127.0.0.1",
                "port", 1)); // nothing listens on port 1 - managed-not-running WARN
        DoctorCommand command = new DoctorCommand(
                tempDir.resolve("ws"), tempDir.resolve("talos-home"));

        Result result = command.execute("", Context.builder(cfg).build());

        assertInstanceOf(Result.TrustedInfo.class, result);
        String report = ((Result.TrustedInfo) result).text;
        assertTrue(report.contains("PASS  engine-files"), report);
        assertTrue(report.contains("WARN  server"), report);
        assertTrue(report.contains("Doctor summary:"), report);
        assertTrue(report.contains("Environment is ready."), report);
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
