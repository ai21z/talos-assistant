package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureIterationSignalsTest {
    @Test
    void mutatingDeniedFailureReportsMutatingDeniedSignal() {
        LoopState state = loopState();
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.denied("Permission denied"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, write, classification, result);

        assertTrue(signals.mutatingDenied());
        assertFalse(signals.approvalDenied());
        assertFalse(signals.pathPolicyBlocked());
        assertTrue(signals.unsupportedReadPaths().isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void unsupportedReadFailureReportsNormalizedUnsupportedReadPath() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "docs\\report.pdf"));
        ToolResult result = ToolResult.fail(ToolError.unsupportedFormat("unsupported binary document"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(read, result, "docs\\report.pdf");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, read, classification, result);

        assertFalse(signals.mutatingDenied());
        assertFalse(signals.approvalDenied());
        assertFalse(signals.pathPolicyBlocked());
        assertEquals(java.util.List.of("docs/report.pdf"), signals.unsupportedReadPaths());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void expectedTargetScopeBlockReportsPathPolicyAndStopsWithExistingErrorMessage() {
        LoopState state = loopState();
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "docs/other.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams(
                "Target outside expected targets before approval: docs/other.md"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "docs/other.md");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, write, classification, result);

        assertFalse(signals.mutatingDenied());
        assertFalse(signals.approvalDenied());
        assertTrue(signals.pathPolicyBlocked());
        assertTrue(signals.unsupportedReadPaths().isEmpty());
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(FailureAction.ASK_USER, state.failureDecision.action());
        assertEquals(result.errorMessage(), state.failureDecision.reason());
    }

    @Test
    void userApprovalDenialOnlyReportsApprovalDeniedForMutatingCalls() {
        LoopState state = loopState();
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));
        ToolResult result = ToolResult.fail(ToolError.denied("User did not approve talos.write_file."));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, write, classification, result);

        assertTrue(signals.mutatingDenied());
        assertTrue(signals.approvalDenied());
        assertFalse(signals.pathPolicyBlocked());
        assertTrue(signals.unsupportedReadPaths().isEmpty());
    }

    @Test
    void successfulResultProducesNoFailureSignals() {
        LoopState state = loopState();
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));
        ToolResult result = ToolResult.ok("ok");
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(write, result, "README.md");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, write, classification, result);

        assertFalse(signals.mutatingDenied());
        assertFalse(signals.approvalDenied());
        assertFalse(signals.pathPolicyBlocked());
        assertTrue(signals.unsupportedReadPaths().isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void readOnlyPreApprovalMessageDoesNotReportPathPolicySignal() {
        LoopState state = loopState();
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "../README.md"));
        ToolResult result = ToolResult.fail(ToolError.invalidParams(
                "Path not allowed before approval: ../README.md"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(read, result, "../README.md");

        ToolFailureIterationSignals.Result signals =
                ToolFailureIterationSignals.from(state, read, classification, result);

        assertFalse(signals.mutatingDenied());
        assertFalse(signals.approvalDenied());
        assertFalse(signals.pathPolicyBlocked());
        assertTrue(signals.unsupportedReadPaths().isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void executionStageDelegatesFailureIterationSignals() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolFailureIterationSignals.from"), source);
        assertFalse(source.contains("failureClassification.mutatingDenied()"), source);
        assertFalse(source.contains("failureClassification.unsupportedReadPath()"), source);
        assertFalse(source.contains("failureClassification.preApprovalPathPolicyBlock()"), source);
        assertFalse(source.contains("failureClassification.userApprovalDenial()"), source);
        assertFalse(source.contains("failureClassification.expectedTargetScopeBlock()"), source);
    }

    private static LoopState loopState() {
        return new LoopState("", java.util.List.of(), java.util.List.of(), null, null, null, 5, 0);
    }
}
