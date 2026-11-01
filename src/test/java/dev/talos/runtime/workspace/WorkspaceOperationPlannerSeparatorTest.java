package dev.talos.runtime.workspace;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationPlannerSeparatorTest {
    private static List<ToolCall> backslashCalls() {
        return List.of(
                new ToolCall("talos.move_path", Map.of("from", "scratch/todo.md", "to", "docs\\todo.md")),
                new ToolCall("talos.move_path", Map.of("from", "scratch\\todo.md", "to", "docs/todo.md")),
                new ToolCall("talos.copy_path", Map.of("from", "README.md", "to", "docs\\README.md")),
                new ToolCall("talos.rename_path", Map.of("path", "docs\\todo.md", "new_name", "tasks.md")),
                new ToolCall("talos.mkdir", Map.of("path", "docs\\notes")),
                new ToolCall("talos.delete_path", Map.of("path", "docs\\old.md")));
    }

    // On POSIX the plan would name docs/todo.md while the tool changes a root file named docs\todo.md.
    @Test @DisabledOnOs(OS.WINDOWS)
    void backslashOperationPathIsRefusedBeforeApprovalWhereItIsNotASeparator() {
        for (ToolCall call : backslashCalls()) {
            Optional<String> rejection = WorkspaceOperationPlanner.validateBeforeApproval(call);
            assertTrue(rejection.isPresent(), call.toString());
            assertTrue(rejection.get().contains("backslash"), rejection.get());
            assertTrue(rejection.get().endsWith("No approval was requested and no file was changed."),
                    rejection.get());
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void backslashOperationPathIsASeparatorOnWindows() {
        for (ToolCall call : backslashCalls()) {
            assertEquals(Optional.empty(), WorkspaceOperationPlanner.validateBeforeApproval(call), call.toString());
        }
    }

    @Test
    void forwardSlashOperationPathIsAccepted() {
        assertEquals(Optional.empty(), WorkspaceOperationPlanner.validateBeforeApproval(
                new ToolCall("talos.move_path", Map.of("from", "scratch/todo.md", "to", "docs/todo.md"))));
    }

    @Test
    void missingParameterIsReportedBeforeSeparators() {
        assertEquals(
                Optional.of("Invalid talos.move_path call: missing required parameter `to`. "
                        + "No approval was requested and no file was changed."),
                WorkspaceOperationPlanner.validateBeforeApproval(
                        new ToolCall("talos.move_path", Map.of("from", "scratch\\todo.md"))));
    }
}
