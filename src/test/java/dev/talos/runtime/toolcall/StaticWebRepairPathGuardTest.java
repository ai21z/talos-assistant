package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebRepairPathGuardTest {

    @Test
    void rejectsNonExpectedFileWriteBeforeApprovalForStaticWebTargetSet() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(),
                "Make this Retrocats website even more polished and complete.",
                "workspace-static-web-surface-targets");
        ToolCall call = new ToolCall(
                "talos.write_file",
                Map.of("path", "README.md", "content", "Placeholder"));

        String diagnostic = StaticWebRepairPathGuard.diagnostic(call, contract, "README.md");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("Target outside expected targets before approval"), diagnostic);
        assertTrue(diagnostic.contains("index.html"), diagnostic);
        assertTrue(diagnostic.contains("style.css"), diagnostic);
        assertTrue(diagnostic.contains("script.js"), diagnostic);
    }
}
