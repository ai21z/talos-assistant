package dev.talos.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSinkSafetyInventoryTest {
    private static final Path INVENTORY =
            Path.of("work-cycle-docs/reports/runtime-sink-safety-inventory.md");

    @Test
    void inventoryCoversCurrentDurableSinkFamiliesAndOwners() throws Exception {
        String inventory = Files.readString(INVENTORY);
        for (String required : List.of(
                "SLF4J/logback file logs",
                "Prompt-debug Markdown",
                "Provider-body JSON",
                "Local trace JSON/text",
                "Session snapshot",
                "Turn JSONL",
                "Command output summaries",
                "Synchronized audit bundles",
                "Manual audit transcripts",
                "SafeLogFormatter",
                "PromptDebugInspector",
                "JsonSessionStore",
                "JsonTurnLogAppender",
                "LocalTurnTraceCapture",
                "ProcessCommandRunner",
                "SynchronizedApprovalAuditRunner",
                "ArtifactCanaryScanner")) {
            assertTrue(inventory.contains(required), required);
        }
    }
}
