package dev.talos.runtime;

import dev.talos.runtime.toolcall.ToolCallSupport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared predicate for explicit user mutation intent.
 *
 * <p>This is intentionally lexical and conservative: it should only fire when
 * the user's own prompt clearly asks for a modification. Runtime guards must
 * consult the original user request only — never assistant messages or tool
 * results.
 */
public final class MutationIntent {

    private static final String PREFIX =
            "(?:(?:ah|oh|ok(?:ay)?|right|alright|so|well|sure|yeah|yep|yup|"
                    + "cool|hey|hi|hello|hmm+),?\\s+)*";

    private static final String CORE_MUTATION_VERBS =
            "(edit|modify|change|update|fix|repair|overwrite|rewrite|replace|redesign|"
                    + "restyle|re-style|re-design|write|create|save|"
                    + "apply|add|remove|delete|move|copy|rename|mkdir|refactor|put|implement)";

    private static final String BUILD_ARTIFACT_VERBS =
            "(make|build|generate|set\\s+up|setup|scaffold)";

    private static final String ARTIFACT_NOUNS =
            "(website|site|web\\s*app|app|application|page|calculator|"
                    + "component|file|project|tool|ui|interface|stylesheet|"
                    + "style\\s*sheet|script)";

    private static final String BUILD_ARTIFACT_REQUEST =
            BUILD_ARTIFACT_VERBS + "\\s+(?:\\S+\\s+){0,10}" + ARTIFACT_NOUNS + "\\b";

    private static final String MAKE_REFERENCE_REQUEST =
            "make\\s+(?:it|this|that|the)\\b";

    private static final String DIRECTORY_CREATION_REQUEST =
            "(?:make|create)\\s+(?:me\\s+)?(?:(?:a|an)\\s+)?(?:new\\s+)?"
                    + "(?:directories|directory|dirs|dir|folders|folder)\\b";

    private static final List<Pattern> REQUEST_PATTERNS = List.of(
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?only\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?use\\s+(?:talos\\.)?"
                    + "(?:write_file|edit_file)\\s+to\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + DIRECTORY_CREATION_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + DIRECTORY_CREATION_REQUEST),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + DIRECTORY_CREATION_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + DIRECTORY_CREATION_REQUEST),
            Pattern.compile("\\b(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?"
                    + BUILD_ARTIFACT_VERBS + "\\s+me\\s+(?:\\S+\\s+){0,10}" + ARTIFACT_NOUNS + "\\b")
    );

    private static final List<Pattern> PRIOR_CHANGE_STATUS_PATTERNS = List.of(
            Pattern.compile("^" + PREFIX + "did\\s+you\\s+(?:make|apply|do|finish|complete|update|change|edit|fix|repair|write|create|save)\\b"),
            Pattern.compile("^" + PREFIX + "did\\s+(?:it|this|that|the\\s+(?:change|changes|edit|edits|fix|repair|update|updates))\\s+(?:work|apply|finish|complete)\\b"),
            Pattern.compile("^" + PREFIX + "is\\s+(?:it|this|that|the\\s+(?:change|changes|edit|edits|fix|repair|update|updates)|.{1,80})\\s+(?:done|finished|complete|completed|working)\\b"),
            Pattern.compile("^" + PREFIX + "are\\s+(?:the\\s+)?(?:change|changes|edit|edits|fix|fixes|update|updates)\\s+(?:applied|done|finished|complete|completed|working)\\b"),
            Pattern.compile("^" + PREFIX + "have\\s+you\\s+(?:made|applied|done|finished|completed|updated|changed|edited|written|created|saved)\\b"),
            Pattern.compile("^" + PREFIX + "what\\s+(?:did|have)\\s+you\\s+(?:make|made|do|done|change|changed|update|updated|edit|edited|write|written|create|created)\\b"),
            Pattern.compile("^" + PREFIX + "why\\s+did\\s+(?:nothing|not\\s+.*|.*\\s+not\\s+)\\s+(?:change|update|happen|apply)\\b"),
            Pattern.compile("^" + PREFIX + "why\\s+did\\s+you\\s+not\\s+(?:make|apply|do|update|change|edit|write|create|save)\\b")
    );

    private static final Set<String> MARKERS = Set.of(
            "edit it", "edit the", "edit this", "edit that",
            "modify it", "modify the", "modify this", "modify that",
            "change it", "change the", "change this", "change that",
            "change everything", "change all",
            "update it", "update the", "update this", "update that",
            "fix it", "fix the", "fix this", "fix that",
            "overwrite it", "overwrite the", "overwrite this",
            "rewrite it", "rewrite the", "rewrite this",
            "replace it", "replace the", "replace this",
            "redesign", "restyle", "re-style", "re-design",
            "make it ", "make the ", "make this ", "make that ",
            "write a ", "write the ", "create a ", "create the ",
            "save it", "save the",
            "apply the", "apply these", "apply those",
            "add a ", "add the ", "remove the ", "delete the ",
            "refactor ",
            "darker and more minimal"
    );

