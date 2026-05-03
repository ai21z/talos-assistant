package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupCmdTest {

    @Test
    void setupCommandDescriptionIsBackendNeutral() {
        CommandLine.Command command = SetupCmd.class.getAnnotation(CommandLine.Command.class);

        assertTrue(command.description()[0].contains("local model"));
        assertFalse(command.description()[0].contains("Install Ollama"));
    }

    @Test
    void setupSummaryDoesNotSayTalosRequiresOllama() {
        String summary = SetupCmd.setupSummary();

        assertTrue(summary.contains("llama.cpp"));
        assertFalse(summary.contains("requires Ollama"));
    }
}
