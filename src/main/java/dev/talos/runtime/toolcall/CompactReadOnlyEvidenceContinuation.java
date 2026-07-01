package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.tools.ToolAliasPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Compact answer synthesis for read-only evidence turns after a context-budget overflow. */
final class CompactReadOnlyEvidenceContinuation {
    private CompactReadOnlyEvidenceContinuation() {}

    static boolean tryAnswer(LoopState state, String retryName) {
        Optional<ReadOnlyEvidenceAnswer> evidence = answerFor(state);
        if (evidence.isEmpty()) return false;
        ReadOnlyEvidenceAnswer answer = evidence.get();
        List<ChatMessage> messages = answerMessages(answer);
        try {
            LlmClient.StreamResult result = state.ctx.llm().chatFull(
                    messages,
                    List.of(),
                    ChatRequestControls.defaults());
            String text = result.text() == null ? "" : result.text().strip();
            if (result.hasToolCalls() || ToolCallParser.containsToolCalls(text)) {
                LocalTurnTraceCapture.warning(
                        "READ_ONLY_EVIDENCE_COMPACT_REJECTED",
                        "compact read-only evidence continuation emitted tool calls after " + retryName);
                return false;
            }
            String stripped = ToolCallParser.stripToolCalls(text).strip();
            if (stripped.isBlank()) {
                LocalTurnTraceCapture.warning(
                        "READ_ONLY_EVIDENCE_COMPACT_REJECTED",
                        "compact read-only evidence continuation returned empty text after " + retryName);
                return false;
            }
            state.currentText = stripped;
            state.currentNativeCalls = List.of();
            state.failureDecision = FailureDecision.continueLoop();
            state.clearPendingActionObligation();
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_CONTINUATION",
                    "used compact evidence-only answer for " + answer.target() + " after " + retryName);
            return true;
        } catch (EngineException.ContextBudgetExceeded budget) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_CONTEXT_BUDGET_EXCEEDED",
                    ResponseObligationVerifier.contextBudgetRetrySkippedDetail(budget));
            return false;
        } catch (EngineException ee) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_FAILED",
                    ee.getMessage() == null ? ee.getClass().getSimpleName() : ee.getMessage());
            return false;
        } catch (Exception e) {
            LocalTurnTraceCapture.warning(
                    "READ_ONLY_EVIDENCE_COMPACT_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private record ReadOnlyEvidenceAnswer(String target, String userTask, String readback) {}

    private static Optional<ReadOnlyEvidenceAnswer> answerFor(LoopState state) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) return Optional.empty();
        if (state.hasPendingActionObligation()) return Optional.empty();
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return Optional.empty();
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() != 1) {
            return Optional.empty();
        }
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (!looksLikeReadOnlyReviewProposal(userTask)) return Optional.empty();
        String target = contract.expectedTargets().iterator().next();
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        if (!successfulReadbackForPath(state, normalizedTarget)) return Optional.empty();
        String body = latestSuccessfulReadbackForPath(state, normalizedTarget);
        if (body == null || body.isBlank()) return Optional.empty();
        return Optional.of(new ReadOnlyEvidenceAnswer(normalizedTarget, userTask.strip(), body));
    }

    private static boolean looksLikeReadOnlyReviewProposal(String userTask) {
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(Locale.ROOT);
        boolean reviewProposal = lower.contains("review")
                || lower.contains("propose")
                || lower.contains("proposal")
                || lower.contains("improvement")
                || lower.contains("suggest");
        boolean markdownTarget = lower.contains("readme") || lower.contains(".md");
        boolean explicitlyReadOnly = lower.contains("do not edit")
                || lower.contains("don't edit")
                || lower.contains("dont edit")
                || lower.contains("do not change")
                || lower.contains("without editing")
                || lower.contains("no file changes");
        return reviewProposal && markdownTarget && explicitlyReadOnly;
    }

    private static boolean successfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) return false;
        String targetKey = normalizeExpectedTargetKey(normalizedPath);
        if (targetKey.isBlank()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (targetKey.equals(normalizeExpectedTargetKey(outcome.pathHint()))) {
                return true;
            }
        }
        return false;
    }

    private static String latestSuccessfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        String target = ToolCallSupport.canonicalizeReadPath(normalizedPath)
                .toLowerCase(Locale.ROOT);
        String fullBody = latestSuccessfulReadbackForPath(state.successfulReadCallBodies, target);
        if (fullBody != null) return fullBody;
        return latestSuccessfulReadbackForPath(state.successfulReadCalls, target);
    }

    private static String latestSuccessfulReadbackForPath(Map<String, String> readbacksBySignature, String target) {
        if (readbacksBySignature == null || readbacksBySignature.isEmpty()
                || target == null || target.isBlank()) {
            return null;
        }
        for (var entry : readbacksBySignature.entrySet()) {
            String signature = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (signature.startsWith("talos.read_file:")
                    && signature.contains("path=" + target + ";")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static List<ChatMessage> answerMessages(ReadOnlyEvidenceAnswer answer) {
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        [ReadOnlyEvidenceAnswer]
                        This is a compact evidence-only continuation after the full-history continuation exceeded the local context budget.
                        Answer the current user request using only the read_file evidence below.
                        Do not claim any file was changed, edited, updated, saved, completed, or ready to use.
                        For review/proposal output, separate observed evidence from suggestions.
                        Do not state commands, dependencies, package managers, frameworks, scripts, licenses, or file meanings as facts unless they appear in the read_file evidence.
                        """),
                ChatMessage.system("[ReadOnlyEvidenceAnswer] Target: " + answer.target()
                        + "\nOlder conversation history is intentionally omitted from this compact frame."),
                ChatMessage.user(
                        "Current user request:\n"
                                + answer.userTask()
                                + "\n\nCurrent read_file evidence for " + answer.target() + ":\n"
                                + answer.readback()
                                + "\n\nAnswer now without tools."));
    }
}
