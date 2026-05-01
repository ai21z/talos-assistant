package dev.talos.runtime.task;

import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.policy.CapabilityAnswerPolicy;
import dev.talos.runtime.policy.ConversationBoundaryPolicy;
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
            "(?i)(?<![A-Za-z0-9_./\\\\-])((?:[A-Za-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv|pdf|doc|docx|xls|xlsx|ppt|pptx)"
                    + ")|(?:(?:[A-Za-z0-9_.\\\\/-]+/)?\\.env(?:\\.[A-Za-z0-9_.-]+)?))"
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");

    private static final Pattern NEGATED_TARGET_SPAN = Pattern.compile(
            "(?i)(?:\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:change|edit|modify|write|create|save|apply|touch|mutate)"
                    + "|\\bwithout\\s+changing)\\s+(.{0,240})");

    private static final Pattern EXTENSIONLESS_TEXT_TARGET = Pattern.compile(
            "(?i)\\b(?:edit|overwrite|replace|update|write|create|set)\\s+`?"
                    + "((?:[A-Za-z0-9_.\\\\/-]+/)?"
                    + "(?:README|LICENSE|NOTICE|CHANGELOG|CONTRIBUTING|AUTHORS|Makefile|Dockerfile))"
                    + "`?(?=$|\\s|[`'\"),;:!?\\]])");

    private static final Pattern NEGATED_READ_TARGET_SPAN = Pattern.compile(
            "(?i)(?:\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:show|display|include|read|inspect|open|summarize)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?(?:content|contents)?\\s*(?:from|of|in)?"
                    + "|\\bwithout\\s+"
                    + "(?:showing|displaying|including|reading|inspecting|opening|summarizing)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?(?:content|contents)?\\s*(?:from|of|in)?)"
                    + "\\s+(.{0,240})");

    private static final Pattern NEGATED_TARGET_PREFERENCE_SPAN = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don't|dont)\\s+(?:want|need)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?(.{0,160})");

    private static final Set<String> CREATE_MARKERS = Set.of(
            "create", "write a", "write the", "save as", "add a", "add the",
            "new file", "build", "generate", "scaffold", "set up", "setup",
            "make a", "make an", "make me"
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

    private static final Pattern SIMPLE_DIRECTORY_LISTING = Pattern.compile(
            "(?i)^\\s*(?:"
                    + "(?:what|which)\\s+(?:files|folders|directories|items|entries)\\s+"
                    + "(?:are|exist|do\\s+we\\s+have)?\\s*(?:in|inside)?\\s*"
                    + "(?:this|the|current|here)?\\s*(?:folder|directory|workspace|repo|repository)?"
                    + "|list\\s+(?:the\\s+)?(?:files|folders|directories|items|entries)\\s*"
                    + "(?:here|in\\s+(?:this|the|current)\\s+(?:folder|directory|workspace|repo|repository))?"
                    + "|show\\s+me\\s+(?:the\\s+)?(?:files|folders|directories|items|entries)\\s*"
                    + "(?:here|in\\s+(?:this|the|current)\\s+(?:folder|directory|workspace|repo|repository))?"
                    + ")[\\s.!?]*$");

    private static final Set<String> SIMPLE_LISTING_EXCLUSION_MARKERS = Set.of(
            "read", "explain", "summarize", "summary", "inspect", "diagnose",
            "search", "grep", "find ", "content", "contents", "inside the files",
            "what does", "what is this project", "what is this folder for"
    );

    private static final Set<String> DIRECTORY_LIST_ONLY_MARKERS = Set.of(
            "list files only",
            "list the files only",
            "only list files",
            "only list the files",
            "files only",
            "file names only",
            "names only"
    );

    private static final Set<String> NEGATIVE_CONTENT_MARKERS = Set.of(
            "do not show content",
            "don't show content",
            "dont show content",
            "do not display content",
            "don't display content",
            "dont display content",
            "do not read content",
            "don't read content",
            "dont read content",
            "do not read files",
            "don't read files",
            "dont read files",
            "do not inspect files",
            "don't inspect files",
            "dont inspect files",
            "without showing content",
            "without displaying content",
            "without reading content",
            "without reading files",
            "without inspecting files",
            "no content"
    );

    private static final Set<String> NO_INSPECTION_MARKERS = Set.of(
            "without inspecting the workspace",
            "without inspecting workspace",
            "without checking the workspace",
            "without checking workspace",
            "without reading the workspace",
            "without reading workspace",
            "without inspecting the repo",
            "without inspecting repo",
            "without checking the repo",
            "without checking repo",
            "without reading the repo",
            "without reading repo",
            "without inspecting the repository",
            "without checking the repository",
            "without reading the repository",
            "without inspecting the codebase",
            "without checking the codebase",
            "without reading the codebase"
    );

    private static final Set<String> NO_INSPECTION_DIRECT_ANSWER_MARKERS = Set.of(
            "how you would approach",
            "how would you approach",
            "approach reviewing",
            "approach review",
            "reviewing a",
            "methodology",
            "general approach"
    );

    private static final Set<String> CHAT_ONLY_HINTS = Set.of(
            "answer briefly",
            "just say hello",
            "just say hi",
            "say hello",
            "say hi",
            "are you awake",
            "normal assistant",
            "friendly sentence"
    );

    private static final Pattern SMALL_TALK_ONLY = Pattern.compile(
            "(?i)^\\s*(?:"
                    + "hi|hello|hey|hey there|hello there|yo|"
                    + "good\\s+(?:morning|afternoon|evening)|"
                    + "thanks|thank\\s+you|thx|"
                    + "ok|okay|cool|nice|great|"
                    + "hmm+|huh"
                    + ")[\\s.!?]*$");

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
        if (current.type() == TaskType.VERIFY_ONLY
                || MutationIntent.looksPriorChangeStatusQuestion(latest)) {
            return current;
        }
        if (looksLikeRepairFollowUp(latest)) {
            TaskContract inherited = inheritedRepairContract(messages, latest, current);
            if (inherited != null) return inherited;
        }
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
        Set<String> forbiddenTargets = extractForbiddenTargets(original);
        Set<String> expectedTargets = extractExpectedTargets(original);
        if (mutationAllowed && !forbiddenTargets.isEmpty()) {
            expectedTargets = withoutForbiddenTargets(expectedTargets, forbiddenTargets);
        }
        Set<String> readForbiddenTargets = extractReadForbiddenTargets(original);
        if (!readForbiddenTargets.isEmpty()) {
            expectedTargets = withoutForbiddenTargets(expectedTargets, readForbiddenTargets);
        }

        return new TaskContract(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
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
        Matcher extensionlessMatcher = EXTENSIONLESS_TEXT_TARGET.matcher(userRequest);
        while (extensionlessMatcher.find()) {
            String target = normalizeTarget(extensionlessMatcher.group(1));
            if (!target.isBlank()) out.add(target);
        }
        return Set.copyOf(out);
    }

    public static Set<String> extractForbiddenTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Matcher spanMatcher = NEGATED_TARGET_SPAN.matcher(userRequest);
        Set<String> out = new LinkedHashSet<>();
        while (spanMatcher.find()) {
            String span = firstSentenceFragment(spanMatcher.group(1));
            Matcher targetMatcher = TARGET_FILE.matcher(span);
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> extractReadForbiddenTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher spanMatcher = NEGATED_READ_TARGET_SPAN.matcher(userRequest);
        while (spanMatcher.find()) {
            String span = firstSentenceFragment(spanMatcher.group(1));
            Matcher targetMatcher = TARGET_FILE.matcher(span);
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
        Matcher preferenceMatcher = NEGATED_TARGET_PREFERENCE_SPAN.matcher(userRequest);
        while (preferenceMatcher.find()) {
            String span = targetCorrectionFragment(preferenceMatcher.group(1));
            String target = firstTargetIn(span);
            if (!target.isBlank()) out.add(target);
        }
        return Set.copyOf(out);
    }

    private static String firstTargetIn(String span) {
        if (span == null || span.isBlank()) return "";
        Matcher targetMatcher = TARGET_FILE.matcher(span);
        if (targetMatcher.find()) {
            return normalizeTarget(targetMatcher.group(1));
        }
        Matcher extensionlessMatcher = EXTENSIONLESS_TEXT_TARGET.matcher(span);
        if (extensionlessMatcher.find()) {
            return normalizeTarget(extensionlessMatcher.group(1));
        }
        return "";
    }

    private static String targetCorrectionFragment(String span) {
        String fragment = firstSentenceFragment(span);
        String lower = fragment.toLowerCase(Locale.ROOT);
        int end = fragment.length();
        for (String marker : List.of(", i want", ", but", " but ", " instead", " rather", ";")) {
            int index = lower.indexOf(marker);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return fragment.substring(0, end);
    }

    private static TaskType classify(String lower, boolean mutationRequested) {
        if (mutationRequested) {
            return containsAny(lower, CREATE_MARKERS) ? TaskType.FILE_CREATE : TaskType.FILE_EDIT;
        }
        if (looksExplicitNoInspectionDirectAnswer(lower)) {
            return TaskType.SMALL_TALK;
        }
        if (ConversationBoundaryPolicy.isDirectAnswerOnly(lower)
                || looksConversationalGreetingRequest(lower)
                || looksAssistantIdentityQuestion(lower)) {
            return TaskType.SMALL_TALK;
        }
        if (looksSimpleDirectoryListingRequest(lower)) {
            return TaskType.DIRECTORY_LISTING;
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
        if (looksSmallTalkOnly(lower)) {
            return TaskType.SMALL_TALK;
        }
        return TaskType.READ_ONLY_QA;
    }

    private static boolean looksSmallTalkOnly(String lower) {
        return lower != null && SMALL_TALK_ONLY.matcher(lower).matches();
    }

    private static boolean looksAssistantIdentityQuestion(String lower) {
        return CapabilityAnswerPolicy.looksLikeIdentityOrCapabilityTurn(lower);
    }

    private static boolean looksSimpleDirectoryListingRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (looksDirectoryListingOnlyRequest(lower)) return true;
        if (containsAny(lower, SIMPLE_LISTING_EXCLUSION_MARKERS)) return false;
        return SIMPLE_DIRECTORY_LISTING.matcher(lower).matches();
    }

    private static boolean looksDirectoryListingOnlyRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!asksForDirectoryListing(lower)) return false;
        if (lower.contains("summarize")
                || lower.contains("summary")
                || lower.contains("explain")
                || lower.contains("diagnose")
                || lower.contains("search")
                || lower.contains("grep")
                || lower.contains("inside the files")
                || lower.contains("what does")) {
            return false;
        }
        return containsAny(lower, DIRECTORY_LIST_ONLY_MARKERS)
                || containsAny(lower, NEGATIVE_CONTENT_MARKERS);
    }

    private static boolean asksForDirectoryListing(String lower) {
        return lower.contains("list files")
                || lower.contains("list the files")
                || lower.contains("show me the files")
                || lower.contains("show the files")
                || lower.contains("what files")
                || lower.contains("which files")
                || SIMPLE_DIRECTORY_LISTING.matcher(lower).matches();
    }

    private static boolean looksExplicitNoInspectionDirectAnswer(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!containsAny(lower, NO_INSPECTION_MARKERS)) return false;
        if (asksForDirectoryListing(lower)) return false;
        if (lower.contains("search")
                || lower.contains("grep")
                || lower.contains("read ")
                || lower.contains("show me the files")
                || lower.contains("what files")) {
            return false;
        }
        return containsAny(lower, NO_INSPECTION_DIRECT_ANSWER_MARKERS);
    }

    private static boolean looksConversationalGreetingRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!lower.matches("^\\s*(?:hi|hello|hey|hey there|yo)\\b.*")) return false;
        if (containsAny(lower, WORKSPACE_MARKERS)
                || containsAny(lower, DIAGNOSE_MARKERS)
                || lower.contains("read ")
                || lower.contains("search ")
                || lower.contains("grep ")
                || lower.contains("file")
                || lower.contains("folder")
                || lower.contains("directory")) {
            return false;
        }
        return containsAny(lower, CHAT_ONLY_HINTS);
    }

    private static boolean looksLikeDeicticFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "");
        return DEICTIC_FOLLOW_UPS.contains(lower);
    }

    private static boolean looksLikeRepairFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "");
        return lower.contains("nothing changed")
                || lower.contains("nothing happened")
                || lower.contains("no changes happened")
                || lower.contains("try again")
                || lower.contains("try one more time")
                || lower.contains("try once more")
                || lower.contains("fix the remaining")
                || lower.contains("remaining static verification problems")
                || lower.contains("static verification problems")
                || lower.contains("complete it")
                || lower.contains("finish it")
                || lower.contains("make it work")
                || lower.contains("fix it")
                || lower.contains("fix this")
                || lower.contains("repair it")
                || lower.contains("repair this")
                || lower.contains("still does not work")
                || lower.contains("still doesn't work")
                || lower.contains("it does not work")
                || lower.contains("it doesn't work")
                || lower.contains("not working")
                || lower.contains("didn't work")
                || lower.contains("did not work")
                || lower.contains("incomplete");
    }

    private static TaskContract inheritedRepairContract(
            List<ChatMessage> messages,
            String latestUserRequest,
            TaskContract current
    ) {
        if (messages == null || messages.isEmpty()) return null;
        String previousAssistant = previousAssistantResponse(messages, latestUserRequest);
        if (!looksLikeIncompleteOutcome(previousAssistant)) return null;
        String previousUser = previousUserRequest(messages, latestUserRequest);
        if (previousUser == null || previousUser.isBlank()) return null;

        TaskContract prior = fromUserRequest(previousUser);
        if (!prior.mutationRequested() || !prior.mutationAllowed()) return null;
        if (current != null && current.mutationRequested() && !current.expectedTargets().isEmpty()) {
            return current;
        }
        return new TaskContract(
                prior.type(),
                true,
                true,
                true,
                prior.expectedTargets(),
                prior.forbiddenTargets(),
                inheritedRepairOriginalRequest(previousUser, latestUserRequest));
    }

    private static String inheritedRepairOriginalRequest(String previousUser, String latestUserRequest) {
        String previous = previousUser == null ? "" : previousUser.strip();
        String latest = latestUserRequest == null ? "" : latestUserRequest.strip();
        if (previous.isBlank()) return latest;
        if (latest.isBlank() || Objects.equals(previous, latest)) return previous;
        return previous + "\n\nRepair follow-up: " + latest;
    }

    private static boolean looksLikeIncompleteOutcome(String assistantResponse) {
        if (assistantResponse == null || assistantResponse.isBlank()) return false;
        String lower = assistantResponse.toLowerCase(Locale.ROOT);
        return lower.contains("task incomplete")
                || lower.contains("not verified complete")
                || lower.contains("partial verification")
                || lower.contains("the turn remains partial")
                || lower.contains("static verification failed")
                || lower.contains("remaining static verification problems")
                || lower.contains("no file changes were applied")
                || lower.contains("no files were changed");
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

    private static Set<String> withoutForbiddenTargets(Set<String> expectedTargets, Set<String> forbiddenTargets) {
        if (expectedTargets == null || expectedTargets.isEmpty()
                || forbiddenTargets == null || forbiddenTargets.isEmpty()) {
            return expectedTargets == null ? Set.of() : expectedTargets;
        }
        Set<String> forbidden = new LinkedHashSet<>();
        for (String target : forbiddenTargets) {
            forbidden.add(normalizeTargetForComparison(target));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String target : expectedTargets) {
            if (!forbidden.contains(normalizeTargetForComparison(target))) {
                out.add(target);
            }
        }
        return Set.copyOf(out);
    }

    private static String firstSentenceFragment(String span) {
        if (span == null || span.isBlank()) return "";
        String normalized = span.stripLeading();
        String[] pieces = normalized.split("(?<=[.!?;])\\s+", 2);
        return pieces.length == 0 ? normalized : pieces[0];
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

    private static String previousAssistantResponse(List<ChatMessage> messages, String latestUserRequest) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedLatest = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null) continue;
            String content = message.content();
            if ("user".equals(message.role())) {
                if (!skippedLatest && Objects.equals(content, latestUserRequest)) {
                    skippedLatest = true;
                }
                continue;
            }
            if (skippedLatest && "assistant".equals(message.role())) {
                return content == null || content.isBlank() ? null : content;
            }
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

    private static String normalizeTargetForComparison(String raw) {
        return normalizeTarget(raw).toLowerCase(Locale.ROOT);
    }
}
