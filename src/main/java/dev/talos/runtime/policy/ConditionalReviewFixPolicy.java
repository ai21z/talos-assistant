package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Handles conditional "review and fix if needed" turns after real inspection evidence exists. */
public final class ConditionalReviewFixPolicy {
    private static final String CLASSIFICATION_REASON = "explicit-review-and-fix-request";

    private ConditionalReviewFixPolicy() {}

    public static boolean isConditionalReviewAndFix(TaskContract contract) {
        return contract != null
                && contract.mutationAllowed()
                && CLASSIFICATION_REASON.equals(contract.classificationReason());
    }

    public static Optional<String> noChangeAnswerIfCurrentWorkspacePasses(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (loopResult == null) return Optional.empty();
        return noChangeAnswerIfCurrentWorkspacePasses(
                contract,
                loopResult.readPaths(),
                loopResult.toolNames(),
                loopResult.mutatingToolSuccesses(),
                workspace);
    }

    public static Optional<String> noChangeAnswerIfCurrentWorkspacePasses(
            TaskContract contract,
            Collection<String> pathsReadThisTurn,
            List<String> toolNames,
            int mutatingToolSuccesses,
            Path workspace
    ) {
        if (!isConditionalReviewAndFix(contract)) return Optional.empty();
        if (!inspectionOnlyEvidence(pathsReadThisTurn, toolNames, mutatingToolSuccesses)) {
            return Optional.empty();
        }

        StaticTaskVerifier.WebDiagnostics diagnostics =
                StaticTaskVerifier.currentWebDiagnostics(workspace, contract);
        if (!diagnostics.available() || !diagnostics.problems().isEmpty()) {
            return Optional.empty();
        }

        LocalTurnTraceCapture.recordActionObligation(
                ActionObligation.MUTATING_TOOL_REQUIRED.name(),
                "SATISFIED_BY_INSPECTION",
                "conditional review/fix inspection found no current static web blocker");
        return Optional.of(deterministicNoChangeAnswer(diagnostics));
    }

    private static boolean inspectionOnlyEvidence(
            Collection<String> pathsReadThisTurn,
            List<String> toolNames,
            int mutatingToolSuccesses
    ) {
        if (mutatingToolSuccesses > 0) return false;
        if (pathsReadThisTurn == null || pathsReadThisTurn.isEmpty()) return false;
        if (toolNames == null || toolNames.isEmpty()) return false;
        for (String toolName : toolNames) {
            if (ToolCallSupport.isMutatingTool(toolName)) return false;
        }
        return toolNames.stream().anyMatch(ToolCallSupport::isReadOnlyTool);
    }

    private static String deterministicNoChangeAnswer(StaticTaskVerifier.WebDiagnostics diagnostics) {
        return "[Conditional review result: No file change was needed.]\n\n"
                + "Talos inspected the current workspace files and did not find an obvious static "
                + "HTML/CSS/JavaScript blocker for this review-and-fix request.\n"
                + "Checked files: " + String.join(", ", diagnostics.primaryFiles()) + ".\n"
                + "No files were changed.";
    }
}