    private static final Set<String> READ_ONLY_NEGATIONS = Set.of(
            "do not change", "do not edit", "do not modify", "do not write",
            "do not create", "do not save", "do not apply", "do not touch",
            "do not mutate", "don't change", "don't edit", "don't modify",
            "don't write", "don't create", "don't save", "don't apply",
            "don't touch", "don't mutate", "dont change", "dont edit",
            "dont modify", "dont write", "dont create", "dont save",
            "dont apply", "dont touch", "dont mutate", "leave files unchanged",
            "no file changes", "without changing"
    );

    private static final Pattern NAMED_FILE_TARGET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])([A-Za-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv))"
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))");

    private static final String EXPLICIT_FILE_TARGET =
            "(?:`?(?:(?:[a-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv|pdf|doc|docx|xls|xlsx|ppt|pptx))"
                    + "|(?:(?:[a-z0-9_.\\\\/-]+/)?"
                    + "(?:readme|license|notice|changelog|contributing|authors|makefile|dockerfile))"
                    + "|(?:(?:[a-z0-9_.\\\\/-]+/)?\\.env(?:\\.[a-z0-9_.-]+)?))`?)";

    private static final String CAPTURED_FILE_TARGET =
            "`?((?:(?:[a-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv|pdf|doc|docx|xls|xlsx|ppt|pptx))"
                    + "|(?:(?:[a-z0-9_.\\\\/-]+/)?"
                    + "(?:readme|license|notice|changelog|contributing|authors|makefile|dockerfile))"
                    + "|(?:(?:[a-z0-9_.\\\\/-]+/)?\\.env(?:\\.[a-z0-9_.-]+)?)))`?";

    private static final Pattern MUTATION_VERB_WITH_FILE_TARGET = Pattern.compile(
            "\\b" + CORE_MUTATION_VERBS + "\\s+(?:only\\s+)?" + EXPLICIT_FILE_TARGET
                    + "(?=$|\\s|[`'\"),;:!?\\]])");

    private static final Pattern SUMMARIZE_SOURCE_TO_TARGET = Pattern.compile(
            "\\b(?:summarize|summarise|condense|"
                    + "write\\s+(?:a\\s+)?summary\\s+of|"
                    + "create\\s+(?:a\\s+)?summary\\s+of|"
                    + "make\\s+(?:a\\s+)?summary\\s+of)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?"
                    + CAPTURED_FILE_TARGET
                    + "\\s+(?:into|to|as|in)\\s+"
                    + "(?:the\\s+)?(?:file\\s+)?"
                    + CAPTURED_FILE_TARGET
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern READ_THEN_WRITE_SUMMARY_TO_TARGET = Pattern.compile(
            "\\b(?:read|inspect|open)\\s+(?:the\\s+)?(?:file\\s+)?"
                    + CAPTURED_FILE_TARGET
                    + ".{0,180}?\\b(?:write|create|save|put|summarize|summarise)\\b"
                    + ".{0,120}?\\b(?:summary|summarized|summarised)?\\s*"
                    + "(?:into|to|as|in)\\s+(?:the\\s+)?(?:file\\s+)?"
                    + CAPTURED_FILE_TARGET
                    + "(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BUILD_FROM_SOURCE_TO_TARGETS = Pattern.compile(
            "\\b" + BUILD_ARTIFACT_VERBS + "\\b.{0,120}?\\bfrom\\s+(?:the\\s+)?(?:file\\s+)?"
                    + CAPTURED_FILE_TARGET
                    + ".{0,200}?\\b(?:use|using)\\s+(.{1,240})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REVIEW_THEN_MUTATION_REQUEST = Pattern.compile(
            "\\b(?:review|inspect|check|diagnose|look\\s+at)\\b.{0,160}"
                    + "\\b(?:and|then)\\s+(?:please\\s+)?" + CORE_MUTATION_VERBS + "\\b");

    private static final Pattern EXPLICIT_BATCH_WORKSPACE_APPLY_REQUEST = Pattern.compile(
            "\\b(?:use\\s+)?(?:talos\\.)?apply_workspace_batch\\b.{0,160}\\bapply\\b"
                    + "|\\b(?:use\\s+)?(?:talos\\.)?apply_workspace_batch\\b.{0,160}\\b(?:create|copy|move|rename|mkdir)\\b"
                    + "|\\bapply\\s+operations_json\\b"
                    + "|\\bapply\\s+(?:these|the|exactly\\s+these)\\s+operations\\b");

    private static final Pattern ADVISORY_MUTATION_QUESTION = Pattern.compile(
            "^" + PREFIX + "(?:should|would|could|can|may)\\s+(?:i|we)\\s+"
                    + CORE_MUTATION_VERBS + "\\b");

    private static final Pattern ADVISORY_WHAT_HOW_MUTATION_QUESTION = Pattern.compile(
            "^" + PREFIX + "(?:what|how)\\s+(?:would|should|could)\\s+(?:you|i|we)\\s+"
                    + CORE_MUTATION_VERBS + "\\b");

    private static final Pattern INSTRUCTIONAL_MUTATION_QUESTION = Pattern.compile(
            "\\b(?:how\\s+to|how\\s+(?:can|could|should)\\s+(?:i|we)|"
                    + "(?:explain|show|tell)\\s+(?:me\\s+)?how\\s+to)\\s+"
                    + CORE_MUTATION_VERBS + "\\b");

    private MutationIntent() {}

    public record SourceToTargetArtifact(Set<String> sourceTargets, Set<String> outputTargets) {
        public SourceToTargetArtifact {
            sourceTargets = sourceTargets == null ? Set.of() : Set.copyOf(sourceTargets);
            outputTargets = outputTargets == null ? Set.of() : Set.copyOf(outputTargets);
        }
    }

    public static boolean looksExplicitMutationRequest(String userRequest) {
        return isExplicitMutationClassificationReason(classificationReason(userRequest));
    }

    public static String classificationReason(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return "empty-user-request";
        if (ToolCallSupport.isSyntheticToolResultContent(userRequest)) return "synthetic-tool-result";
        String lower = userRequest.toLowerCase().trim();
        if (containsGlobalReadOnlyNegation(lower)) return "global-read-only-negation";
        if (looksPriorChangeStatusQuestion(lower)) return "prior-change-status-question";
        if (looksAdvisoryMutationQuestion(lower)) return "advisory-mutation-question";
        if (looksInstructionalMutationQuestion(lower)) return "instructional-mutation-question";
        if (looksReviewThenMutationRequest(lower)) return "explicit-review-and-fix-request";
        if (looksExplicitBatchWorkspaceApplyRequest(lower)) return "explicit-batch-workspace-apply-request";
        if (sourceToTargetArtifact(userRequest).isPresent()) return "explicit-source-to-target-artifact-request";
        for (Pattern pattern : REQUEST_PATTERNS) {
            if (pattern.matcher(lower).find()) return "explicit-request-pattern";
        }
        if (looksNaturalMakeItArtifactRequest(lower)) return "natural-artifact-request";
        if (looksExplicitFileTargetMutation(lower)) return "explicit-mutation-verb-with-file-target";
        for (String marker : MARKERS) {
            if (lower.contains(marker)) return "explicit-mutation-marker";
        }
        return "non-mutating";
    }

    public static boolean isExplicitMutationClassificationReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        return reason.startsWith("explicit-") || "natural-artifact-request".equals(reason);
    }

    public static Optional<SourceToTargetArtifact> sourceToTargetArtifact(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Optional.empty();
        String request = userRequest.trim();
        Optional<SourceToTargetArtifact> direct = sourceToTargetArtifact(SUMMARIZE_SOURCE_TO_TARGET.matcher(request));
        if (direct.isPresent()) return direct;
        Optional<SourceToTargetArtifact> readThenWrite =
                sourceToTargetArtifact(READ_THEN_WRITE_SUMMARY_TO_TARGET.matcher(request));
        if (readThenWrite.isPresent()) return readThenWrite;
        return buildFromSourceToTargets(request);
    }

    public static boolean looksPriorChangeStatusQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        if (ToolCallSupport.isSyntheticToolResultContent(userRequest)) return false;
        String lower = userRequest.toLowerCase().trim();
        if (containsConditionalApplyClause(lower)) return false;
        for (Pattern pattern : PRIOR_CHANGE_STATUS_PATTERNS) {
            if (pattern.matcher(lower).find()) return true;
        }
        return false;
    }

    private static boolean containsConditionalApplyClause(String lower) {
        return Pattern.compile("\\b(?:if\\s+not|otherwise|then)\\b.{0,80}\\b"
                + "(?:fix|repair|update|change|edit|make|create|write|apply)\\b").matcher(lower).find();
    }

    private static boolean looksNaturalMakeItArtifactRequest(String lower) {
        if (!lower.contains("can you make it")
                && !lower.contains("could you make it")
                && !lower.contains("would you make it")
                && !lower.contains("will you make it")) {
            return false;
        }
        return Pattern.compile("\\b" + ARTIFACT_NOUNS + "\\b").matcher(lower).find()
                && (lower.contains(" here")
                || lower.contains("folder")
                || lower.contains("file")
                || lower.contains("open and use")
                || lower.contains("i just want"));
    }

    private static boolean looksExplicitFileTargetMutation(String lower) {
        return lower != null && MUTATION_VERB_WITH_FILE_TARGET.matcher(lower).find();
    }

    private static Optional<SourceToTargetArtifact> sourceToTargetArtifact(Matcher matcher) {
        if (matcher == null || !matcher.find()) return Optional.empty();
        String source = normalizeArtifactPath(matcher.group(1));
        String output = normalizeArtifactPath(matcher.group(2));
        if (source.isBlank() || output.isBlank() || source.equals(output)) return Optional.empty();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        sources.add(source);
        outputs.add(output);
        return Optional.of(new SourceToTargetArtifact(sources, outputs));
    }

    private static Optional<SourceToTargetArtifact> buildFromSourceToTargets(String request) {
        Matcher matcher = BUILD_FROM_SOURCE_TO_TARGETS.matcher(request);
        if (!matcher.find()) return Optional.empty();
        String source = normalizeArtifactPath(matcher.group(2));
        if (source.isBlank()) return Optional.empty();

        LinkedHashSet<String> outputs = new LinkedHashSet<>();
        Matcher outputMatcher = NAMED_FILE_TARGET.matcher(matcher.group(3));
        while (outputMatcher.find()) {
            String output = normalizeArtifactPath(outputMatcher.group(1));
            if (!output.isBlank() && !output.equals(source)) {
                outputs.add(output);
            }
        }
        if (outputs.isEmpty()) return Optional.empty();

        LinkedHashSet<String> sources = new LinkedHashSet<>();
        sources.add(source);
        return Optional.of(new SourceToTargetArtifact(sources, outputs));
    }

    private static String normalizeArtifactPath(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = ToolCallSupport.normalizePath(value).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && ".,;:!?)]}".indexOf(normalized.charAt(normalized.length() - 1)) >= 0) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean looksReviewThenMutationRequest(String lower) {
        return lower != null && REVIEW_THEN_MUTATION_REQUEST.matcher(lower).find();
    }

    private static boolean looksExplicitBatchWorkspaceApplyRequest(String lower) {
        return lower != null && EXPLICIT_BATCH_WORKSPACE_APPLY_REQUEST.matcher(lower).find();
    }

    private static boolean looksAdvisoryMutationQuestion(String lower) {
        return lower != null
                && (ADVISORY_MUTATION_QUESTION.matcher(lower).find()
                || ADVISORY_WHAT_HOW_MUTATION_QUESTION.matcher(lower).find());
    }

    private static boolean looksInstructionalMutationQuestion(String lower) {
        return lower != null && INSTRUCTIONAL_MUTATION_QUESTION.matcher(lower).find();
    }

    private static boolean containsGlobalReadOnlyNegation(String lower) {
        for (String marker : READ_ONLY_NEGATIONS) {
            int start = lower.indexOf(marker);
            while (start >= 0) {
                if (!isScopedLimiter(lower, start, marker)) return true;
                start = lower.indexOf(marker, start + marker.length());
            }
        }
        return false;
    }

    /**
     * Returns true for no-other-target limiters, not no-mutation instructions.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "do not modify anything else"} limits the requested edit.</li>
     *   <li>{@code "do not edit any other files"} limits the requested edit.</li>
     *   <li>{@code "do not modify anything"} is still a global read-only guard.</li>
     * </ul>
     */
    private static boolean isScopedLimiter(String lower, int markerStart, String marker) {
        String tail = lower.substring(markerStart + marker.length()).stripLeading();
        tail = tail.replaceFirst("^[\\p{Punct}\\s]+", "").stripLeading();
        return tail.startsWith("anything else")
                || tail.startsWith("everything else")
                || tail.startsWith("anything outside")
                || tail.startsWith("anything beyond")
                || tail.startsWith("any other")
                || tail.startsWith("other file")
                || tail.startsWith("other files")
                || tail.startsWith("other parts")
                || tail.startsWith("other things")
                || tail.startsWith("private file")
                || tail.startsWith("private files")
                || tail.startsWith("protected file")
                || tail.startsWith("protected files")
                || tail.startsWith("secret file")
                || tail.startsWith("secret files")
                || tail.startsWith("secrets")
                || tail.startsWith("credentials")
                || tail.startsWith("else")
                || startsWithNamedFileTarget(tail);
    }

    private static boolean startsWithNamedFileTarget(String tail) {
        if (tail == null || tail.isBlank()) return false;
        var matcher = NAMED_FILE_TARGET.matcher(tail);
        return matcher.find() && matcher.start() <= 4;
    }
}
