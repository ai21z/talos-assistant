package dev.talos.runtime.task;

import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic resolver for Talos's minimal current-turn task contract. */
public final class TaskContractResolver {

    private static final Pattern TARGET_FILE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])([A-Za-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv))"
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");

    private static final Set<String> CREATE_MARKERS = Set.of(
            "create", "write a", "write the", "save as", "add a", "add the",
            "new file", "build", "generate", "scaffold", "set up", "setup",
            "make a", "make an"
    );

    private static final Set<String> DIAGNOSE_MARKERS = Set.of(
            "inspect", "diagnose", "check whether", "check if", "mismatch",
            "selector", "linkage", "wired", "wiring", "broken reference",
            "suspicious reference", "do not change", "broken", "what is wrong"
    );

    private static final Set<String> WORKSPACE_MARKERS = Set.of(
            "workspace", "repo", "repository", "project", "codebase", "what files",
            "what is in this", "explain this", "this folder", "this directory",
            "this site"
    );

    private static final Pattern SMALL_TALK_ONLY = Pattern.compile(
            "(?i)^\\s*(?:"
                    + "hi|hello|hey|hey there|hello there|yo|"
                    + "good\\s+(?:morning|afternoon|evening)|"
                    + "thanks|thank\\s+you|thx|"
                    + "ok|okay|cool|nice|great|"
                    + "hmm+|huh"
                    + ")[\\s.!?]*$");

    private static final Set<String> ASSISTANT_IDENTITY_MARKERS = Set.of(
            "who are you",
            "what are you",
            "what is talos",
            "who is talos",
            "what can you do",
            "how can you assist me",
            "how can you help me",
            "what can talos do",
            "tell me about yourself"
    );

    private static final Set<String> DEICTIC_FOLLOW_UPS = Set.of(
            "this here",
            "this folder",
            "this directory",
            "this one",
            "yes this",
            "yes, this",
            "yes check it",
            "here",
            "this"
    );

    private TaskContractResolver() {}

    public static TaskContract fromMessages(List<ChatMessage> messages) {
        String latest = latestUserRequest(messages);
        TaskContract current = fromUserRequest(latest);
        if (looksLikeDeicticFollowUp(latest) && !current.mutationRequested()) {
            TaskContract inherited = inheritedReadOnlyWorkspaceContract(messages, latest);
            if (inherited != null) return inherited;
        }
        return current;
    }

    public static TaskContract fromUserRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()
                || ToolCallSupport.isSyntheticToolResultContent(userRequest)) {
            return TaskContract.unknown(userRequest);
        }

        String original = userRequest.strip();
        String lower = original.toLowerCase(Locale.ROOT);
        boolean priorChangeStatusQuestion = MutationIntent.looksPriorChangeStatusQuestion(original);
        boolean mutationRequested = !priorChangeStatusQuestion
                && MutationIntent.looksExplicitMutationRequest(original);
        TaskType type = priorChangeStatusQuestion
                ? TaskType.VERIFY_ONLY
                : classify(lower, mutationRequested);
        boolean mutationAllowed = mutationRequested
                && (type == TaskType.FILE_EDIT || type == TaskType.FILE_CREATE);
        boolean verificationRequired = mutationAllowed || type == TaskType.VERIFY_ONLY;

        return new TaskContract(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                extractExpectedTargets(original),
                Set.of(),
                original);
    }

    public static Set<String> extractExpectedTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Matcher matcher = TARGET_FILE.matcher(userRequest);
        Set<String> out = new LinkedHashSet<>();
        while (matcher.find()) {
            String target = normalizeTarget(matcher.group(1));
            if (!target.isBlank()) out.add(target);
        }
        return Set.copyOf(out);
    }

    private static TaskType classify(String lower, boolean mutationRequested) {
        if (mutationRequested) {
            return containsAny(lower, CREATE_MARKERS) ? TaskType.FILE_CREATE : TaskType.FILE_EDIT;
        }
        if (lower.contains("verify") || lower.contains("confirm")) {
            return TaskType.VERIFY_ONLY;
        }
        if (containsAny(lower, DIAGNOSE_MARKERS)) {
            return TaskType.DIAGNOSE_ONLY;
        }
        if (containsAny(lower, WORKSPACE_MARKERS)) {
            return TaskType.WORKSPACE_EXPLAIN;
        }
        if (looksSmallTalkOnly(lower) || looksAssistantIdentityQuestion(lower)) {
            return TaskType.SMALL_TALK;
        }
        return TaskType.READ_ONLY_QA;
    }

    private static boolean looksSmallTalkOnly(String lower) {
        return lower != null && SMALL_TALK_ONLY.matcher(lower).matches();
    }

    private static boolean looksAssistantIdentityQuestion(String lower) {
        return lower != null && containsAny(lower, ASSISTANT_IDENTITY_MARKERS);
    }

    private static boolean looksLikeDeicticFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "");
        return DEICTIC_FOLLOW_UPS.contains(lower);
    }

    private static TaskContract inheritedReadOnlyWorkspaceContract(
            List<ChatMessage> messages,
            String latestUserRequest
    ) {
        String previous = previousUserRequest(messages, latestUserRequest);
        if (previous == null || previous.isBlank()) return null;
        TaskContract prior = fromUserRequest(previous);
        if (prior.mutationRequested()) return null;
        if (prior.type() != TaskType.WORKSPACE_EXPLAIN
                && prior.type() != TaskType.DIAGNOSE_ONLY
                && prior.type() != TaskType.VERIFY_ONLY) {
            return null;
        }
        return new TaskContract(
                prior.type(),
                false,
                false,
                prior.type() == TaskType.VERIFY_ONLY,
                Set.of(),
                Set.of(),
                latestUserRequest);
    }

    private static boolean containsAny(String lower, Set<String> markers) {
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static String latestUserRequest(List<ChatMessage> messages) {
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

    private static String previousUserRequest(List<ChatMessage> messages, String latestUserRequest) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedLatest = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            if (content == null || content.isBlank()) continue;
            if (!skippedLatest && Objects.equals(content, latestUserRequest)) {
                skippedLatest = true;
                continue;
            }
            return content;
        }
        return null;
    }

    private static String normalizeTarget(String raw) {
        if (raw == null) return "";
        String normalized = raw.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
