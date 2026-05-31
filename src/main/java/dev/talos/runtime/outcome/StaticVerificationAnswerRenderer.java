package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolAliasPolicy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned final-answer fragments for post-apply static verification.
 */
public final class StaticVerificationAnswerRenderer {
    private StaticVerificationAnswerRenderer() {}

    public static String passedAnnotation(TaskVerificationResult result) {
        StringBuilder out = new StringBuilder();
        out.append("[Static verification: passed - ")
                .append(verificationSummary(result))
                .append("]\n\n");
        List<String> contextualFacts = contextualStaticWebFacts(result);
        if (!contextualFacts.isEmpty()) {
            out.append("Contextual static-web findings outside this turn:");
            for (String fact : contextualFacts.subList(0, Math.min(5, contextualFacts.size()))) {
                out.append("\n- ").append(singleLine(fact));
            }
            if (contextualFacts.size() > 5) {
                out.append("\n- ... ").append(contextualFacts.size() - 5).append(" more");
            }
            out.append("\n\n");
        }
        return out.toString();
    }

    public static String readbackOnlyAnnotation(
            TaskVerificationResult result,
            ToolCallLoop.LoopResult loopResult
    ) {
        String readbackKind = hasSuccessfulWorkspaceOperation(loopResult)
                ? "Workspace operation/readback"
                : "File write/readback";
        String verifierReason = hasUnsatisfiedTaskSpecificVerification(result)
                ? "Task-specific verification did not satisfy the requested claim, "
                : "No task-specific verifier was applicable, ";
        return "[" + readbackKind + " passed. " + verifierReason
                + "so task completion was not verified. "
                + verificationSummary(result) + "]\n\n";
    }

    public static String failedAnnotation(TaskVerificationResult result) {
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The requested task is not verified complete. ")
                .append("Applied changes below are workspace changes only; unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        out.append("\n\n");
        return out.toString();
    }

    public static String failedReplacement(
            TaskVerificationResult result,
            ToolCallLoop.LoopResult loopResult
    ) {
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The requested task is not verified complete. ")
                .append("Applied changes, if any, are workspace changes only; unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        List<ToolCallLoop.ToolOutcome> applied = successfulMutatingOutcomes(loopResult);
        if (!applied.isEmpty()) {
            out.append("\n\nApplied mutating tool calls:");
            for (ToolCallLoop.ToolOutcome outcome : applied.subList(0, Math.min(5, applied.size()))) {
                out.append("\n- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(outcome.summary().isBlank() ? "mutation applied" : singleLine(outcome.summary()));
            }
            if (applied.size() > 5) {
                out.append("\n- ... ").append(applied.size() - 5).append(" more");
            }
        }
        out.append("\n\nThe assistant success summary was replaced with this runtime verification result because verification failed.");
        return out.toString().stripTrailing();
    }

    public static String partialFailedAnnotation(TaskVerificationResult result) {
        StringBuilder out = new StringBuilder();
        out.append("[Partial verification: static checks failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The turn remains partial. Some changes were applied, but unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nRemaining static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        out.append("\n\n");
        return out.toString();
    }

    public static String unavailableAnnotation(TaskVerificationResult result) {
        return "[Static verification incomplete: " + verificationSummary(result) + "]\n\n";
    }

    public static String changedFilesSummary(ToolCallLoop.LoopResult loopResult) {
        List<ToolCallLoop.ToolOutcome> applied = successfulMutatingOutcomes(loopResult);
        if (applied.isEmpty()) return "";
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : applied) {
            if (outcome == null) continue;
            if (outcome.workspaceOperationPlan() != null
                    && !outcome.workspaceOperationPlan().changedPaths().isEmpty()) {
                paths.addAll(outcome.workspaceOperationPlan().changedPaths());
                continue;
            }
            if (outcome.pathHint() == null || outcome.pathHint().isBlank()) continue;
            paths.add(outcome.pathHint().strip().replace('\\', '/'));
        }
        if (paths.size() <= 1) return "";
        return "Updated " + paths.size() + " files: " + String.join(", ", paths) + ".\n\n";
    }

    private static boolean hasSuccessfulWorkspaceOperation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .filter(Objects::nonNull)
                .anyMatch(outcome -> outcome.success()
                        && outcome.mutating()
                        && isWorkspaceOperationOutcome(outcome));
    }

    private static boolean isWorkspaceOperationOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null) return false;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan != null && plan.operationKind() != WorkspaceOperationPlan.OperationKind.WRITE_FILE) {
            return true;
        }
        String tool = canonicalToolName(outcome.toolName());
        return "talos.move_path".equals(tool)
                || "talos.copy_path".equals(tool)
                || "talos.rename_path".equals(tool)
                || "talos.mkdir".equals(tool)
                || "talos.apply_workspace_batch".equals(tool);
    }

    private static List<ToolCallLoop.ToolOutcome> successfulMutatingOutcomes(
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        return loopResult.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
    }

    private static String verificationSummary(TaskVerificationResult result) {
        if (result == null || result.summary() == null || result.summary().isBlank()) {
            return "no additional detail";
        }
        String summary = result.summary().replace('\n', ' ').replace('\r', ' ').strip();
        return summary.length() <= 240 ? summary : summary.substring(0, 237) + "...";
    }

    private static boolean hasUnsatisfiedTaskSpecificVerification(TaskVerificationResult result) {
        String summary = verificationSummary(result).toLowerCase();
        return summary.contains("verification was not satisfied")
                || summary.contains("required verification")
                || summary.contains("required interaction verification");
    }

    private static List<String> contextualStaticWebFacts(TaskVerificationResult result) {
        if (result == null || result.facts() == null || result.facts().isEmpty()) return List.of();
        return result.facts().stream()
                .filter(fact -> fact != null
                        && fact.startsWith("Contextual static-web finding outside this turn: "))
                .toList();
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) return "no additional detail";
        String out = value.replace('\r', ' ').replace('\n', ' ').strip();
        return out.length() <= 240 ? out : out.substring(0, 237) + "...";
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
