package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.tools.ToolAliasPolicy;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic guard for command-style output claims without command evidence. */
public final class CommandOutputTruthfulnessGuard {
    private CommandOutputTruthfulnessGuard() {}

    public static final String UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT =
            "[Command output truth check: no talos.run_command result was produced this turn.]\n\n"
            + "No command output is available because talos.run_command did not run. "
            + "The unsupported command-style output was withheld.";
    public static final String UNSUPPORTED_COMMAND_APPROVAL_DENIAL_REPLACEMENT =
            "[Command approval truth check: no talos.run_command approval denial was recorded.]\n\n"
            + "No command result is available because talos.run_command did not run. "
            + "The unsupported command approval-denial claim was withheld.";

    private static final Pattern GIT_STATUS_LINE = Pattern.compile(
            "(?im)^(on branch \\S+|your branch is .+|changes not staged for commit:"
                    + "|changes to be committed:|untracked files:|nothing to commit, working tree clean)\\s*$");

    public record Result(String answer, boolean unsupportedCommandOutputClaim) {
        public Result {
            answer = answer == null ? "" : answer;
        }
    }

    public static Result withholdUnsupportedCommandOutputIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (shouldWithholdUnsupportedCommandApprovalDenial(answer, plan, loopResult)) {
            return new Result(UNSUPPORTED_COMMAND_APPROVAL_DENIAL_REPLACEMENT, true);
        }
        if (!shouldWithholdUnsupportedCommandOutput(answer, plan, loopResult)) {
            return new Result(answer, false);
        }
        return new Result(UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, true);
    }

    public static boolean shouldWithholdUnsupportedCommandOutput(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (answer == null || answer.isBlank()) return false;
        if (hasSuccessfulRunCommand(loopResult)) return false;
        if (honestlyReportsCommandUnavailable(answer)) return false;
        if (!looksLikeGitStatusRequest(plan, answer)) return false;
        return looksLikeGitStatusOutput(answer);
    }

    private static boolean hasSuccessfulRunCommand(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.success()) continue;
            if ("talos.run_command".equals(canonicalToolName(outcome.toolName()))) return true;
        }
        return false;
    }

    private static boolean shouldWithholdUnsupportedCommandApprovalDenial(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (answer == null || answer.isBlank()) return false;
        if (hasDeniedRunCommand(loopResult)) return false;
        return claimsCommandApprovalDenied(answer) && looksLikeCommandExecutionClaim(plan, answer);
    }

    private static boolean hasDeniedRunCommand(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.denied()) continue;
            if ("talos.run_command".equals(canonicalToolName(outcome.toolName()))) return true;
        }
        return false;
    }

    private static boolean claimsCommandApprovalDenied(String answer) {
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return lower.contains("approval was denied")
                || lower.contains("approval denied")
                || lower.contains("denied command approval")
                || lower.contains("has denied command approval")
                || lower.contains("command approval was denied");
    }

    private static boolean looksLikeCommandExecutionClaim(CurrentTurnPlan plan, String answer) {
        String request = plan == null || plan.taskContract() == null
                ? ""
                : plan.taskContract().originalUserRequest();
        String lowerRequest = request == null ? "" : request.toLowerCase(Locale.ROOT);
        String lowerAnswer = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return lowerAnswer.contains("command")
                || lowerRequest.contains("talos.run_command")
                || lowerRequest.contains("run_command")
                || lowerRequest.contains("run the command")
                || lowerRequest.contains("execute the command")
                || lowerRequest.contains("call the command")
                || lowerRequest.contains("try the command");
    }

    private static boolean looksLikeGitStatusOutput(String answer) {
        return GIT_STATUS_LINE.matcher(answer).find();
    }

    private static boolean looksLikeGitStatusRequest(CurrentTurnPlan plan, String answer) {
        String request = plan == null || plan.taskContract() == null
                ? ""
                : plan.taskContract().originalUserRequest();
        return containsGitStatus(request) || containsGitStatus(answer);
    }

    private static boolean containsGitStatus(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("git status");
    }

    private static boolean honestlyReportsCommandUnavailable(String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);
        return (lower.contains("cannot run") || lower.contains("can't run") || lower.contains("did not run")
                || lower.contains("was not run") || lower.contains("no command") || lower.contains("not available"))
                && (lower.contains("run_command") || lower.contains("command") || lower.contains("git status"));
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
