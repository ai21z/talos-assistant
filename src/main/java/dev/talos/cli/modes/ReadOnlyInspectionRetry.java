package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.ToolCallParser;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ReadOnlyInspectionRetry {
    private static final Logger LOG = LoggerFactory.getLogger(ReadOnlyInspectionRetry.class);

    private ReadOnlyInspectionRetry() {}

    @FunctionalInterface
    interface ChatFunction {
        LlmClient.StreamResult chat(List<ChatMessage> messages) throws Exception;
    }

    record Result(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            String extraSummary
    ) {}

    static Result retryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx,
            ChatFunction chat
    ) {
        if (answer == null) answer = "";
        TaskContract contract = plan == null ? null : plan.taskContract();
        if (!requiresWorkspaceEvidence(contract)) {
            return new Result(answer, null, null);
        }
        if (contract.mutationRequested()) {
            return new Result(answer, null, null);
        }
        if (ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null || workspace == null || chat == null) {
            return new Result(answer, null, null);
        }

        String userRequest = plan.originalUserRequest();
        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        retryMessages.add(ChatMessage.assistant(answer.isBlank() ? "(no answer)" : answer));
        retryMessages.add(ChatMessage.user(retryPrompt(contract, userRequest, workspace)));

        try {
            LlmClient.StreamResult retry = chat.chat(retryMessages);
            String retryText = retry.text() == null ? "" : retry.text();
            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), retryMessages, workspace, ctx);
                String mergedAnswer = retryLoop.finalAnswer();
                return new Result(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop,
                        retryLoop.summary());
            }
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                return new Result(ToolCallParser.stripToolCalls(retryText), null, null);
            }
        } catch (Exception e) {
            LOG.warn("Read-only inspection retry failed: {}", SafeLogFormatter.throwableMessage(e));
        }
        return new Result(answer, null, null);
    }

    static String retryPrompt(
            TaskContract contract,
            String userRequest,
            Path workspace
    ) {
        String type = contract == null ? "READ_ONLY_QA" : contract.type().name();
        String request = userRequest == null ? "" : userRequest.strip();
        if (request.length() > 1000) {
            request = request.substring(0, 1000) + "...";
        }
        String primaryFiles = String.join(", ", StaticTaskVerifier.obviousPrimaryFiles(workspace));
        if (primaryFiles.isBlank()) {
            primaryFiles = "any obvious primary text files";
        }
        if (contract != null && contract.type() == TaskType.DIRECTORY_LISTING) {
            return """
                The previous answer did not inspect the local workspace, but the current task asks only for directory entries.

                Task type: DIRECTORY_LISTING
                User request: "%s"

                Use talos.list_dir on "." unless the user named another in-workspace directory. Do not inspect, search, retrieve, summarize, infer, write, or edit file contents. Answer with file and directory names only.""".formatted(request);
        }
        if (contract != null
                && contract.type() == TaskType.VERIFY_ONLY
                && "explicit-command-verification-request".equals(contract.classificationReason())) {
            return """
                The previous answer did not run the requested bounded command verification.

                Task type: VERIFY_ONLY
                User request: "%s"

                Use talos.run_command now with the requested approved command profile. Do not call file-inspection, search, retrieval, write, or edit tools on this retry. If the runtime rejects the command profile or no approved profile matches, report that verified command-tool result directly and do not claim the command passed.""".formatted(request);
        }
        return """
                The previous answer did not inspect the local workspace, but the current task contract requires evidence.

                Task type: %s
                User request: "%s"

                Use read-only tools now. Start with talos.list_dir on "." for "this folder", "here", or "this workspace". Then read the obvious primary files if present: %s. Answer from observed file evidence only. If there are no readable relevant files, say that directly. Do not call write_file or edit_file.""".formatted(type, request, primaryFiles);
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
}
