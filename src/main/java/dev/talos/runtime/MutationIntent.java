package dev.talos.runtime;

import dev.talos.runtime.toolcall.ToolCallSupport;

import java.util.List;
import java.util.Set;
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
            "(?:edit|modify|change|update|fix|rewrite|replace|redesign|"
                    + "restyle|re-style|re-design|write|create|save|"
                    + "apply|add|remove|delete|refactor|put|implement)";

    private static final String BUILD_ARTIFACT_VERBS =
            "(?:make|build|generate|set\\s+up|setup|scaffold)";

    private static final String ARTIFACT_NOUNS =
            "(?:website|site|web\\s*app|app|application|page|calculator|"
                    + "component|file|project|tool|ui|interface|stylesheet|"
                    + "style\\s*sheet|script)";

    private static final String BUILD_ARTIFACT_REQUEST =
            BUILD_ARTIFACT_VERBS + "\\s+(?:\\S+\\s+){0,10}" + ARTIFACT_NOUNS + "\\b";

    private static final String MAKE_REFERENCE_REQUEST =
            "make\\s+(?:it|this|that|the)\\b";

    private static final List<Pattern> REQUEST_PATTERNS = List.of(
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?only\\s+" + CORE_MUTATION_VERBS + "\\b"),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + BUILD_ARTIFACT_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:please\\s+)?(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "i\\s+(?:want|need)\\s+you\\s+to\\s+" + MAKE_REFERENCE_REQUEST),
            Pattern.compile("^" + PREFIX + "(?:now\\s+)?(?:let's|lets)\\s+" + MAKE_REFERENCE_REQUEST)
    );

    private static final Set<String> MARKERS = Set.of(
            "edit it", "edit the", "edit this", "edit that",
            "modify it", "modify the", "modify this", "modify that",
            "change it", "change the", "change this", "change that",
            "change everything", "change all",
            "update it", "update the", "update this", "update that",
            "fix it", "fix the", "fix this", "fix that",
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

    private MutationIntent() {}

    public static boolean looksExplicitMutationRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        if (ToolCallSupport.isSyntheticToolResultContent(userRequest)) return false;
        String lower = userRequest.toLowerCase().trim();
        if (containsGlobalReadOnlyNegation(lower)) return false;
        for (Pattern pattern : REQUEST_PATTERNS) {
            if (pattern.matcher(lower).find()) return true;
        }
        for (String marker : MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
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
                || tail.startsWith("else");
    }
}
