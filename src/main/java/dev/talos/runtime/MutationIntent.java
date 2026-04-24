package dev.talos.runtime;

import dev.talos.runtime.toolcall.ToolCallSupport;

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

    private static final java.util.List<Pattern> REQUEST_PATTERNS = java.util.List.of(
            Pattern.compile("^(?:please\\s+)?(?:edit|modify|change|update|fix|rewrite|replace|redesign|restyle|re-style|re-design|make|write|create|save|apply|add|remove|delete|refactor)\\b"),
            Pattern.compile("^(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?(?:edit|modify|change|update|fix|rewrite|replace|redesign|restyle|re-style|re-design|make|write|create|save|apply|add|remove|delete|refactor)\\b"),
            Pattern.compile("^i\\s+(?:want|need)\\s+you\\s+to\\s+(?:edit|modify|change|update|fix|rewrite|replace|redesign|restyle|re-style|re-design|make|write|create|save|apply|add|remove|delete|refactor)\\b"),
            Pattern.compile("^(?:let's|lets)\\s+(?:edit|modify|change|update|fix|rewrite|replace|redesign|restyle|re-style|re-design|make|write|create|save|apply|add|remove|delete|refactor)\\b")
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

    private MutationIntent() {}

    public static boolean looksExplicitMutationRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        if (ToolCallSupport.isSyntheticToolResultContent(userRequest)) return false;
        String lower = userRequest.toLowerCase().trim();
        for (Pattern pattern : REQUEST_PATTERNS) {
            if (pattern.matcher(lower).find()) return true;
        }
        for (String marker : MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}
