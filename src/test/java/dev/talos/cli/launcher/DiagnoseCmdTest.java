package dev.talos.cli.launcher;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnoseCmdTest {

    @Test
    void engineSectionUsesActiveBackendNotHardCodedOllama() {
        String section = DiagnoseCmd.renderEngineSection(new Config(null), true);

        assertTrue(section.contains("Engine:"));
        assertTrue(section.contains("Backend: llama_cpp"));
        assertTrue(section.contains("Model:   talos-agent"));
        assertFalse(section.contains("Ollama:"));
    }

    @Test
    void terminalSectionReportsWhatDecidesExactDisplay() {
        var capabilities = dev.talos.cli.ui.TerminalCapabilities.detect(
                java.util.Map.of(), false, "Windows 11", java.nio.charset.Charset.forName("windows-1252"), null);
        String section = DiagnoseCmd.renderTerminalSection(capabilities,
                java.nio.charset.Charset.forName("UTF-8"), "Cp1252");

        assertTrue(section.contains("Terminal:"), section);
        assertTrue(section.contains("Interactive stdout:  no"), section);
        assertTrue(section.contains("Unicode output:      ASCII fallback"), section);
        assertTrue(section.contains("JVM stdout encoding: UTF-8"), section);
        assertTrue(section.contains("Platform encoding:   Cp1252"), section);
    }

    @Test
    void criticalFailureIsReportedForMalformedUserConfig(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                engines:
                  llama_cpp:
                    server_path: "C:\\Users\\bad\\llama-server.exe"
                """, StandardCharsets.UTF_8);
        Config config = new Config(configFile);

        String failure = DiagnoseCmd.criticalDiagnosisFailure(config.getReport(), "answer text", 0);

        assertTrue(failure.contains("User config could not be loaded"));
        assertTrue(failure.contains(configFile.toString()));
    }

    @Test
    void criticalFailureIsReportedForAnswerGenerationErrorText() {
        String failure = DiagnoseCmd.criticalDiagnosisFailure(
                new Config(tempMissingConfig()).getReport(),
                "Error: ConnectionFailed: Cannot connect to backend",
                0);

        assertTrue(failure.contains("Answer generation failed"));
        assertTrue(failure.contains("ConnectionFailed"));
    }

    @Test
    void noCriticalFailureForNormalAnswerWithoutMalformedConfig() {
        String failure = DiagnoseCmd.criticalDiagnosisFailure(
                new Config(tempMissingConfig()).getReport(),
                "Normal answer",
                0);

        assertTrue(failure.isBlank());
    }

    private static Path tempMissingConfig() {
        return Path.of(System.getProperty("java.io.tmpdir"), "talos-diagnose-missing-config.yaml");
    }
}
