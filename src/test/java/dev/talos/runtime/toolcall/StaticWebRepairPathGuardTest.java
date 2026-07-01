package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebRepairPathGuardTest {

    @Test
    void rejectsRootDirectoryWriteBeforeApprovalForStaticWebTargetSet() {
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
                Map.of("path", "./", "content", "Placeholder"));

        String diagnostic = StaticWebRepairPathGuard.diagnostic(call, contract, "./");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("Target outside expected targets before approval"), diagnostic);
        assertTrue(diagnostic.contains("index.html"), diagnostic);
        assertTrue(diagnostic.contains("style.css"), diagnostic);
        assertTrue(diagnostic.contains("script.js"), diagnostic);
    }

    @Test
    void rejectsPlaceholderWritePathBeforeApprovalForStaticWebTargetSet() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("script.js"),
                Set.of(),
                Set.of("scripts.js"),
                "Read script.js, then fix the selector bug by changing .missing-button to .cta-button. "
                        + "Do not edit scripts.js.",
                "explicit-read-then-mutation-request");
        ToolCall call = new ToolCall(
                "talos.write_file",
                Map.of("path", "?", "content", "?"));

        String diagnostic = StaticWebRepairPathGuard.diagnostic(call, contract, "?");

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("Target outside expected targets before approval"), diagnostic);
        assertTrue(diagnostic.contains("script.js"), diagnostic);
        assertTrue(diagnostic.contains("Similar filenames are not substitutes"), diagnostic);
    }

    @Test
    void leavesOrdinaryOffTargetFilesToExpectedTargetPolicy() {
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

        assertNull(diagnostic);
    }
}
