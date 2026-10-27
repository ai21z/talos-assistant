package dev.talos.runtime.expectation;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactLiteralWriteCallCorrectorTest {
    @Test
    void correctsWriteFileAliasesWithoutDroppingRawToolName() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "Write exactly this content: expected body");
        ToolCall call = new ToolCall("file_utils:write_file", Map.of(
                "path", "README.md",
                "content", "model body"));

        ExactLiteralWriteCallCorrector.Correction correction =
                ExactLiteralWriteCallCorrector.correct(call, contract);

        assertTrue(correction.corrected());
        assertEquals("file_utils:write_file", correction.call().toolName());
        assertEquals("talos.write_file", correction.call().canonicalToolName());
        assertEquals("expected body", correction.call().param("content"));
    }
}
