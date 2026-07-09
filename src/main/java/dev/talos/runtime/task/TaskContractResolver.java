package dev.talos.runtime.task;

import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
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
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|py|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv|tmp|pdf|doc|docx|xls|xlsx|ppt|pptx|"
                    + "png|jpg|jpeg|gif|bmp|webp|tif|tiff|zip|tar|gz|tgz|7z|rar|"
                    + "exe|dll|so|dylib|class|jar|war|ear|bin|dat)"
                    + ")|(?:(?:[A-Za-z0-9_.\\\\/-]+/)?\\.env(?:\\.[A-Za-z0-9_.-]+)?))"
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");
    private static final Pattern INLINE_EXAMPLE_ARTIFACT_BEFORE = Pattern.compile(
            "(?is).*\\b(?:example|sample|template)\\s+$");
    private static final Pattern INLINE_EXAMPLE_ARTIFACT_AFTER = Pattern.compile(
            "(?is)^\\s*(?:example|sample|template)\\b.*");
    // Kept aligned with the MutationIntent verb families: a target governed by
    // any explicit action verb must bind even when example/sample/template
    // words appear nearby (T1002 hardening).
    private static final Pattern EXPLICIT_TARGET_ACTION_BEFORE = Pattern.compile(
            "(?i)\\b(?:read|open|inspect|summarize|create|write|edit|update|fix|delete|remove|use"
                    + "|modify|overwrite|replace|rewrite|regenerate|make|build|generate|scaffold"
                    + "|change|copy|move|rename|append|refactor)\\b");
    // A sentence boundary is punctuation followed by whitespace; dots inside
    // filenames, version numbers, or abbreviations never sever the verb.
    private static final Pattern SENTENCE_BOUNDARY_BEFORE_TARGET = Pattern.compile("[.?!;]\\s");

    private static final Pattern NEGATED_TARGET_SPAN = Pattern.compile(
            "(?i)(?:\\b(?:do\\s+not|don't|dont)\\s+"
                    + "(?:change|edit|modify|write|create|save|apply|touch|mutate|use)"
                    + "|\\bwithout\\s+(?:changing|using))\\s+(.{0,240})");

    private static final Pattern IGNORED_INSTRUCTION_OUTPUT_TARGET_SPAN = Pattern.compile(
            "(?i)\\b(?:ignore|disregard)\\s+(?:any\\s+)?"
                    + "(?:instruction|instructions|directive|directives)\\b"
                    + ".{0,240}?\\b(?:to|that\\s+(?:says?|tells?|asks?)\\s+(?:you\\s+)?to)\\s+"
                    + "(?:create|write|save|edit|modify|change|touch|mutate|use)\\s+(.{0,240})");

    private static final Pattern AVOID_TARGET_SPAN = Pattern.compile(
            "(?i)\\bavoid\\s+(.{0,240})");

    private static final Pattern LEAVE_TARGET_ALONE_SPAN = Pattern.compile(
            "(?i)\\bleave\\s+(.{0,120}?)\\s+alone\\b");
    private static final Pattern PRESERVE_UNCHANGED_TARGET_SPAN = Pattern.compile(
            "(?i)\\b(?:keep|preserve)\\s+(.{0,160}?)\\s+"
                    + "(?:unchanged|as\\s*-?\\s*is|intact)\\b");
    private static final Pattern DIRECT_NOT_TARGET_PREFIX = Pattern.compile(
            "(?is)(?:^|[\\s,;])not\\s+$");
    private static final Pattern TAILWIND_NEGATIVE_LOCAL_ARTIFACT = Pattern.compile(
            "(?i)\\bno\\s+(?:broken|placeholder|fake|stub|local|orphan(?:ed)?)\\s+"
                    + "(.{0,80}?tailwind(?:\\.min)?\\.css)\\b");
    private static final Pattern TAILWIND_GENERIC_LOCAL_ARTIFACT_BAN = Pattern.compile(
            "(?i)\\b(?:no|avoid|without|do\\s+not|don't|dont)\\s+"
                    + "(?:creating\\s+|create\\s+|using\\s+|use\\s+)?"
                    + "(?:a\\s+|any\\s+)?(?:broken\\s+|placeholder\\s+|fake\\s+|stub\\s+|local\\s+|orphan(?:ed)?\\s+)*"
                    + "tailwind\\s+(?:artifacts?|files?|css\\s+files?)\\b");
    private static final Pattern GENERIC_FRAMEWORK_LOCAL_ARTIFACT_BAN = Pattern.compile(
            "(?i)\\b(?:no|avoid|without|do\\s+not|don't|dont)\\s+"
                    + "(?:creating\\s+|create\\s+|using\\s+|use\\s+)?"
                    + "(?:a\\s+|any\\s+)?(?:broken\\s+|placeholder\\s+|fake\\s+|stub\\s+|local\\s+|orphan(?:ed)?\\s+)*"
                    + "(?:frontend\\s+|framework\\s+|cdn\\s+)?(?:artifacts?|files?|css\\s+files?|js\\s+files?)\\b");
    private static final Pattern FRAMEWORK_CDN_ONLY = Pattern.compile(
            "(?i)\\b(?:bootstrap|alpine|htmx|react|vue)\\b.{0,80}\\b(?:cdn\\s+only|through\\s+the\\s+cdn\\s+only|with\\s+the\\s+cdn\\s+only)\\b");
    private static final List<FrameworkArtifactFamily> FRONTEND_FRAMEWORK_ARTIFACTS = List.of(
            new FrameworkArtifactFamily("bootstrap", List.of(
                    "bootstrap.css",
                    "bootstrap.min.css",
                    "bootstrap.js",
                    "bootstrap.min.js",
                    "bootstrap.bundle.js",
                    "bootstrap.bundle.min.js")),
            new FrameworkArtifactFamily("alpine", List.of("alpine.js", "alpine.min.js")),
            new FrameworkArtifactFamily("htmx", List.of("htmx.js", "htmx.min.js")),
            new FrameworkArtifactFamily("react", List.of(
                    "react.js",
                    "react.min.js",
                    "react-dom.js",
                    "react-dom.min.js")),
            new FrameworkArtifactFamily("vue", List.of("vue.js", "vue.min.js")));

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
                    + "(?:(?:to|into)\\s+|->\\s*)`?([^\\s,;`]+)`?");

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

    private static final Pattern EXPLICIT_NATURAL_COMMAND_EXECUTION = Pattern.compile(
            "(?i)\\b(?:run|execute|call|try)\\s+(?:the\\s+)?command\\b");

    private static final Pattern SIMPLE_DIRECTORY_LISTING = Pattern.compile(
            "(?i)^\\s*(?:"
                    + "(?:what|which)\\s+(?:files|folders|directories|items|entries)\\s+"
                    + "(?:are|exist|do\\s+we\\s+have)?\\s*(?:in|inside)?\\s*"
                    + "(?:this|the|current|here)?\\s*(?:folder|directory|workspace|repo|repository)?"
                    + "|what(?:'s|\\s+is)\\s+(?:in\\s+)?here"
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
            "do not inspect this workspace",
            "do not inspect the workspace",
            "do not inspect workspace",
            "don't inspect this workspace",
            "don't inspect the workspace",
            "don't inspect workspace",
            "dont inspect this workspace",
            "dont inspect the workspace",
            "dont inspect workspace",
            "do not read this workspace",
            "do not read the workspace",
            "do not read workspace",
            "don't read this workspace",
            "don't read the workspace",
            "don't read workspace",
            "do not check this workspace",
            "do not check the workspace",
            "do not check workspace",
            "do not inspect my files",
            "don't inspect my files",
            "dont inspect my files",
            "without inspecting the workspace",
            "without inspecting workspace",
            "without checking the workspace",
            "without checking workspace",
            "without reading the workspace",
            "without reading workspace",
            "without using this workspace",
            "without using the workspace",
            "without using workspace",
            "without inspecting or using this workspace",
            "without inspecting or using the workspace",
            "without inspecting or using workspace",
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

    private static final Pattern SOURCE_EVIDENCE_SPAN = Pattern.compile(
            "(?i)\\b(according\\s+to|based\\s+on|summari[sz]ing|summary\\s+of|from|using)\\b\\s+(.{1,320})");

    // T895: the file named in the leading "read|open ... and|then <mutate>" clause is a READ
    // source, not a mutation target. Captured so it is projected as source evidence (MUST_READ)
    // rather than a MUST_MUTATE obligation that falsely blocks the turn.
    private static final Pattern READ_CLAUSE_BEFORE_MUTATION = Pattern.compile(
            "(?i)\\b(?:read|open)\\s+(.{0,120}?)\\b(?:and|then)\\b");

    // T896: distinguish a leading EDIT verb from a CREATE verb so an explicit edit of an existing
    // named file ("Edit index.html to add a Contact section") classifies FILE_EDIT even though
    // "add a" is a CREATE_MARKER describing content to add INSIDE the file.
    private static final Pattern EDIT_LEADING_VERB = Pattern.compile(
            "(?i)\\b(?:edit|modify|change|update|fix|rewrite|replace|append|remove|delete|adjust|tweak|restyle|redesign|refactor)\\b");
    private static final Pattern CREATE_LEADING_VERB = Pattern.compile(
            "(?i)\\b(?:create|write|build|generate|scaffold|make|add)\\b");

    private static final Pattern PYTHON_COMMAND_EXECUTION = Pattern.compile(
            "(?i)(?:\\b(?:run|execute|try|probe|verify|check|test)\\s+"
                    + "(?:(?:python3?|py)\\b|pytest\\b|(?:this|the)\\s+python\\s+file\\b|"
                    + "(?:[A-Za-z0-9_.\\\\/-]+\\.py)\\b)"
                    + "|\\b(?:python3?|py)\\s+-m\\s+pytest\\b"
                    + "|\\b(?:python3?|py)\\s+(?:[A-Za-z0-9_.\\\\/-]+\\.py)\\b)");

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

    // Bare English function words that verb-pattern captures can swallow as path tokens
    // (e.g. "mkdir by writing/editing file content" in the workspace-operation retry frame).
    // Membership is whole-token, so names with a file extension or path separator never match.
    private static final Set<String> BARE_PATH_STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "into", "onto",
            "by", "with", "using", "via", "from", "of", "in", "on", "at", "for", "as");

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
        if (!current.mutationRequested() && looksLikeConfirmationFollowUp(latest)) {
            TaskContract inherited = inheritedAssistantPlanContract(messages, latest, current);
            if (inherited != null) return withContextualStaticWebTargets(messages, latest, inherited);
        }
        if (looksLikeRepairFollowUp(latest)) {
            TaskContract inherited = inheritedRepairContract(messages, latest, current);
            if (inherited != null) return withContextualStaticWebTargets(messages, latest, inherited);
        }
        if (!current.mutationRequested() && looksLikeCorrectionFollowUp(latest)) {
            TaskContract inherited = inheritedCorrectionContract(messages, latest);
            if (inherited != null) return withContextualStaticWebTargets(messages, latest, inherited);
        }
        if (looksLikeDeicticFollowUp(latest) && !current.mutationRequested()) {
            TaskContract inherited = inheritedReadOnlyWorkspaceContract(messages, latest);
            if (inherited != null) return inherited;
        }
        return withContextualStaticWebTargets(messages, latest, current);
    }

    public static TaskIntent intentFromMessages(List<ChatMessage> messages) {
        return intentFromUserRequest(latestUserRequest(messages));
    }

    public static TaskContract fromUserRequest(String userRequest) {
        TaskContract legacy = resolveLegacyFromUserRequest(userRequest);
        return TaskContractCompiler.compile(TaskIntentResolver.fromUserRequest(userRequest, legacy));
    }

    public static TaskIntent intentFromUserRequest(String userRequest) {
        TaskContract legacy = resolveLegacyFromUserRequest(userRequest);
        return TaskIntentResolver.fromUserRequest(userRequest, legacy);
    }

    static TaskContract resolveLegacyFromUserRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()
                || ToolCallSupport.isSyntheticToolResultContent(userRequest)) {
            return TaskContract.unknown(userRequest);
        }

        String original = userRequest.strip();
        String lower = original.toLowerCase(Locale.ROOT);
        if (looksLikeCheckpointRestoreRequest(lower)) {
            return new TaskContract(
                    TaskType.CHECKPOINT_RESTORE,
                    true,
                    true,
                    true,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    original,
                    "checkpoint-restore-request");
        }
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
            Set<String> batchSources = extractBatchWorkspaceSourceTargets(original);
            if (!batchSources.isEmpty()) {
                expectedTargets = withoutForbiddenTargets(expectedTargets, batchSources);
            }
            Set<String> batchTargets = extractBatchWorkspaceExpectedTargets(original);
            if (!batchTargets.isEmpty()) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(expectedTargets);
                merged.addAll(batchTargets);
                expectedTargets = Set.copyOf(merged);
            }
        }
        if (mutationAllowed) {
            Set<String> lexicalSourceTargets = extractLexicalSourceEvidenceTargets(original);
            if (!lexicalSourceTargets.isEmpty()) {
                LinkedHashSet<String> mergedSources = new LinkedHashSet<>(sourceEvidenceTargets);
                mergedSources.addAll(lexicalSourceTargets);
                sourceEvidenceTargets = Set.copyOf(mergedSources);
                if (!readEvidenceTargetsAreAlsoMutationTargets(original)) {
                    expectedTargets = withoutForbiddenTargets(expectedTargets, sourceEvidenceTargets);
                }
            }
            // T895: "read X and create/write Y" names X as a READ source, not a mutation target.
            // When X is distinct from the create target(s) Y, project X as source evidence so it is
            // never a MUST_MUTATE obligation (which falsely blocked the turn when only Y was written).
            // When X is the only target (read then mutate the SAME file), createTargets is empty and
            // X stays a mutation target.
            if ("explicit-read-then-mutation-request".equals(classificationReason)
                    && !readEvidenceTargetsAreAlsoMutationTargets(original)) {
                Set<String> readSources = extractReadThenMutationSourceTargets(original);
                if (!readSources.isEmpty()) {
                    LinkedHashSet<String> createTargets = new LinkedHashSet<>(expectedTargets);
                    createTargets.removeAll(readSources);
                    // Only treat the read source as evidence when a genuine, NON-forbidden write
                    // target remains distinct from it. When the read source is the only write
                    // target (read then mutate the same file), or the only remainder is a forbidden
                    // target (e.g. "... do not edit scripts.js"), leave expectedTargets unchanged.
                    LinkedHashSet<String> genuineWriteTargets = new LinkedHashSet<>(createTargets);
                    genuineWriteTargets.removeAll(forbiddenTargets);
                    if (!genuineWriteTargets.isEmpty()) {
                        expectedTargets = Set.copyOf(createTargets);
                        LinkedHashSet<String> mergedSources = new LinkedHashSet<>(sourceEvidenceTargets);
                        mergedSources.addAll(readSources);
                        sourceEvidenceTargets = Set.copyOf(mergedSources);
                    }
                }
            }
            if (expectedTargets.isEmpty()) {
                expectedTargets = withoutForbiddenTargets(
                        inferConventionalStaticWebTargets(original, type),
                        forbiddenTargets);
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
        Set<String> ignoredInstructionOutputTargets = extractIgnoredInstructionOutputTargets(original);
        if (!ignoredInstructionOutputTargets.isEmpty()) {
            expectedTargets = withoutForbiddenTargets(expectedTargets, ignoredInstructionOutputTargets);
            sourceEvidenceTargets = withoutForbiddenTargets(sourceEvidenceTargets, ignoredInstructionOutputTargets);
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

    private static boolean looksLikeCheckpointRestoreRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        String normalized = lower.strip().replaceAll("\\s+", " ");
        if (normalized.startsWith("how ")
                || normalized.startsWith("what ")
                || normalized.startsWith("why ")
                || normalized.startsWith("explain ")
                || normalized.startsWith("tell me ")) {
            return false;
        }
        boolean restoreVerb = normalized.contains("revert")
                || normalized.contains("undo")
                || normalized.contains("rollback")
                || normalized.contains("roll back")
                || normalized.contains("restore");
        if (!restoreVerb) return false;
        return normalized.contains("your change")
                || normalized.contains("your changes")
                || normalized.contains("talos change")
                || normalized.contains("talos changes")
                || normalized.contains("previous change")
                || normalized.contains("previous changes")
                || normalized.contains("last change")
                || normalized.contains("last changes")
                || normalized.contains("last turn")
                || normalized.contains("previous turn")
                || normalized.contains("what you changed")
                || normalized.contains("what you did");
    }

    public static Set<String> extractExpectedTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Matcher matcher = TARGET_FILE.matcher(userRequest);
        Set<String> out = new LinkedHashSet<>();
        while (matcher.find()) {
            String target = normalizeTarget(matcher.group(1));
            if (!target.isBlank()
                    && !inlineExampleArtifactMention(userRequest, matcher.start(1), matcher.end(1))) {
                out.add(target);
            }
        }
        Matcher extensionlessMatcher = EXTENSIONLESS_TEXT_TARGET.matcher(userRequest);
        while (extensionlessMatcher.find()) {
            String target = normalizeTarget(extensionlessMatcher.group(1));
            if (!target.isBlank()
                    && !inlineExampleArtifactMention(
                    userRequest,
                    extensionlessMatcher.start(1),
                    extensionlessMatcher.end(1))) {
                out.add(target);
            }
        }
        Matcher directoryMatcher = SINGLE_DIRECTORY_CREATION_TARGET.matcher(userRequest);
        while (directoryMatcher.find()) {
            String target = normalizeTarget(directoryMatcher.group(1));
            if (looksLikeDirectoryTarget(target)) out.add(target);
        }
        return Set.copyOf(out);
    }

    private static boolean inlineExampleArtifactMention(String userRequest, int start, int end) {
        if (userRequest == null || userRequest.isBlank() || start < 0 || end < start) return false;
        // Windows are sliced from the original string first and lowercased
        // per window: lowercasing the whole request can change its length
        // (Turkish dotted capital I under ROOT), which would misalign the
        // matcher offsets and silently disable the suppression.
        int beforeStart = Math.max(0, start - 160);
        int afterEnd = Math.min(userRequest.length(), end + 40);
        String before = userRequest.substring(beforeStart, start).toLowerCase(Locale.ROOT);
        String after = userRequest.substring(end, afterEnd).toLowerCase(Locale.ROOT);
        if (explicitTargetActionNear(before)) return false;
        return INLINE_EXAMPLE_ARTIFACT_BEFORE.matcher(before).matches()
                || INLINE_EXAMPLE_ARTIFACT_AFTER.matcher(after).matches();
    }

    private static boolean explicitTargetActionNear(String beforeTarget) {
        if (beforeTarget == null || beforeTarget.isBlank()) return false;
        String tail = beforeTarget.replaceAll("\\s+", " ");
        Matcher boundary = SENTENCE_BOUNDARY_BEFORE_TARGET.matcher(tail);
        int lastBoundaryEnd = -1;
        while (boundary.find()) {
            lastBoundaryEnd = boundary.end();
        }
        if (lastBoundaryEnd >= 0) {
            tail = tail.substring(lastBoundaryEnd);
        }
        return EXPLICIT_TARGET_ACTION_BEFORE.matcher(tail).find();
    }

    private static Set<String> extractLexicalSourceEvidenceTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher spanMatcher = SOURCE_EVIDENCE_SPAN.matcher(userRequest);
        while (spanMatcher.find()) {
            String marker = spanMatcher.group(1);
            String span = sourceEvidenceFragment(marker, spanMatcher.group(2));
            if (span.isBlank()) continue;
            Matcher targetMatcher = TARGET_FILE.matcher(span);
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
            Matcher extensionlessMatcher = EXTENSIONLESS_TEXT_TARGET.matcher(span);
            while (extensionlessMatcher.find()) {
                String target = normalizeTarget(extensionlessMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
        return Set.copyOf(out);
    }

    // T895: extract the read-source file(s) from the leading "read|open ... and|then" clause of a
    // read-then-mutation request, so a distinct read source is not projected as a mutation target.
    private static Set<String> extractReadThenMutationSourceTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Matcher clause = READ_CLAUSE_BEFORE_MUTATION.matcher(userRequest);
        if (!clause.find()) return Set.of();
        String span = clause.group(1);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher target = TARGET_FILE.matcher(span);
        while (target.find()) {
            String normalized = normalizeTarget(target.group(1));
            if (!normalized.isBlank()) out.add(normalized);
        }
        return Set.copyOf(out);
    }

    private static boolean readEvidenceTargetsAreAlsoMutationTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean asksReadFirst = lower.contains("read the current")
                || lower.contains("read current")
                || lower.contains("inspect the current")
                || lower.contains("inspect current")
                || lower.contains("open the current")
                || lower.contains("open current");
        if (!asksReadFirst) return false;
        return lower.contains("then rewrite the existing files")
                || lower.contains("then rewrite existing files")
                || lower.contains("then update the existing files")
                || lower.contains("then update existing files")
                || lower.contains("then edit the existing files")
                || lower.contains("then edit existing files")
                || lower.contains("rewrite the existing files")
                || lower.contains("rewrite existing files")
                || lower.contains("rewrite the current files")
                || lower.contains("update the current files");
    }

    private static String sourceEvidenceFragment(String marker, String span) {
        if (span == null || span.isBlank()) return "";
        String fragment = firstSentenceFragment(span);
        String lowerMarker = marker == null ? "" : marker.toLowerCase(Locale.ROOT).strip();
        String lowerFragment = fragment.toLowerCase(Locale.ROOT).stripLeading();
        if ("using".equals(lowerMarker)) {
            if (lowerFragment.startsWith("exactly ")) {
                return "";
            }
            Matcher firstTarget = TARGET_FILE.matcher(fragment);
            if (firstTarget.find()) {
                int colon = fragment.indexOf(':');
                if (colon >= 0 && colon < firstTarget.start()) {
                    return "";
                }
            }
            if (lowerFragment.startsWith("workspace operation tool")
                    || lowerFragment.startsWith("workspace tool")
                    || lowerFragment.startsWith("file tool")
                    || lowerFragment.startsWith("tool ")
                    || lowerFragment.startsWith("tools ")) {
                return "";
            }
        }
        if ("from".equals(lowerMarker)) {
            int end = fragment.length();
            Matcher delimiter = Pattern.compile("(?i)\\b(?:with|use|using|as|to|into|in)\\b")
                    .matcher(fragment);
            if (delimiter.find()) {
                end = delimiter.start();
            }
            return fragment.substring(0, end);
        }
        return fragment;
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
            if (!destination.isBlank() && !isBarePathStopWord(destination)) out.add(destination);
        }
        return Set.copyOf(out);
    }

    private static Set<String> extractBatchWorkspaceSourceTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher sourceMatcher = BATCH_DESTINATION_OPERATION.matcher(userRequest);
        while (sourceMatcher.find()) {
            String source = normalizeTarget(sourceMatcher.group(1));
            if (!source.isBlank()) out.add(source);
        }
        return Set.copyOf(out);
    }

    private static boolean looksNaturalBatchWorkspaceOperation(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("batch this")
                || NATURAL_BATCH_DIRECTORY_CREATION_SPAN.matcher(userRequest).find();
    }

    private static Set<String> inferConventionalStaticWebTargets(String userRequest, TaskType type) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (looksDocumentGuideAboutWebSurface(lower)) return Set.of();
        boolean createLike = type == TaskType.FILE_CREATE
                || lower.contains("build")
                || lower.contains("create")
                || lower.contains("generate")
                || lower.contains("scaffold")
                || lower.contains("set up")
                || lower.contains("setup")
                || lower.contains("make me");
        if (!createLike) return Set.of();

        boolean webSurface = mentionsStaticWebSurface(lower);
        boolean deicticSite = lower.contains("that site")
                || lower.contains("the site")
                || lower.contains("that webpage")
                || lower.contains("the webpage")
                || lower.contains("that web page")
                || lower.contains("the web page");
        boolean strongSingularConvention = lower.contains("synthwave")
                || lower.contains("modern")
                || lower.contains("polished")
                || lower.contains("good looking")
                || lower.contains("cool looking");
        boolean namesStyleAndScript = mentionsStyleAsset(lower) && mentionsScriptAsset(lower);
        if (!deicticSite && !(webSurface && namesStyleAndScript && strongSingularConvention)) {
            return Set.of();
        }

        return conventionalStaticWebTargets();
    }

    private static Set<String> conventionalStaticWebTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("index.html");
        targets.add("style.css");
        targets.add("script.js");
        return Set.copyOf(targets);
    }

    private static boolean looksDocumentGuideAboutWebSurface(String lower) {
        if (lower == null || lower.isBlank()) return false;
        boolean documentOutput = lower.contains("pdf file")
                || lower.contains(".pdf")
                || lower.contains("docx file")
                || lower.contains("word file")
                || lower.contains(".docx")
                || lower.contains("txt file")
                || lower.contains("text file")
                || lower.contains(".txt")
                || lower.contains("markdown file")
                || lower.contains(".md");
        boolean explanatory = lower.contains("talks about")
                || lower.contains("guide")
                || lower.contains("instructions")
                || lower.contains("how to build")
                || lower.contains("how to create")
                || lower.contains("how to make");
        return documentOutput && explanatory && mentionsStaticWebSurface(lower);
    }

    private static boolean mentionsStaticWebSurface(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("website")
                || lower.contains("web site")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("frontend")
                || lower.contains("front-end")
                || lower.contains("landing page")
                || lower.contains(" site")
                || lower.contains(" page");
    }

    private static boolean mentionsStyleAsset(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("css")
                || lower.contains(".css")
                || lower.contains("stylesheet")
                || lower.contains("style sheet")
                || lower.contains("style.css")
                || lower.contains("styles.css")
                || lower.contains("styling")
                || lower.contains("style")
                || lower.contains("modern")
                || lower.contains("synthwave")
                || lower.contains("neon")
                || lower.contains("visual")
                || lower.contains("design");
    }

    private static boolean mentionsScriptAsset(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("javascript")
                || lower.contains(".js")
                || lower.contains("script.js")
                || lower.contains("scripts.js")
                || lower.contains("scripting")
                || lower.contains("script file")
                || lower.contains("interaction")
                || lower.contains("interactive")
                || lower.contains("functioning")
                || lower.contains("functional");
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
        if (isBarePathStopWord(value)) return false;
        if (value.contains(" ")) return false;
        return value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    }

    private static boolean isBarePathStopWord(String value) {
        return value != null && BARE_PATH_STOP_WORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    public static Set<String> extractForbiddenTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        addTargetsFromSpanMatches(out, NEGATED_TARGET_SPAN.matcher(userRequest));
        out.addAll(extractIgnoredInstructionOutputTargets(userRequest));
        addTargetsFromSpanMatches(out, AVOID_TARGET_SPAN.matcher(userRequest));
        addTargetsFromSpanMatches(out, LEAVE_TARGET_ALONE_SPAN.matcher(userRequest));
        out.addAll(extractPreserveUnchangedTargets(userRequest));
        addTailwindNegativeLocalArtifactTargets(out, userRequest);
        addFrontendFrameworkNegativeLocalArtifactTargets(out, userRequest);
        addDirectNotTargets(out, userRequest);
        return Set.copyOf(out);
    }

    private static Set<String> extractIgnoredInstructionOutputTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        addTargetsFromSpanMatches(out, IGNORED_INSTRUCTION_OUTPUT_TARGET_SPAN.matcher(userRequest));
        return Set.copyOf(out);
    }

    private static void addTailwindNegativeLocalArtifactTargets(Set<String> out, String userRequest) {
        if (TAILWIND_GENERIC_LOCAL_ARTIFACT_BAN.matcher(userRequest).find()) {
            addCommonLocalTailwindArtifactTargets(out);
        }
        Matcher spanMatcher = TAILWIND_NEGATIVE_LOCAL_ARTIFACT.matcher(userRequest);
        while (spanMatcher.find()) {
            Matcher targetMatcher = TARGET_FILE.matcher(spanMatcher.group(1));
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
    }

    private static void addCommonLocalTailwindArtifactTargets(Set<String> out) {
        if (out == null) return;
        out.add("tailwind.css");
        out.add("tailwind.min.css");
    }

    private static void addFrontendFrameworkNegativeLocalArtifactTargets(Set<String> out, String userRequest) {
        if (out == null || userRequest == null || userRequest.isBlank()) return;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean genericLocalArtifactBan = GENERIC_FRAMEWORK_LOCAL_ARTIFACT_BAN.matcher(userRequest).find()
                || FRAMEWORK_CDN_ONLY.matcher(userRequest).find();
        for (FrameworkArtifactFamily family : FRONTEND_FRAMEWORK_ARTIFACTS) {
            if (!containsFrameworkName(userRequest, family.name())) continue;
            if (genericLocalArtifactBan) {
                out.addAll(family.artifactTargets());
                continue;
            }
            for (String target : family.artifactTargets()) {
                if (lower.contains("no placeholder " + family.name())
                        || lower.contains("no broken " + target)
                        || lower.contains("no placeholder " + target)
                        || lower.contains("do not create " + target)
                        || lower.contains("don't create " + target)
                        || lower.contains("dont create " + target)
                        || lower.contains("do not use " + target)
                        || lower.contains("don't use " + target)
                        || lower.contains("dont use " + target)) {
                    out.add(target);
                }
            }
        }
    }

    private static boolean containsFrameworkName(String value, String frameworkName) {
        if (value == null || value.isBlank() || frameworkName == null || frameworkName.isBlank()) {
            return false;
        }
        return Pattern.compile("(?i)(?<![A-Za-z0-9_-])"
                        + Pattern.quote(frameworkName)
                        + "(?![A-Za-z0-9_-])")
                .matcher(value)
                .find();
    }

    public static Set<String> extractPreserveUnchangedTargets(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher preserveMatcher = PRESERVE_UNCHANGED_TARGET_SPAN.matcher(userRequest);
        while (preserveMatcher.find()) {
            String span = firstSentenceFragment(preserveMatcher.group(1));
            if (!preserveSpanNamesOnlyTargets(span)) continue;
            Matcher targetMatcher = TARGET_FILE.matcher(span);
            while (targetMatcher.find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
        return Set.copyOf(out);
    }

    private static boolean preserveSpanNamesOnlyTargets(String span) {
        if (span == null || span.isBlank()) return false;
        String residue = TARGET_FILE.matcher(span).replaceAll(" ");
        residue = residue
                .replace('`', ' ')
                .replace('\'', ' ')
                .replace('"', ' ')
                .replace(',', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replaceAll("(?i)\\b(?:the|file|files|target|targets|current|existing|root|and|or)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return residue.isBlank();
    }

    private static void addDirectNotTargets(Set<String> out, String userRequest) {
        Matcher targetMatcher = TARGET_FILE.matcher(userRequest);
        while (targetMatcher.find()) {
            int start = targetMatcher.start(1);
            String prefix = userRequest.substring(Math.max(0, start - 24), start)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[`'\"]+$", "");
            if (DIRECT_NOT_TARGET_PREFIX.matcher(prefix).find()) {
                String target = normalizeTarget(targetMatcher.group(1));
                if (!target.isBlank()) out.add(target);
            }
        }
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

    // T896: true when the leading mutation verb is an EDIT verb occurring before any CREATE verb.
    // An explicit edit of a named existing file stays an edit even if the body says "add a/the X".
    private static boolean startsWithEditNotCreateVerb(String lower) {
        if (lower == null || lower.isBlank()) return false;
        Matcher edit = EDIT_LEADING_VERB.matcher(lower);
        if (!edit.find()) return false;
        Matcher create = CREATE_LEADING_VERB.matcher(lower);
        return !create.find() || edit.start() < create.start();
    }

    private static TaskType classify(String lower, boolean mutationRequested, String classificationReason) {
        if (mutationRequested) {
            if ("explicit-review-and-fix-request".equals(classificationReason)) {
                return TaskType.FILE_EDIT;
            }
            if ("explicit-source-to-target-artifact-request".equals(classificationReason)) {
                return TaskType.FILE_CREATE;
            }
            if (looksCreateMissingFilesRequest(lower)) {
                return TaskType.FILE_CREATE;
            }
            // T896: when a CREATE_MARKER is present but an explicit EDIT verb leads before any
            // CREATE verb, the marker describes content added INSIDE an existing file (e.g. "Edit
            // index.html to add a Contact section"), so it is an edit, not a new-file create.
            if (containsAny(lower, CREATE_MARKERS)) {
                return startsWithEditNotCreateVerb(lower) ? TaskType.FILE_EDIT : TaskType.FILE_CREATE;
            }
            return TaskType.FILE_EDIT;
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
        if (looksUnsupportedPythonCommandExecutionRequest(lower)) return true;
        if (!containsAny(lower, COMMAND_EXECUTION_ACTION_MARKERS)) return false;
        if (!lower.contains("command")) return false;
        if (EXPLICIT_NATURAL_COMMAND_EXECUTION.matcher(lower).find()) return true;
        return lower.contains("if it can't run")
                || lower.contains("if it cannot run")
                || lower.contains("safe command")
                || lower.contains("command check");
    }

    private static boolean looksCreateMissingFilesRequest(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return (lower.contains("make") || lower.contains("create") || lower.contains("add"))
                && (lower.contains("rest files")
                || lower.contains("remaining files")
                || lower.contains("missing files"));
    }

    public static boolean looksUnsupportedPythonCommandExecutionRequest(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (PYTHON_COMMAND_EXECUTION.matcher(lower).find()) return true;
        if (!containsAny(lower, COMMAND_EXECUTION_ACTION_MARKERS)) return false;
        boolean pythonSurface = lower.contains("python")
                || lower.contains("pytest")
                || lower.contains(".py");
        if (!pythonSurface) return false;
        return lower.contains("run tests")
                || lower.contains("run the tests")
                || lower.contains("execute tests")
                || lower.contains("execute the tests")
                || lower.contains("verify tests")
                || lower.contains("verify the tests")
                || lower.contains("check tests")
                || lower.contains("check the tests");
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
        return !(lower.contains("search")
                || lower.contains("grep")
                || lower.contains("read ")
                || lower.contains("show me the files")
                || lower.contains("what files"));
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

    private static boolean looksLikeConfirmationFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "");
        return lower.equals("yes")
                || lower.equals("yes proceed")
                || lower.equals("yes proceed please")
                || lower.equals("proceed")
                || lower.equals("proceed please")
                || lower.equals("go ahead")
                || lower.equals("go ahead please")
                || lower.equals("do it")
                || lower.equals("do it please")
                || lower.equals("continue")
                || lower.equals("continue please");
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
                || lower.contains("final pass")
                || lower.contains("stress check")
                || lower.contains("inspect and repair")
                || lower.contains("repair anything remaining")
                || lower.contains("fix what remains")
                || lower.contains("leave it in the best verified state")
                || lower.contains("best verified state")
                || lower.contains("still does not work")
                || lower.contains("still doesn't work")
                || lower.contains("it does not work")
                || lower.contains("it doesn't work")
                || lower.contains("not working")
                || lower.contains("didn't work")
                || lower.contains("did not work")
                || lower.contains("incomplete");
    }

    private static boolean looksLikeCorrectionFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "");
        boolean correctionLanguage = lower.startsWith("but ")
                || lower.startsWith("no,")
                || lower.startsWith("no ")
                || lower.contains("you just ")
                || lower.contains("you only ")
                || lower.contains("you never ")
                || lower.contains("it still ")
                || lower.contains("there is no ")
                || lower.contains("there isn't ")
                || lower.contains("missing ");
        if (!correctionLanguage) return false;
        return lower.contains("no styling")
                || lower.contains("no style")
                || lower.contains("no css")
                || lower.contains("without styling")
                || lower.contains("without style")
                || lower.contains("without css")
                || lower.contains("missing styling")
                || lower.contains("missing style")
                || lower.contains("missing css")
                || lower.contains("never put any style")
                || lower.contains("never added style")
                || lower.contains("never added css")
                || lower.contains("reduced it")
                || lower.contains("changed the index");
    }

    private static TaskContract withContextualStaticWebTargets(
            List<ChatMessage> messages,
            String latestUserRequest,
            TaskContract contract
    ) {
        if (contract == null
                || !contract.expectedTargets().isEmpty()
                || !looksContextualStaticWebAssetFollowUp(latestUserRequest)
                || !priorMessagesMentionStaticWebSurface(messages, latestUserRequest)) {
            return contract;
        }
        Set<String> expectedTargets = withoutForbiddenTargets(
                conventionalStaticWebTargets(),
                contract.forbiddenTargets());
        if (expectedTargets.isEmpty()) return contract;
        return new TaskContract(
                contract.mutationAllowed() ? contract.type() : TaskType.FILE_EDIT,
                true,
                true,
                true,
                expectedTargets,
                contract.sourceEvidenceTargets(),
                contract.forbiddenTargets(),
                contract.originalUserRequest(),
                contract.mutationAllowed()
                        ? contract.classificationReason()
                        : "contextual-static-web-follow-up");
    }

    private static boolean looksContextualStaticWebAssetFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (looksDocumentGuideAboutWebSurface(lower)) return false;
        boolean restFiles = lower.contains("rest files")
                || lower.contains("remaining files")
                || lower.contains("missing files");
        boolean filesWithAssets = lower.contains("files")
                && (mentionsStyleAsset(lower) || mentionsScriptAsset(lower));
        boolean styledInteraction = mentionsStyleAsset(lower) && mentionsScriptAsset(lower);
        boolean existingSiteRewrite = (lower.contains("site")
                || lower.contains("website")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("page"))
                && (lower.contains("rewrite")
                || lower.contains("redesign")
                || lower.contains("look better")
                || lower.contains("looks better")
                || lower.contains("improve")
                || lower.contains("better"));
        return restFiles || filesWithAssets || styledInteraction || existingSiteRewrite
                || looksVagueStaticWebRedesignFollowUp(lower);
    }

    private static boolean looksVagueStaticWebRedesignFollowUp(String lower) {
        if (lower == null || lower.isBlank()) return false;
        boolean mutationPhrase = lower.contains("make it better")
                || lower.contains("look better")
                || lower.contains("looks better")
                || lower.contains("more modern")
                || lower.contains("still bad")
                || lower.contains("according to my intent")
                || lower.contains("make the changes in tailwind")
                || (lower.contains("edit") && lower.contains("better"))
                || (lower.contains("modify") && lower.contains("files"));
        if (!mutationPhrase) return false;
        return !startsLikeReadOnlyQuestion(lower);
    }

    private static boolean startsLikeReadOnlyQuestion(String lower) {
        if (lower == null) return false;
        String normalized = lower.strip();
        return normalized.startsWith("what ")
                || normalized.startsWith("why ")
                || normalized.startsWith("how ")
                || normalized.startsWith("which ")
                || normalized.startsWith("where ")
                || normalized.startsWith("when ");
    }

    private static boolean priorMessagesMentionStaticWebSurface(
            List<ChatMessage> messages,
            String latestUserRequest
    ) {
        if (messages == null || messages.isEmpty()) return false;
        int latestUserIndex = latestUserMessageIndex(messages);
        int endExclusive = latestUserIndex < 0 ? messages.size() : latestUserIndex;
        for (int i = 0; i < endExclusive; i++) {
            ChatMessage message = messages.get(i);
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String lower = message.content().toLowerCase(Locale.ROOT);
            if (mentionsStaticWebSurface(lower)
                    || lower.contains("index.html")
                    || lower.contains("style.css")
                    || lower.contains("styles.css")
                    || lower.contains("script.js")
                    || lower.contains("scripts.js")
                    || lower.contains("static web")) {
                return true;
            }
        }
        return false;
    }

    private static int latestUserMessageIndex(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (content == null || content.isBlank()
                    || ToolCallSupport.isSyntheticToolResultContent(content)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static TaskContract inheritedAssistantPlanContract(
            List<ChatMessage> messages,
            String latestUserRequest,
            TaskContract current
    ) {
        String previousAssistant = previousAssistantResponse(messages, latestUserRequest);
        if (!looksLikeConcreteMutationProposal(previousAssistant)) return null;
        Set<String> expectedTargets = extractExpectedTargets(previousAssistant);
        if (expectedTargets.isEmpty()) return null;
        Set<String> forbiddenTargets = current == null ? Set.of() : current.forbiddenTargets();
        expectedTargets = withoutForbiddenTargets(expectedTargets, forbiddenTargets);
        if (expectedTargets.isEmpty()) return null;
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                expectedTargets,
                Set.of(),
                forbiddenTargets,
                "Confirmed assistant-proposed mutation plan.\n\nConfirmation follow-up: "
                        + (latestUserRequest == null ? "" : latestUserRequest.strip()),
                "confirmation-follow-up-inherits-assistant-mutation-plan");
    }

    private static boolean looksLikeConcreteMutationProposal(String assistantResponse) {
        if (assistantResponse == null || assistantResponse.isBlank()) return false;
        String lower = assistantResponse.toLowerCase(Locale.ROOT);
        boolean asksConfirmation = lower.contains("would you like")
                || lower.contains("should i proceed")
                || lower.contains("shall i proceed")
                || lower.contains("proceed?");
        if (!asksConfirmation) return false;
        boolean mutationLanguage = lower.contains("update")
                || lower.contains("edit")
                || lower.contains("create")
                || lower.contains("write")
                || lower.contains("change")
                || lower.contains("modify");
        return mutationLanguage && !extractExpectedTargets(assistantResponse).isEmpty();
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

    private static TaskContract inheritedCorrectionContract(
            List<ChatMessage> messages,
            String latestUserRequest
    ) {
        String previousUser = previousUserRequest(messages, latestUserRequest);
        if (previousUser == null || previousUser.isBlank()) return null;

        TaskContract prior = fromUserRequest(previousUser);
        if (!prior.mutationRequested() || !prior.mutationAllowed()) return null;
        return new TaskContract(
                prior.type(),
                true,
                true,
                true,
                prior.expectedTargets(),
                prior.sourceEvidenceTargets(),
                prior.forbiddenTargets(),
                inheritedRepairOriginalRequest(previousUser, latestUserRequest),
                "correction-follow-up-inherits-previous-mutation-contract");
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

    private record FrameworkArtifactFamily(String name, List<String> artifactTargets) {}
}
