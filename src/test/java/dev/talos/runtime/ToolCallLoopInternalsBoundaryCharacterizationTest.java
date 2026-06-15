package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallLoopInternalsBoundaryCharacterizationTest {

    @Test
    void reportPinsToolLoopInternalsScopingBoundary() throws Exception {
        String report = Files.readString(Path.of(
                "work-cycle-docs/reports/t825-tool-loop-internals-boundary-scoping.md"));

        assertAll(
                () -> assertTrue(report.contains("INFERRED_REVIEW"), report),
                () -> assertTrue(report.contains("ToolCallLoop | `334`"), report),
                () -> assertTrue(report.contains("LoopState | `292`"), report),
                () -> assertTrue(report.contains("ToolCallSupport | `235`"), report),
                () -> assertTrue(report.contains("ToolCallExecutionStage | `231`"), report),
                () -> assertTrue(report.contains("TurnProcessor | `314`"), report),
                () -> assertTrue(report.contains("TaskContract | `301`"), report),
                () -> assertTrue(report.contains("Candidate T826 Owners"), report),
                () -> assertTrue(report.contains("Do Not Move In T825"), report),
                () -> assertTrue(report.contains("T825 does not authorize production extraction"), report),
                () -> assertTrue(report.contains("ToolCallLoopOrchestrationCharacterizationTest"), report),
                () -> assertTrue(report.contains("ToolCallRepromptStageTest"), report));
    }
}
