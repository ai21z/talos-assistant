package dev.talos.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FullAuditCoverageDocumentationTest {
    private static final List<String> CURRENT_NATIVE_TOOLS = List.of(
            "talos.list_dir",
            "talos.read_file",
            "talos.grep",
            "talos.retrieve",
            "talos.write_file",
            "talos.edit_file",
            "talos.mkdir",
            "talos.copy_path",
            "talos.move_path",
            "talos.rename_path",
            "talos.delete_path",
            "talos.apply_workspace_batch",
            "talos.run_command");

    @Test
    void fullE2eAuditDocsNameEveryCurrentNativeTool() throws IOException {
        String workflow = read("work-cycle-docs/full-e2e-audit-workflow.md");
        String operatorPrompt = read("work-cycle-docs/full-e2e-audit-operator-prompt.md");

        for (String tool : CURRENT_NATIVE_TOOLS) {
            assertTrue(workflow.contains(tool), () -> "workflow missing native tool: " + tool);
            assertTrue(operatorPrompt.contains(tool), () -> "operator prompt missing native tool: " + tool);
        }
    }

    @Test
    void talosbenchPromptBankMentionsEveryCurrentNativeTool() throws IOException {
        String cases = read("tools/manual-eval/talosbench-cases.json");

        for (String tool : CURRENT_NATIVE_TOOLS) {
            assertTrue(cases.contains(tool), () -> "TalosBench prompt bank missing native tool: " + tool);
        }
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
