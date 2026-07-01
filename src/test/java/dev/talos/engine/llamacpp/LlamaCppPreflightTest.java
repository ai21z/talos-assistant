package dev.talos.engine.llamacpp;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T784: pins the static managed-mode validation strings now shared between
 * {@link LlamaCppServerManager} (pre-launch) and the doctor preflight via
 * {@link LlamaCppPreflight}. The manager-side behavior is separately pinned
 * by {@code LlamaCppServerManagerTest} - together they prove the extraction
 * is behavior-preserving and stays that way.
 */
class LlamaCppPreflightTest {

    @TempDir Path tempDir;

    @Test
    void missingServerBinaryFailsWithThePinnedString() {
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", tempDir.resolve("missing-server.exe").toString(),
                "model_path", tempDir.resolve("agent.gguf").toString()));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertTrue(report.managed());
        assertFalse(report.filesOk());
        assertEquals("llama_cpp server_path is missing or not a file: "
                        + tempDir.resolve("missing-server.exe"),
                report.validationFailure());
    }

    @Test
    void missingModelFileFailsWithThePinnedString() throws IOException {
        Path exe = touch("llama-server.exe");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "model_path", tempDir.resolve("missing.gguf").toString()));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertFalse(report.filesOk());
        assertEquals("llama_cpp model_path or hf_repo is missing. model_path is not a file: "
                        + tempDir.resolve("missing.gguf"),
                report.validationFailure());
    }

    @Test
    void unsupportedGptossGgufArchitectureFailsWithThePinnedString() throws IOException {
        Path exe = touch("llama-server.exe");
        Path model = writeGgufWithArchitecture("gptoss");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "model_path", model.toString(),
                "model", "gpt-oss-20b"));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertFalse(report.filesOk());
        assertTrue(report.validationFailure().contains("unsupported GGUF architecture 'gptoss'"),
                report.validationFailure());
        assertTrue(report.validationFailure().contains("gpt-oss-20b"), report.validationFailure());
        assertTrue(report.validationFailure().contains(model.toString()), report.validationFailure());
    }

    @Test
    void validManagedFilesPass() throws IOException {
        Path exe = touch("llama-server.exe");
        Path model = touch("agent.gguf");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "model_path", model.toString()));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertTrue(report.managed());
        assertTrue(report.filesOk());
        assertEquals("", report.validationFailure());
    }

    @Test
    void huggingFaceSourceSkipsTheLocalModelFileCheck() throws IOException {
        Path exe = touch("llama-server.exe");
        Config cfg = config(Map.of(
                "mode", "managed",
                "server_path", exe.toString(),
                "hf_repo", "ggml-org/gpt-oss-20b-GGUF"));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertTrue(report.filesOk());
    }

    @Test
    void connectOnlyModeValidatesNothingAndReportsUnmanaged() {
        Config cfg = config(Map.of(
                "mode", "connect_only",
                "host", "http://127.0.0.1",
                "port", 18080));

        LlamaCppPreflight.Report report = LlamaCppPreflight.check(cfg);

        assertFalse(report.managed());
        assertTrue(report.filesOk());
        assertEquals("http://127.0.0.1:18080", report.baseUrl());
        assertEquals(18080, report.port());
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
}
