package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolAliasPolicy;

import java.util.Locale;

/**
 * Runtime-owned command verification result selection and final-answer text.
 */
public final class CommandOutcomeRenderer {
    private CommandOutcomeRenderer() {}

    public record Conclusion(
            ToolCallLoop.ToolOutcome outcome,
            boolean succeeded,
            boolean failed,
            boolean denied
    ) {
        public static Conclusion none() {
            return new Conclusion(null, false, false, false);
        }
    }

    public static Conclusion conclusion(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return Conclusion.none();
        ToolCallLoop.ToolOutcome firstSuccess = null;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !"talos.run_command".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success()) {
                return new Conclusion(outcome, false, !outcome.denied(), outcome.denied());
            }
            if (firstSuccess == null) {
                firstSuccess = outcome;
            }
        }
        return firstSuccess == null
                ? Conclusion.none()
                : new Conclusion(firstSuccess, true, false, false);
    }

    public static String failureReplacement(Conclusion conclusion) {
        ToolCallLoop.ToolOutcome outcome = conclusion == null ? null : conclusion.outcome();
        String detail = outcome == null ? "" : singleLine(outcome.errorMessage());
        if (conclusion != null && conclusion.denied()) {
            return "[Command not run: talos.run_command was blocked before execution.]\n\n"
                    + (detail.isBlank()
                    ? "No command result is available because the command was not approved or policy blocked it."
                    : detail);
        }
        String prefix = (outcome != null
                && outcome.failureReason() == dev.talos.tools.ToolFailureReason.COMMAND_TIMEOUT)
                ? "[Command timed out: talos.run_command did not finish successfully.]"
                : "[Command failed: talos.run_command did not finish successfully.]";
        return prefix + "\n\n"
                + (detail.isBlank() ? "The command returned a failed result." : detail);
    }

    public static String successReplacement(Conclusion conclusion) {
        ToolCallLoop.ToolOutcome outcome = conclusion == null ? null : conclusion.outcome();
        String summary = outcome == null ? "" : singleLine(outcome.summary());
        if (summary.isBlank()) {
            summary = "Command succeeded: talos.run_command completed";
        }
        if (!summary.endsWith(".") && !summary.endsWith("!") && !summary.endsWith("?")) {
            summary += ".";
        }
        return summary;
    }

    public static String requiredButNotRunReplacement() {
        return "[Command not run: talos.run_command was required for this explicit command request.]\n\n"
                + "No command result is available because the model did not call talos.run_command.";
    }

    public static String unsupportedCommandNotAvailableReplacement() {
        return "[Command not run: Python execution is outside the current bounded command profile.]\n\n"
                + "No Python, pytest, or .py command result is available in this beta turn.";
    }

    public static boolean satisfiesVerifyOnlyRequest(TaskContract contract) {
        return contract != null
                && contract.type() == TaskType.VERIFY_ONLY
                && contract.verificationRequired()
                && !contract.mutationRequested();
    }

    public static boolean explicitCommandVerificationRequired(TaskContract contract) {
        return contract != null
                && "explicit-command-verification-request".equals(contract.classificationReason());
    }

    public static boolean unsupportedCommandVerificationRequest(TaskContract contract) {
        return contract != null
                && "unsupported-command-verification-request".equals(contract.classificationReason());
    }

    public static boolean unsupportedPythonCommandExecutionRequest(TaskContract contract) {
        return contract != null
                && TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(contract.originalUserRequest());
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) return "";
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
