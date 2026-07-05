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
        String workflow = read("work-cycle-docs/runbooks/live-audit.md");

        for (String tool : CURRENT_NATIVE_TOOLS) {
            assertTrue(workflow.contains(tool), () -> "workflow missing native tool: " + tool);
        }
    }

    @Test
    void talosbenchPromptBankMentionsEveryCurrentNativeTool() throws IOException {
        String cases = read("tools/manual-eval/talosbench-cases.json");

        for (String tool : CURRENT_NATIVE_TOOLS) {
            assertTrue(cases.contains(tool), () -> "TalosBench prompt bank missing native tool: " + tool);
        }
    }

    @Test
    void talosbenchPythonCaseRequiresExpectedOutputFiles() throws IOException {
        String cases = read("tools/manual-eval/talosbench-cases.json");

        assertTrue(cases.contains("\"id\": \"t325-python-command-boundary\""),
                "TalosBench prompt bank must include the T325 Python command-boundary case.");
        assertTrue(cases.contains("\"expectedFinalFilePaths\""),
                "T325 TalosBench case must use expectedFinalFilePaths so missing Python outputs fail the audit.");
        assertTrue(cases.contains("\"dijkstra.py\""),
                "T325 TalosBench case must assert dijkstra.py exists after a claimed create/test turn.");
        assertTrue(cases.contains("\"test_dijkstra.py\""),
                "T325 TalosBench case must assert test_dijkstra.py exists after a claimed create/test turn.");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
