package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.WebDiagnosticIntent;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolAliasPolicy;

import java.util.List;
import java.util.Locale;

/** Selects deterministic terminal answers after read-only tool evidence is already gathered. */
public final class TerminalReadOnlyStopAnswer {
    private TerminalReadOnlyStopAnswer() {
    }

    record Answer(String text, String logMessage) {}

    public static String tryAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        Answer answer = select(state, outcome);
        return answer == null ? null : answer.text();
    }

    static Answer select(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        String webDiagnostics = readOnlyWebDiagnosticStopAnswer(state, outcome);
        if (webDiagnostics != null) {
            return new Answer(
                    webDiagnostics,
                    "Stopping read-only web diagnostic loop with deterministic static diagnostics.");
        }

        String unsupportedDocument = unsupportedDocumentStopAnswer(state, outcome);
        if (unsupportedDocument != null) {
            return new Answer(
                    unsupportedDocument,
                    "Stopping tool-call loop after unsupported binary document read.");
        }

        String directoryListing = directoryListingStopAnswer(state, outcome);
        if (directoryListing != null) {
            return new Answer(
                    directoryListing,
                    "Stopping directory-listing loop after successful list_dir evidence.");
        }

        String readTargetAnswer = readTargetStopAnswer(state, outcome);
        if (readTargetAnswer != null) {
            return new Answer(
                    readTargetAnswer,
                    "Stopping read-target loop after required read_file evidence.");
        }

        String multiTargetEvidenceComplete = multiTargetEvidenceCompleteStopAnswer(state, outcome);
        if (multiTargetEvidenceComplete != null) {
            return new Answer(
                    multiTargetEvidenceComplete,
                    "Stopping read-only multi-target loop after all requested files were read.");
        }

        return null;
    }

    private static String readTargetStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() != 1) return null;
        String target = contract.expectedTargets().iterator().next();
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        boolean targetRead = state.toolOutcomes.stream()
                .anyMatch(toolOutcome -> "talos.read_file".equals(canonicalToolName(toolOutcome.toolName()))
                        && toolOutcome.success()
                        && normalizedTarget.equals(ToolCallSupport.normalizePath(toolOutcome.pathHint())));
        if (!targetRead) {
            return missingReadTargetAnswer(state, target, normalizedTarget);
        }
        if (outcome.successesThisIteration() > 0 && outcome.failuresThisIteration() == 0) return null;
        String body = latestSuccessfulToolResultBodyByCanonical(state.messages, "talos.read_file");
        if (body == null || body.isBlank()) return null;
        return "Read " + target + ":\n" + body;
    }

    private static String missingReadTargetAnswer(
            LoopState state,
            String target,
            String normalizedTarget
    ) {
        if (state == null || normalizedTarget == null || normalizedTarget.isBlank()) return null;
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            var outcome = state.toolOutcomes.get(i);
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (outcome.success()) continue;
            if (!normalizedTarget.equals(ToolCallSupport.normalizePath(outcome.pathHint()))) continue;
            String message = outcome.errorMessage() == null ? "" : outcome.errorMessage().strip();
            if (message.isBlank()) {
                message = "read_file failed for " + target + ".";
            }
            String candidate = candidateSibling(normalizedTarget, message);
            return "Could not read " + target + ": " + message
                    + (candidate.isBlank() ? "" : "\nPossible intended sibling: " + candidate);
        }
        return null;
    }

    private static String candidateSibling(String normalizedTarget, String message) {
        if (normalizedTarget == null || normalizedTarget.isBlank()
                || message == null || message.isBlank()) {
            return "";
        }
        String lower = normalizedTarget.toLowerCase(Locale.ROOT);
        String candidate = switch (lower) {
            case "styles.css" -> "style.css";
            case "style.css" -> "styles.css";
            case "scripts.js" -> "script.js";
            case "script.js" -> "scripts.js";
            default -> "";
        };
        if (candidate.isBlank()) return "";
        return message.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))
                ? candidate
                : "";
    }

    private static String multiTargetEvidenceCompleteStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        if (outcome.successesThisIteration() > 0
                || outcome.failuresThisIteration() > 0
                || outcome.mutationsThisIteration() > 0) {
            return null;
        }
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() < 2) {
            return null;
        }
        List<String> targets = contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .filter(target -> target != null && !target.isBlank())
                .sorted()
                .toList();
        if (targets.size() < 2) return null;
        if (!targets.stream().allMatch(target -> state.pathsReadThisTurn.contains(target))) {
            return null;
        }

        StringBuilder out = new StringBuilder(
                "Read evidence complete, but no final synthesis was produced.\n"
                        + "Talos already read all requested files:");
        for (String target : targets) {
            out.append("\n- ").append(target);
        }
        out.append("\n\nThe model repeated read calls instead of producing a final answer. ")
                .append("No files were changed. Retry with a narrower question if you need a full synthesis.");
        return out.toString();
    }

    private static String directoryListingStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null || outcome.successesThisIteration() <= 0) return null;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.DIRECTORY_LISTING) return null;
        String body = DirectoryListingEvidence.selectedBody(
                state.messages,
                state.toolOutcomes,
                contract.originalUserRequest());
        if (body == null || body.isBlank()) return null;
        return renderDirectoryEntries(body);
    }

    private static String renderDirectoryEntries(String toolBody) {
        if (toolBody == null || toolBody.isBlank()) return null;
        String[] lines = toolBody.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder out = new StringBuilder("Directory entries:");
        boolean added = false;
        for (String line : lines) {
            String entry = line == null ? "" : line.strip();
            if (entry.isBlank()) continue;
            out.append("\n- ").append(entry);
            added = true;
        }
        return added ? out.toString() : null;
    }

    private static String latestSuccessfulToolResultBodyByCanonical(List<ChatMessage> messages, String canonicalToolName) {
        if (messages == null || messages.isEmpty() || canonicalToolName == null || canonicalToolName.isBlank()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            int prefixStart = content.indexOf("[tool_result:");
            if (prefixStart < 0) continue;
            int prefixEnd = content.indexOf(']', prefixStart);
            if (prefixEnd < 0) continue;
            String rawToolName = content.substring(prefixStart + "[tool_result:".length(), prefixEnd).strip();
            if (!canonicalToolName.equals(canonicalToolName(rawToolName))) continue;
            String body = content.substring(prefixEnd + 1).strip();
            int end = body.indexOf("[/tool_result]");
            if (end >= 0) {
                body = body.substring(0, end).strip();
            }
            if (body.startsWith("[error]")) continue;
            if (body.contains("You already gathered this information")) continue;
            return body;
        }
        return null;
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static String unsupportedDocumentStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (outcome == null) return null;
        if (outcome.successesThisIteration() > 0 || outcome.mutationsThisIteration() > 0) return null;
        List<String> unsupportedPaths = outcome.unsupportedReadPathsThisIteration();
        if (unsupportedPaths == null || unsupportedPaths.isEmpty()) return null;
        if (userNamedConvertedFallback(state, unsupportedPaths)) return null;
        return "[Document capability note: Talos could not inspect unsupported binary document contents with "
                + "the current local text-tool surface: "
                + String.join(", ", unsupportedPaths)
                + ". It cannot confirm whether those files are empty or what they contain.]";
    }

    private static boolean userNamedConvertedFallback(LoopState state, List<String> unsupportedPaths) {
        if (state == null || unsupportedPaths == null || unsupportedPaths.isEmpty()) return false;
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(Locale.ROOT);
        for (String path : unsupportedPaths) {
            String stem = filenameStem(path);
            if (stem.isBlank()) continue;
            if (lower.contains(stem + ".txt") || lower.contains("extracted_" + stem + ".txt")) {
                return true;
            }
        }
        return false;
    }

    private static String filenameStem(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static String readOnlyWebDiagnosticStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        if (state.workspace == null) return null;
        if (state.totalToolsInvoked <= 0) return null;
        if (state.mutatingToolSuccesses > 0 || outcome.mutationsThisIteration() > 0) return null;

        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        String retryTaskType = ToolCallSupport.embeddedRetryTaskType(userTask);
        if ("WORKSPACE_EXPLAIN".equals(retryTaskType)) return null;
        if (declaresTaskType(state.messages, "WORKSPACE_EXPLAIN")) return null;
        String intentUserTask = ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt(userTask);
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(intentUserTask)) return null;
        if (!readStaticWebDiagnosticSurface(state)) return null;

        String diagnostics = StaticTaskVerifier.renderWebDiagnostics(state.workspace);
        return diagnostics == null || diagnostics.isBlank() ? null : diagnostics;
    }

    private static boolean readStaticWebDiagnosticSurface(LoopState state) {
        if (state == null || state.pathsReadThisTurn == null || state.pathsReadThisTurn.isEmpty()) return false;
        boolean readHtml = false;
        boolean readScript = false;
        for (String path : state.pathsReadThisTurn) {
            String lower = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                readHtml = true;
            }
            if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                readScript = true;
            }
        }
        return readHtml && readScript;
    }

    private static boolean declaresTaskType(List<ChatMessage> messages, String taskType) {
        if (messages == null || taskType == null || taskType.isBlank()) return false;
        String marker = "Task type: " + taskType;
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null) continue;
            if (message.content().contains(marker)) return true;
        }
        return false;
    }
}
