package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolAliasPolicy;

import java.util.List;
import java.util.Locale;

/** Selects deterministic terminal answers after read-only tool evidence is already gathered. */
public final class TerminalReadOnlyStopAnswer {
    public static final String SYNTHESIS_UNAVAILABLE_REASON = "READ_ONLY_SYNTHESIS_UNAVAILABLE";
    public static final String RETENTION_LIMIT_REASON = "READ_ONLY_EVIDENCE_RETENTION_LIMIT";
    public static final String INSPECTION_UNAVAILABLE_REASON = "READ_ONLY_INSPECTION_UNAVAILABLE";
    public static final String INSPECTION_REQUEST_LIMIT_REASON = "READ_ONLY_INSPECTION_REQUEST_LIMIT";
    public static final String ACQUISITION_LIMIT_REASON = "READ_ONLY_ACQUISITION_LIMIT";
    public static final String DELIVERY_UNAVAILABLE_REASON = "READ_EVIDENCE_DELIVERY_UNAVAILABLE";
    private TerminalReadOnlyStopAnswer() {
    }

    record Answer(String text, String logMessage, boolean synthesisIncomplete) {
        Answer(String text, String logMessage) {
            this(text, logMessage, false);
        }
    }

    public static boolean synthesisUnavailable(dev.talos.runtime.ToolCallLoop.LoopResult result) {
        return result != null && result.failureDecision().action() == dev.talos.runtime.failure.FailureAction.ASK_USER
                && SYNTHESIS_UNAVAILABLE_REASON.equals(result.failureDecision().reason());
    }

    public static boolean retentionLimited(dev.talos.runtime.ToolCallLoop.LoopResult result) {
        return result != null && result.failureDecision().action() == dev.talos.runtime.failure.FailureAction.ASK_USER
                && RETENTION_LIMIT_REASON.equals(result.failureDecision().reason());
    }

    /** Only runtime-owned read-only stops establish this limitation, never answer wording. */
    public static String limitationReason(dev.talos.runtime.ToolCallLoop.LoopResult result) {
        if (result == null || result.failureDecision().action() != dev.talos.runtime.failure.FailureAction.ASK_USER)
            return "";
        return switch (result.failureDecision().reason()) {
            case SYNTHESIS_UNAVAILABLE_REASON, RETENTION_LIMIT_REASON, INSPECTION_UNAVAILABLE_REASON,
                    INSPECTION_REQUEST_LIMIT_REASON, ACQUISITION_LIMIT_REASON,
                    DELIVERY_UNAVAILABLE_REASON -> result.failureDecision().reason();
            default -> "";
        };
    }

    static String retentionLimitAnswer() {
        return "The acquired source observations exceed the bounded answer context. "
                + "A complete diagnosis cannot be established without discarding evidence. "
                + "No further tools were executed and no files were changed. The diagnosis remains incomplete.";
    }

    /** A rejected answer generation is distinguished from failed read evidence. */
    public static boolean rejectedProposalSynthesis(dev.talos.runtime.ToolCallLoop.LoopResult result) {
        return synthesisUnavailable(result)
                && result.readOnlySynthesisStatus() == dev.talos.runtime.ToolCallLoop.ReadOnlySynthesisStatus.REJECTED
                && result.readOnlyFinalizationEvidence().proposalSelected()
                && result.failedCalls() == 0 && result.mutatingToolSuccesses() == 0
                && !result.toolOutcomes().isEmpty()
                && result.toolOutcomes().stream().noneMatch(outcome -> outcome.denied() || !outcome.success()
                    || outcome.mutating() || outcome.mutationEffects().hasEffects());
    }

    public static String synthesisUnavailableAnswer() {
        return ReadOnlyEvidenceSynthesizer.UNAVAILABLE;
    }

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
        // Reading a web surface proves evidence availability, not completion of
        // the requested explanation. Let the provider synthesize; finalization
        // still contains unsupported claims using static diagnostics.

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
                    "Stopping read-only multi-target loop without a final synthesis.", true);
        }

        return null;
    }

    private static String readTargetStopAnswer(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return null;
        TaskContract contract =
                TurnTaskContractCapture.currentOrResolve(state.messages);
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
        TaskContract contract =
                TurnTaskContractCapture.currentOrResolve(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() < 2) {
            return null;
        }
        List<String> targets = contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .filter(target -> !target.isBlank())
                .sorted()
                .toList();
        if (targets.size() < 2) return null;
        if (!state.pathsReadThisTurn.containsAll(targets)) {
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
        TaskContract contract =
                TurnTaskContractCapture.currentOrResolve(state.messages);
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
        String userTask =
                TurnTaskContractCapture.originalUserRequestOrResolve(
                        state.messages);
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

}
