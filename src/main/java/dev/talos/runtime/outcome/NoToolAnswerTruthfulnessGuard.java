package dev.talos.runtime.outcome;

import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure final-answer guards for no-tool turns. */
public final class NoToolAnswerTruthfulnessGuard {
    private NoToolAnswerTruthfulnessGuard() {}

    public static final int UNGROUNDED_MIN_CHARS = 600;

    public static final String UNGROUNDED_ANNOTATION =
            "[Grounding check: the user asked for an answer based on workspace "
            + "contents, but no files were read this turn. The response below was "
            + "produced without reading any files.]\n\n";

    public static final String STREAMING_NO_TOOL_MUTATION_ANNOTATION =
            "[Truth check: the response below narrates completed file changes, "
            + "but no file tool was called in this turn. Treat it as unverified.]\n\n";

    public static final String STREAMING_NO_TOOL_MUTATION_REPLACEMENT =
            "[Truth check: no file was changed in this turn. The user asked for a "
            + "modification, but the assistant did not call any file-editing tool, so "
            + "the prior \"updated file\" narrative was discarded.]\n\n"
            + "No file changes were applied. Please retry with actual tool-backed edits.";

    public static final String MALFORMED_TOOL_PROTOCOL_REPLACEMENT =
            "[Truth check: the model produced an invalid tool-call payload, so no action was taken.]\n\n"
            + "No file changes were applied. Please retry the request.";

    public static final String LOCAL_ACCESS_CAPABILITY_CORRECTION =
            "[Capability correction: Talos can inspect files in the current workspace "
            + "with local read tools, but no file tool was called in this turn.]\n\n"
            + "I can read, list, and search files in this workspace when the task calls "
            + "for it. I did not inspect files in this turn, so I cannot give an "
            + "evidence-backed workspace answer yet.";

    private static final Set<String> EVIDENCE_REQUEST_MARKERS = Set.of(
            "read the",
            "read first",
            "inspect",
            "check whether",
            "check if",
            "check that",
            "verify",
            "evidence",
            "actual file",
            "based on the file",
            "from the file",
            "wired together",
            "wiring",
            "mismatch",
            "suspicious reference",
            "broken reference",
            "identify the"
    );

    private static final Set<String> NEGATIVE_LOCAL_ACCESS_MARKERS = Set.of(
            "don't have direct access to your local workspace",
            "do not have direct access to your local workspace",
            "don't have direct access to your local files",
            "do not have direct access to your local files",
            "can't browse your local files",
            "cannot browse your local files",
            "can't access your local files",
            "cannot access your local files",
            "can't inspect your local files",
            "cannot inspect your local files",
            "can't read your files",
            "cannot read your files",
            "if you provide the file contents",
            "if you provide specific details or content from the files"
    );

    private static final Set<String> LOCAL_WORKSPACE_TURN_MARKERS = Set.of(
            "workspace",
            "folder",
            "directory",
            "file",
            "files",
            "project",
            "repo",
            "repository",
            "here",
            "this"
    );

    private static final Set<String> STREAMING_MUTATION_NARRATIVE_MARKERS = Set.of(
            "updated `index.html`",
            "updated index.html",
            "updated `style.css`",
            "updated style.css",
            "updated `script.js`",
            "updated script.js",
            "here is the updated",
            "summary of changes",
            "summary of changes and verifications",
            "### updated `index.html`",
            "### updated `style.css`",
            "### updated `script.js`",
            "these changes should ensure",
            "these changes should align"
    );

    public static boolean looksLikeEvidenceRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        for (String marker : EVIDENCE_REQUEST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    public static String correctNegativeLocalAccessClaimIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (!shouldCorrectNegativeLocalAccessClaim(answer, plan, messages)) return answer;
        return LOCAL_ACCESS_CAPABILITY_CORRECTION;
    }

    public static boolean shouldCorrectNegativeLocalAccessClaim(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (!containsNegativeLocalAccessClaim(answer)) return false;
        return looksLikeLocalWorkspaceTurn(plan, messages, answer);
    }

    public static boolean containsNegativeLocalAccessClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String marker : NEGATIVE_LOCAL_ACCESS_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    public static boolean shouldAppendStreamingGroundingAnnotation(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (answer == null || answer.isBlank()) return false;
        if (answer.length() < UNGROUNDED_MIN_CHARS) return false;
        CurrentTurnPlan safePlan = safePlan(plan, messages);
        if (isDirectAnswerOnlyTurn(safePlan)) return false;
        return looksLikeEvidenceRequest(latestUserRequest(safePlan, messages));
    }

    public static String annotateStreamingNoToolMutationClaim(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (answer == null || answer.isBlank()) return answer;
        if (!safePlan(plan, messages).taskContract().mutationRequested()) return answer;
        if (!MutationFailureAnswerRenderer.containsMutationClaim(answer)
                && !containsStreamingMutationNarrative(answer)) return answer;
        return STREAMING_NO_TOOL_MUTATION_ANNOTATION + answer;
    }

    public static boolean containsStreamingMutationNarrative(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String marker : STREAMING_MUTATION_NARRATIVE_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    public static String enforceStreamingNoToolTruthfulness(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        String out = answer;
        if (shouldReplaceStreamingNoToolMutationNarrative(answer, plan, messages)) {
            return STREAMING_NO_TOOL_MUTATION_REPLACEMENT;
        }
        if (shouldAppendStreamingGroundingAnnotation(answer, plan, messages)) {
            out = UNGROUNDED_ANNOTATION + answer;
        }
        out = annotateStreamingNoToolMutationClaim(out, plan, messages);
        return out;
    }

    public static boolean shouldReplaceStreamingNoToolMutationNarrative(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        if (answer == null || answer.isBlank()) return false;
        if (!safePlan(plan, messages).taskContract().mutationRequested()) return false;
        return MutationFailureAnswerRenderer.containsMutationClaim(answer)
                || containsStreamingMutationNarrative(answer);
    }

    private static boolean looksLikeLocalWorkspaceTurn(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            String answer
    ) {
        CurrentTurnPlan safePlan = safePlan(plan, messages);
        TaskContract contract = safePlan.taskContract();
        if (contract.mutationRequested()) return false;

        TaskType type = contract.type();
        if (type == TaskType.DIRECTORY_LISTING
                || type == TaskType.WORKSPACE_EXPLAIN
                || type == TaskType.DIAGNOSE_ONLY
                || type == TaskType.VERIFY_ONLY) {
            return true;
        }

        String userRequest = latestUserRequest(safePlan, messages);
        if (containsLocalWorkspaceMarker(userRequest)) return true;
        return containsLocalWorkspaceMarker(answer) && type != TaskType.SMALL_TALK;
    }

    private static boolean containsLocalWorkspaceMarker(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : LOCAL_WORKSPACE_TURN_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static String latestUserRequest(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null
                && plan.originalUserRequest() != null
                && !plan.originalUserRequest().isBlank()) {
            return plan.originalUserRequest();
        }
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            return content == null || content.isBlank() ? null : content;
        }
        return null;
    }

    private static boolean isDirectAnswerOnlyTurn(CurrentTurnPlan plan) {
        if (plan == null) return false;
        return plan.actionObligation() == ActionObligation.DIRECT_ANSWER_ONLY
                || plan.taskContract().type() == TaskType.SMALL_TALK;
    }

    private static CurrentTurnPlan safePlan(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null) return plan;
        return CurrentTurnPlan.compatibility(
                TaskContract.unknown(latestUserRequest(null, messages)),
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
