package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.tools.ToolAliasPolicy;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic guard for command/tool output claims without producer evidence. */
public final class CommandOutputTruthfulnessGuard {
    private CommandOutputTruthfulnessGuard() {}

    public static final String UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT =
            "[Command output truth check: no talos.run_command result was produced this turn.]\n\n"
            + "No command output is available because talos.run_command did not run. "
            + "The unsupported command-style output was withheld.";
    public static final String UNSUPPORTED_FILE_CONTENT_REPLACEMENT =
            "[Workspace content truth check: no matching talos.read_file result was produced this turn.]\n\n"
            + "No file-content output is available because talos.read_file did not read the claimed file content. "
            + "The unsupported file-content output was withheld.";
    public static final String UNSUPPORTED_COMMAND_APPROVAL_DENIAL_REPLACEMENT =
            "[Command approval truth check: no talos.run_command approval denial was recorded.]\n\n"
            + "No command result is available because talos.run_command did not run. "
            + "The unsupported command approval-denial claim was withheld.";

    private static final Pattern GIT_STATUS_LINE = Pattern.compile(
            "(?im)^(on branch \\S+|your branch is .+|changes not staged for commit:"
                    + "|changes to be committed:|untracked files:|nothing to commit, working tree clean)\\s*$");
    private static final Pattern TEST_RUN_OUTPUT_LINE = Pattern.compile(
            "(?im)^(BUILD (SUCCESSFUL|FAILED)\\b.*|\\d+\\s+tests?\\s+completed,\\s+\\d+\\s+failed"
                    + "|Tests run:\\s*\\d+,\\s*Failures:\\s*\\d+.*)$");
    private static final Pattern PROCESS_LIST_OUTPUT = Pattern.compile(
            "(?im)^\\s*(PID\\s+PPID\\s+COMMAND|PID\\s+TTY\\s+TIME\\s+CMD"
                    + "|Image Name\\s+PID\\s+Session Name)\\s*$");
    private static final Pattern SHELL_LISTING_OUTPUT = Pattern.compile(
            "(?im)^\\s*(\\$\\s*(ls|dir|cat|type|Get-Content)\\b.*"
                    + "|(?:ls|dir|cat|type|Get-Content)\\s+output:|Directory:\\s+.+"
                    + "|Mode\\s+LastWriteTime\\s+Length\\s+Name)\\s*$");
    private static final Pattern FILE_CONTENT_CLAIM = Pattern.compile(
            "(?im)^\\s*([\\w./\\\\-]+\\.(?:md|txt|json|java|js|css|html|xml|yml|yaml|toml|properties|csv|tsv|ini))"
                    + "\\s+(?:contains|says|content(?:s)?(?:\\s+are)?):\\s*$");

    public record Result(
            String answer,
            boolean unsupportedCommandOutputClaim,
            String shape,
            String missingProducer
    ) {
        public Result {
            answer = answer == null ? "" : answer;
            shape = shape == null ? "" : shape;
            missingProducer = missingProducer == null ? "" : missingProducer;
        }

        public Result(String answer, boolean unsupportedCommandOutputClaim) {
            this(answer, unsupportedCommandOutputClaim, "", "");
        }
    }

    private record UnsupportedOutputClaim(String shape, String missingProducer, String replacement) {}

    public static Result withholdUnsupportedCommandOutputIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (shouldWithholdUnsupportedCommandApprovalDenial(answer, plan, loopResult)) {
            return new Result(
                    UNSUPPORTED_COMMAND_APPROVAL_DENIAL_REPLACEMENT,
                    true,
                    "command-approval-denial",
                    "talos.run_command denial");
        }
        UnsupportedOutputClaim unsupported = unsupportedOutputClaim(answer, plan, loopResult);
        if (unsupported == null) {
            return new Result(answer, false);
        }
        return new Result(unsupported.replacement(), true, unsupported.shape(), unsupported.missingProducer());
    }

    public static boolean shouldWithholdUnsupportedCommandOutput(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        return unsupportedOutputClaim(answer, plan, loopResult) != null;
    }

    private static UnsupportedOutputClaim unsupportedOutputClaim(
            String answer,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (answer == null || answer.isBlank()) return null;
        boolean honestCommandUnavailable = honestlyReportsCommandUnavailable(answer);
        if (!honestCommandUnavailable && !hasSuccessfulRunCommand(loopResult)) {
            String commandShape = unsupportedCommandOutputShape(answer, plan);
            if (!commandShape.isBlank()) {
                return new UnsupportedOutputClaim(
                        commandShape,
                        "talos.run_command",
                        UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT);
            }
        }
        if (!hasSuccessfulReadForClaimedFile(answer, loopResult)
                && looksLikeFileContentClaim(answer)) {
            return new UnsupportedOutputClaim(
                    "file-content",
                    "talos.read_file",
                    UNSUPPORTED_FILE_CONTENT_REPLACEMENT);
        }
        return null;
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

    private static String unsupportedCommandOutputShape(String answer, CurrentTurnPlan plan) {
        if (looksLikeGitStatusRequest(plan, answer) && looksLikeGitStatusOutput(answer)) {
            return "git-status";
        }
        if (looksLikeTestRunContext(plan, answer) && TEST_RUN_OUTPUT_LINE.matcher(answer).find()) {
            return "test-run";
        }
        if (looksLikeProcessListContext(plan, answer) && PROCESS_LIST_OUTPUT.matcher(answer).find()) {
            return "process-list";
        }
        if (looksLikeShellListingContext(plan, answer) && SHELL_LISTING_OUTPUT.matcher(answer).find()) {
            return "shell-listing";
        }
        return "";
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

    private static boolean looksLikeTestRunContext(CurrentTurnPlan plan, String answer) {
        String request = request(plan);
        String lowerRequest = lower(request);
        String lowerAnswer = lower(answer);
        return containsAny(lowerRequest, "test", "tests", "gradle", "maven", "pytest", "build")
                || lowerAnswer.contains("test result")
                || lowerAnswer.contains("tests completed");
    }

    private static boolean looksLikeProcessListContext(CurrentTurnPlan plan, String answer) {
        String lowerRequest = lower(request(plan));
        String lowerAnswer = lower(answer);
        return containsAny(lowerRequest, "process list", "processes", "ps ", "tasklist")
                || containsAny(lowerAnswer, "process list", "tasklist output", "ps output");
    }

    private static boolean looksLikeShellListingContext(CurrentTurnPlan plan, String answer) {
        String lowerRequest = lower(request(plan));
        String lowerAnswer = lower(answer);
        return containsAny(
                lowerRequest,
                "run ls", " ls",
                "run dir", " dir",
                "run cat", " cat ",
                "run type", " type ",
                "get-childitem", "get-content", "directory listing")
                || containsAny(
                lowerAnswer,
                "$ ls", "$ dir", "$ cat", "$ type",
                "ls output:", "dir output:", "cat output:", "type output:",
                "get-childitem output:", "get-content output:");
    }

    private static boolean looksLikeFileContentClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return FILE_CONTENT_CLAIM.matcher(answer).find();
    }

    private static boolean hasSuccessfulReadForClaimedFile(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        Set<String> claimedPaths = fileContentClaimPaths(answer);
        if (claimedPaths.isEmpty()) return false;
        Set<String> readPaths = successfulReadFilePaths(loopResult);
        for (String claimed : claimedPaths) {
            if (!readPaths.contains(canonicalPath(claimed))) return false;
        }
        return true;
    }

    private static Set<String> fileContentClaimPaths(String answer) {
        Set<String> out = new HashSet<>();
        if (answer == null || answer.isBlank()) return out;
        var matcher = FILE_CONTENT_CLAIM.matcher(answer);
        while (matcher.find()) {
            String path = canonicalPath(matcher.group(1));
            if (!path.isBlank()) out.add(path);
        }
        return out;
    }

    private static Set<String> successfulReadFilePaths(ToolCallLoop.LoopResult loopResult) {
        Set<String> paths = new HashSet<>();
        if (loopResult == null || loopResult.toolOutcomes() == null) return paths;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            String path = canonicalPath(outcome.pathHint());
            if (!path.isBlank()) paths.add(path);
        }
        return paths;
    }

    private static String request(CurrentTurnPlan plan) {
        return plan == null || plan.taskContract() == null
                ? ""
                : plan.taskContract().originalUserRequest();
    }

    private static boolean containsAny(String lower, String... needles) {
        if (lower == null || lower.isBlank() || needles == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle)) return true;
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String canonicalPath(String path) {
        if (path == null) return "";
        String out = path.replace('\\', '/').strip();
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.startsWith("./") && out.length() > 2) {
            out = out.substring(2);
        }
        return out;
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
