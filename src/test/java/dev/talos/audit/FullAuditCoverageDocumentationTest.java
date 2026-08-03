package dev.talos.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullAuditCoverageDocumentationTest {
    private static final List<String> CURRENT_MODEL_EXPOSED_NATIVE_TOOLS = List.of(
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
            "talos.run_command");

    @Test
    void fullE2eAuditDocsNameEveryCurrentModelExposedNativeTool() throws IOException {
        String workflow = read("work-cycle-docs/runbooks/live-audit.md");

        for (String tool : CURRENT_MODEL_EXPOSED_NATIVE_TOOLS) {
            assertTrue(workflow.contains(tool), () -> "workflow missing native tool: " + tool);
        }
    }

    @Test
    void talosbenchPromptBankMentionsEveryCurrentModelExposedNativeTool() throws IOException {
        String cases = read("tools/manual-eval/talosbench-cases.json");

        for (String tool : CURRENT_MODEL_EXPOSED_NATIVE_TOOLS) {
            assertTrue(cases.contains(tool), () -> "TalosBench prompt bank missing native tool: " + tool);
        }
    }

    @Test
    void registeredBatchCompatibilitySurfaceIsNotAdvertisedAsProviderCallable() throws IOException {
        String readme = read("README.md");
        String workflow = read("work-cycle-docs/runbooks/live-audit.md");
        String cases = read("tools/manual-eval/talosbench-cases.json");

        assertTrue(readme.contains(
                "| `apply_workspace_batch` | internal compatibility surface; not exposed to models | required |"),
                readme);
        assertTrue(readme.contains("current assistant planner does not expose it"), readme);
        assertTrue(readme.contains("typed multi-operation authority contract"), readme);
        assertTrue(workflow.contains("explicitly excluded registered"), workflow);
        assertTrue(workflow.contains("compatibility surface"), workflow);
        assertTrue(workflow.contains("`workspace-batch-no-typed-authority`"), workflow);
        assertFalse(cases.contains("talos.apply_workspace_batch"), cases);
        assertFalse(cases.contains("full-audit-apply-workspace-batch-tool-probe"), cases);
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
