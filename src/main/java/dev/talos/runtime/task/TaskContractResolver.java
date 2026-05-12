package dev.talos.runtime.task;

import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.policy.CapabilityAnswerPolicy;
import dev.talos.runtime.policy.ConversationBoundaryPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.verification.StaticWebImportIntent;
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
                    + "(?:change|edit|modify|write|create|save|apply|touch|mutate|use)"
                    + "|\\bwithout\\s+(?:changing|using))\\s+(.{0,240})");

    private static final Pattern AVOID_TARGET_SPAN = Pattern.compile(
            "(?i)\\bavoid\\s+(.{0,240})");

    private static final Pattern LEAVE_TARGET_ALONE_SPAN = Pattern.compile(
            "(?i)\\bleave\\s+(.{0,120}?)\\s+alone\\b");

    private static final Pattern EXTENSIONLESS_TEXT_TARGET = Pattern.compile(
            "(?i)\\b(?:edit|overwrite|replace|update|write|create|set)\\s+`?"
                    + "((?:[A-Za-z0-9_.\\\\/-]+/)?"
                    + "(?:README|LICENSE|NOTICE|CHANGELOG|CONTRIBUTING|AUTHORS|Makefile|Dockerfile))"
                    + "`?(?=$|\\s|[`'\"),;:!?\\]])");

    private static final Pattern BATCH_DIRECTORY_CREATION_SPAN = Pattern.compile(
            "(?i)\\b(?:create|make|mkdir)\\s+"
                    + "(?:directories|directory|dirs|dir|folders|folder)\\s+"
                    + "(.{1,180}?)(?=\\s+and\\s+(?:copy|move|rename|write|edit|create\\s+file)\\b|[.;]|$)");

    private static final Pattern NATURAL_BATCH_DIRECTORY_CREATION_SPAN = Pattern.compile(
            "(?i)\\b(?:create|make)\\s+"
                    + "(.{1,180}?)(?=\\s*,?\\s+(?:then\\s+)?(?:copy|move|rename)\\b)");

    private static final Pattern SINGLE_DIRECTORY_CREATION_TARGET = Pattern.compile(
            "(?i)\\b(?:mkdir|"
                    + "(?:make|create)\\s+(?:me\\s+)?(?:(?:a|an)\\s+)?(?:new\\s+)?"
                    + "(?:directories|directory|dirs|dir|folders|folder))\\s+"
                    + "(?:(?:called|named|as)\\s+)?"
                    + "`?([A-Za-z0-9_.\\\\/-]+(?:[\\\\/][A-Za-z0-9_.-]+)?)`?"
                    + "(?=$|\\s|[`'\"),;:!?\\]])");

    private static final Pattern BATCH_DESTINATION_OPERATION = Pattern.compile(
            "(?i)\\b(?:copy|move|rename)\\s+`?([^\\s,;`]+)`?\\s+"
                    + "(?:to|into)\\s+`?([^\\s,;`]+)`?");

    private static final Pattern NEGATED_READ_TARGET_SPAN = Pattern.compile(
            "(?i)(?:\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:show|display|include|read|inspect|open|summarize)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?(?:content|contents)?\\s*(?:from|of|in)?"
                    + "|\\bwithout\\s+"
                    + "(?:showing|displaying|including|reading|inspecting|opening|summarizing)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?(?:content|contents)?\\s*(?:from|of|in)?)"
                    + "\\s+(.{0,240})");

    private static final Pattern DIRECT_NEGATED_READ_TARGET_SPAN = Pattern.compile(
            "(?i)\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:show|display|include|read|inspect|open|summarize)\\s+(.{0,240})");

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

    private static final Set<String> COMMAND_EXECUTION_ACTION_MARKERS = Set.of(
            "run ", "execute ", "call ", "try ", "probe ", "verify ", "check ",
            "use talos.run_command with"
    );

    private static final Set<String> COMMAND_TOOL_MARKERS = Set.of(
            "talos.run_command", "run_command", "profile gradle_", "args_json", "timeout_ms"
    );

    private static final Set<String> GRADLE_COMMAND_MARKERS = Set.of(
            "gradle", "gradle_test", "gradle_check", "gradle_build", "gradle_install_dist",
            "gradle_e2e_test", "dev.talos."
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
            "do not show file contents",
            "don't show file contents",
            "dont show file contents",
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
            "how you would review",
            "how would you review",
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
        if (CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(original)) {
            return new TaskContract(
                    TaskType.SMALL_TALK,
                    false,
                    false,
                    false,
                    Set.of(),
                    Set.of(),
                    original,
                    "workspace-switch-unsupported");
        }
        if (CapabilityAnswerPolicy.looksLikeToolAliasCapabilityTurn(original)) {
            return new TaskContract(
                    TaskType.SMALL_TALK,
                    false,
                    false,
                    false,
                    Set.of(),
                    Set.of(),
                    original,
                    "tool-alias-capability-question");
        }
        boolean sessionMetaEvidenceQuestion = looksLikeSessionMetaEvidenceQuestion(lower);
        boolean sessionUncertaintyQuestion = looksLikeSessionUncertaintyQuestion(lower);
        boolean priorChangeStatusQuestion = MutationIntent.looksPriorChangeStatusQuestion(original);
        String classificationReason = MutationIntent.classificationReason(original);
        boolean mutationRequested = !sessionMetaEvidenceQuestion
                && !sessionUncertaintyQuestion
                && !priorChangeStatusQuestion
                && MutationIntent.isExplicitMutationClassificationReason(classificationReason);
        boolean commandVerificationRequest = !sessionMetaEvidenceQuestion
                && !sessionUncertaintyQuestion
                && !priorChangeStatusQuestion
                && !mutationRequested
                && looksExplicitCommandVerificationRequest(lower);
        boolean unsupportedCommandVerificationRequest = !sessionMetaEvidenceQuestion
                && !sessionUncertaintyQuestion
                && !priorChangeStatusQuestion
                && !mutationRequested
                && !commandVerificationRequest
                && looksUnsupportedNaturalCommandVerificationRequest(lower);
        TaskType type = sessionMetaEvidenceQuestion
                ? TaskType.VERIFY_ONLY
                : sessionUncertaintyQuestion
                ? TaskType.VERIFY_ONLY
                : priorChangeStatusQuestion
                ? TaskType.VERIFY_ONLY
                : commandVerificationRequest
                ? TaskType.VERIFY_ONLY
                : unsupportedCommandVerificationRequest
                ? TaskType.VERIFY_ONLY
                : classify(lower, mutationRequested, classificationReason);
        boolean mutationAllowed = mutationRequested
                && (type == TaskType.FILE_EDIT || type == TaskType.FILE_CREATE);
        boolean verificationRequired = mutationAllowed || type == TaskType.VERIFY_ONLY;
        MutationIntent.SourceToTargetArtifact sourceToTargetArtifact =
                MutationIntent.sourceToTargetArtifact(original).orElse(null);
        Set<String> forbiddenTargets = extractForbiddenTargets(original);
        Set<String> expectedTargets = extractExpectedTargets(original);
        Set<String> sourceEvidenceTargets = sourceToTargetArtifact == null
                ? Set.of()
                : sourceToTargetArtifact.sourceTargets();
        if (sourceToTargetArtifact != null && !sourceToTargetArtifact.outputTargets().isEmpty()) {
            expectedTargets = sourceToTargetArtifact.outputTargets();
        }
        if (mutationRequested && "explicit-batch-workspace-apply-request".equals(classificationReason)) {
            Set<String> batchTargets = extractBatchWorkspaceExpectedTargets(original);
            if (!batchTargets.isEmpty()) {
                expectedTargets = batchTargets;
            }
        } else if (mutationRequested && looksNaturalBatchWorkspaceOperation(original)) {
            Set<String> batchTargets = extractBatchWorkspaceExpectedTargets(original);
            if (!batchTargets.isEmpty()) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(expectedTargets);
                merged.addAll(batchTargets);
                expectedTargets = Set.copyOf(merged);
            }
        }
        if (!mutationRequested && StaticWebImportIntent.matches(original)) {
            expectedTargets = StaticWebImportIntent.evidenceTargets(original, expectedTargets);
        }
        if (mutationAllowed && !forbiddenTargets.isEmpty()) {
            expectedTargets = withoutForbiddenTargets(expectedTargets, forbiddenTargets);
        }
        Set<String> readForbiddenTargets = extractReadForbiddenTargets(original);
        if (!readForbiddenTargets.isEmpty()) {
            expectedTargets = withoutForbiddenTargets(expectedTargets, readForbiddenTargets);
            sourceEvidenceTargets = withoutForbiddenTargets(sourceEvidenceTargets, readForbiddenTargets);
        }

        return new TaskContract(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                sourceEvidenceTargets,
                forbiddenTargets,
                original,
                sessionMetaEvidenceQuestion
                        ? "session-meta-evidence-question"
                        : sessionUncertaintyQuestion
                        ? "session-uncertainty-question"
                        : commandVerificationRequest
                        ? "explicit-command-verification-request"
                        : unsupportedCommandVerificationRequest
                        ? "unsupported-command-verification-request"
                        : classificationReason);
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
        Matcher directoryMatcher = SINGLE_DIRECTORY_CREATION_TARGET.matcher(userRequest);
        while (directoryMatcher.find()) {
            String target = normalizeTarget(directoryMatcher.group(1));
            if (looksLikeDirectoryTarget(target)) out.add(target);
        }
        return Set.copyOf(out);
    }

    private static Set<String> extractBatchWorkspaceExpectedTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher directoryMatcher = BATCH_DIRECTORY_CREATION_SPAN.matcher(userRequest);
        while (directoryMatcher.find()) {
            for (String target : splitDirectoryTargets(directoryMatcher.group(1))) {
                if (!target.isBlank()) out.add(target);
            }
        }
        Matcher naturalDirectoryMatcher = NATURAL_BATCH_DIRECTORY_CREATION_SPAN.matcher(userRequest);
        while (naturalDirectoryMatcher.find()) {
            for (String target : splitDirectoryTargets(naturalDirectoryMatcher.group(1))) {
                if (!target.isBlank()) out.add(target);
            }
        }
        Matcher destinationMatcher = BATCH_DESTINATION_OPERATION.matcher(userRequest);
        while (destinationMatcher.find()) {
            String destination = normalizeTarget(destinationMatcher.group(2));
            if (!destination.isBlank()) out.add(destination);
        }
        return Set.copyOf(out);
    }

    private static boolean looksNaturalBatchWorkspaceOperation(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("batch this")
                || NATURAL_BATCH_DIRECTORY_CREATION_SPAN.matcher(userRequest).find();
    }

    private static List<String> splitDirectoryTargets(String rawSpan) {
        if (rawSpan == null || rawSpan.isBlank()) return List.of();
        String span = rawSpan
                .replaceAll("(?i)\\b(?:and\\s+)?then\\b", " ")
                .strip();
        String[] pieces = span.split("(?i)\\s*(?:,|\\band\\b)\\s*");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String piece : pieces) {
            String normalized = normalizeTarget(piece);
            if (looksLikeDirectoryTarget(normalized)) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static boolean looksLikeDirectoryTarget(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("a", "an", "the", "and", "to", "into").contains(lower)) return false;
        if (lower.contains(" ")) return false;
        return value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    }

    public static Set<String> extractForbiddenTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        addTargetsFromSpanMatches(out, NEGATED_TARGET_SPAN.matcher(userRequest));
        addTargetsFromSpanMatches(out, AVOID_TARGET_SPAN.matcher(userRequest));
        addTargetsFromSpanMatches(out, LEAVE_TARGET_ALONE_SPAN.matcher(userRequest));
        return Set.copyOf(out);
    }

    private static void addTargetsFromSpanMatches(Set<String> out, Matcher spanMatcher) {
        while (spanMatcher.find()) {
            String span = firstSentenceFragment(spanMatcher.group(1));
            Matcher targetMatcher = TARGET_FILE.matcher(span);
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
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
        Matcher directMatcher = DIRECT_NEGATED_READ_TARGET_SPAN.matcher(userRequest);
        while (directMatcher.find()) {
            String span = firstSentenceFragment(directMatcher.group(1));
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

    private static TaskType classify(String lower, boolean mutationRequested, String classificationReason) {
        if (mutationRequested) {
            if ("explicit-review-and-fix-request".equals(classificationReason)) {
                return TaskType.FILE_EDIT;
            }
            if ("explicit-source-to-target-artifact-request".equals(classificationReason)) {
                return TaskType.FILE_CREATE;
            }
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

    private static boolean looksExplicitCommandVerificationRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (lower.contains("what is talos.run_command")
                || lower.contains("what does talos.run_command")
                || lower.contains("how does talos.run_command")
                || lower.contains("how to use talos.run_command")
                || lower.contains("can talos use talos.run_command")) {
            return false;
        }
        if (!containsAny(lower, COMMAND_EXECUTION_ACTION_MARKERS)) return false;
        if (containsAny(lower, COMMAND_TOOL_MARKERS)) return true;
        return containsAny(lower, GRADLE_COMMAND_MARKERS)
                && looksGradleBuildOrTestVerification(lower);
    }

    private static boolean looksUnsupportedNaturalCommandVerificationRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!containsAny(lower, COMMAND_EXECUTION_ACTION_MARKERS)) return false;
        if (!lower.contains("command")) return false;
        return lower.contains("if it can't run")
                || lower.contains("if it cannot run")
                || lower.contains("safe command")
                || lower.contains("command check");
    }

    private static boolean looksLikeSessionMetaEvidenceQuestion(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!lower.contains("?")) return false;
        if (lower.contains("read it now")
                || lower.contains("read them now")
                || lower.contains("open it now")
                || lower.contains("inspect it now")) {
            return false;
        }
        boolean asksAboutPriorAction = lower.contains("did you read")
                || lower.contains("have you read")
                || lower.contains("has talos read")
                || lower.contains("did talos read")
                || lower.contains("did you write")
                || lower.contains("did you edit")
                || lower.contains("did you change")
                || lower.contains("did you modify")
                || lower.contains("did you update")
                || lower.contains("did talos write")
                || lower.contains("did talos edit")
                || lower.contains("did talos change")
                || lower.contains("did talos modify")
                || lower.contains("did talos update");
        if (!asksAboutPriorAction) return false;
        boolean evidenceScoped = lower.contains("verified evidence")
                || lower.contains("runtime evidence")
                || lower.contains("from this session")
                || lower.contains("in this session")
                || lower.contains("earlier")
                || lower.contains("previously")
                || lower.contains("already");
        boolean contentRequest = lower.contains("summarize")
                || lower.contains("summary")
                || lower.contains("tell me")
                || lower.contains("show me")
                || lower.contains("content")
                || lower.contains("contents")
                || lower.contains("what is in")
                || lower.contains("what does");
        if (!evidenceScoped && contentRequest) return false;
        return TARGET_FILE.matcher(lower).find() || EXTENSIONLESS_TEXT_TARGET.matcher(lower).find();
    }

    private static boolean looksLikeSessionUncertaintyQuestion(String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (!lower.contains("?")) return false;
        boolean asksUncertainty = lower.contains("unsure")
                || lower.contains("uncertain")
                || lower.contains("uncertainty")
                || lower.contains("not sure");
        if (!asksUncertainty) return false;
        return lower.contains("session")
                || lower.contains("audit")
                || lower.contains("turn")
                || lower.contains("trace")
                || lower.contains("evidence");
    }

    private static boolean looksGradleBuildOrTestVerification(String lower) {
        return lower.contains("test")
                || lower.contains("build")
                || lower.contains("gradle check")
                || lower.contains("passes")
                || lower.contains("pass ");
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
                || lower.contains("fix any obvious issue")
                || lower.contains("fix any obvious issues")
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
                inheritedRepairOriginalRequest(previousUser, latestUserRequest),
                "repair-follow-up-inherits-previous-mutation-contract");
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
                || lower.contains("action obligation failed")
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
                latestUserRequest,
                "deictic-read-only-follow-up-inherits-workspace-contract");
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
