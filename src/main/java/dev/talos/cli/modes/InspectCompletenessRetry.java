package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class InspectCompletenessRetry {
    private static final Logger LOG = LoggerFactory.getLogger(InspectCompletenessRetry.class);

    private InspectCompletenessRetry() {}

    @FunctionalInterface
    interface ChatFunction {
        LlmClient.StreamResult chat(List<ChatMessage> messages) throws Exception;
    }

    record Result(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            String extraSummary
    ) {}

    static List<String> missingReads(Path workspace, ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null) return List.of();
        LinkedHashSet<String> missing = new LinkedHashSet<>(missingPrimaryReads(workspace, loopResult));
        for (String target : EvidenceObligationVerifier.missingLinkedScriptReadTargets(
                workspace, loopResult.toolOutcomes())) {
            if (target == null || target.isBlank()) continue;
            if (ProtectedPathPolicy.classify(workspace, target).protectedPath()) continue;
            String normalized = ToolCallSupport.normalizePath(target);
            if (!normalized.isBlank()) missing.add(normalized);
        }
        return List.copyOf(missing);
    }

    static Result retryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            ChatFunction chat
    ) {
        if (answer == null) answer = "";
        if (loopResult == null || ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null || chat == null) {
            return new Result(answer, null, null);
        }
        String userRequest = plan == null ? "" : plan.originalUserRequest();
        TaskContract contract = plan == null ? null : plan.taskContract();
        if (contract != null && contract.type() == TaskType.DIRECTORY_LISTING) {
            return new Result(answer, null, null);
        }
        if (!InspectUnderCompletionAnswerGuard.looksLikeInspectFirstRequest(userRequest)
                && !requiresWorkspaceEvidence(contract)) {
            return new Result(answer, null, null);
        }
        List<String> missing = missingReads(workspace, loopResult);
        if (missing.isEmpty()) return new Result(answer, null, null);
        if (loopResult.mutatingToolSuccesses() > 0) return new Result(answer, null, null);
        if (answer.isBlank()) return new Result(answer, null, null);

        LOG.info("Inspect-completeness retry fired: tiny workspace, inspect-first request, "
                + "missing reads for {}", missing);

        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        retryMessages.add(ChatMessage.assistant(answer));
        retryMessages.add(ChatMessage.user(retryPrompt(contract, userRequest, missing)));
        try {
            LlmClient.StreamResult retry = chat.chat(retryMessages);
            String retryText = retry.text() == null ? "" : retry.text();
            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), retryMessages, workspace, ctx);
                ToolCallLoop.LoopResult groundedRetryLoop = mergeReadOnlyRetryEvidence(loopResult, retryLoop);
                String mergedAnswer = retryLoop.finalAnswer();
                return new Result(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        groundedRetryLoop,
                        groundedRetryLoop == null ? retryLoop.summary() : groundedRetryLoop.summary());
            }
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                return new Result(retryText, null, null);
            }
        } catch (Exception e) {
            LOG.warn("Inspect-completeness retry failed: {}", SafeLogFormatter.throwableMessage(e));
        }
        return new Result(answer, null, null);
    }

    static ToolCallLoop.LoopResult mergeReadOnlyRetryEvidence(
            ToolCallLoop.LoopResult original,
            ToolCallLoop.LoopResult retry
    ) {
        if (retry == null) return null;
        if (original == null) return retry;
        if (original.mutatingToolSuccesses() > 0 || retry.mutatingToolSuccesses() > 0) return retry;

        List<String> mergedReadPaths = mergeReadPaths(original.readPaths(), retry.readPaths());
        List<String> mergedToolNames = new ArrayList<>();
        if (original.toolNames() != null) mergedToolNames.addAll(original.toolNames());
        if (retry.toolNames() != null) mergedToolNames.addAll(retry.toolNames());
        List<ToolCallLoop.ToolOutcome> mergedOutcomes = new ArrayList<>();
        if (original.toolOutcomes() != null) mergedOutcomes.addAll(original.toolOutcomes());
        if (retry.toolOutcomes() != null) mergedOutcomes.addAll(retry.toolOutcomes());

        return new ToolCallLoop.LoopResult(
                retry.finalAnswer(),
                original.iterations() + retry.iterations(),
                original.toolsInvoked() + retry.toolsInvoked(),
                mergedToolNames,
                retry.messages(),
                original.failedCalls() + retry.failedCalls(),
                original.retriedCalls() + retry.retriedCalls(),
                original.hitIterLimit() || retry.hitIterLimit(),
                retry.mutatingToolSuccesses(),
                mergedReadPaths,
                original.cushionFiresRedundantRead() + retry.cushionFiresRedundantRead(),
                original.cushionFiresAliasRescue() + retry.cushionFiresAliasRescue(),
                original.cushionFiresB3EditShortCircuit() + retry.cushionFiresB3EditShortCircuit(),
                original.cushionFiresE1Suggestion() + retry.cushionFiresE1Suggestion(),
                retry.failureDecision(),
                mergedOutcomes);
    }

    private static List<String> missingPrimaryReads(Path workspace, ToolCallLoop.LoopResult loopResult) {
        return loopResult == null
                ? List.of()
                : StaticTaskVerifier.missingPrimaryReads(workspace, loopResult.readPaths());
    }

    private static String retryPrompt(TaskContract contract, String userRequest, List<String> missing) {
        String request = userRequest == null ? "" : userRequest.strip();
        return """
                You started diagnosing the workspace before reading all of the obvious primary files.

                Task type: %s
                User request: "%s"

                Read these files now before answering: %s. After reading them, answer concretely from the file contents. Do not speculate about files that do not exist.""".formatted(
                contract == null ? TaskType.READ_ONLY_QA.name() : contract.type().name(),
                request,
                String.join(", ", missing));
    }

    private static boolean requiresWorkspaceEvidence(TaskContract taskContract) {
        if (taskContract == null) return false;
        return switch (taskContract.type()) {
            case DIRECTORY_LISTING, WORKSPACE_EXPLAIN, VERIFY_ONLY -> true;
            case DIAGNOSE_ONLY -> NoToolAnswerTruthfulnessGuard.looksLikeEvidenceRequest(
                    taskContract.originalUserRequest())
                    || containsWorkspaceEvidenceAnchor(taskContract.originalUserRequest());
            default -> false;
        };
    }

    private static boolean containsWorkspaceEvidenceAnchor(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("workspace")
                || lower.contains("folder")
                || lower.contains("directory")
                || lower.contains("project")
                || lower.contains("repo")
                || lower.contains("repository")
                || lower.contains("here")
                || lower.contains("this")
                || lower.contains("website")
                || lower.contains("web page")
                || lower.contains("webpage")
                || lower.contains("site")
                || lower.contains("html")
                || lower.contains("css")
                || lower.contains("javascript")
                || lower.contains("script");
    }

    private static boolean hasAnyTextToolCalls(String answer) {
        return !ToolCallParser.looksLikeMalformedToolProtocol(answer)
                && ToolCallParser.containsToolCalls(answer);
    }

    private static List<String> mergeReadPaths(List<String> original, List<String> retry) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addNormalizedReadPaths(merged, original);
        addNormalizedReadPaths(merged, retry);
        return List.copyOf(merged);
    }

    private static void addNormalizedReadPaths(Set<String> merged, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        for (String path : paths) {
            String normalized = ToolCallSupport.normalizePath(path);
            if (!normalized.isBlank()) {
                merged.add(normalized);
            }
        }
    }
}
