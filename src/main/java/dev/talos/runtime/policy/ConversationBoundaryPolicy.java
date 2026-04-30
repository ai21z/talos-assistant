package dev.talos.runtime.policy;

import dev.talos.runtime.MutationIntent;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Classifies conversation-only turns that must not inspect or mutate the workspace. */
public final class ConversationBoundaryPolicy {
    private static final String NEAR_SLASH_COMMAND_ANSWER =
            "Use `/last trace` to show the most recent trace.";

    private static final Set<String> DIRECT_CHAT_PROMPTS = Set.of(
            "hello friend",
            "how are you are you good?",
            "perfect just as i want it!",
            "thanks, that is perfect",
            "looks good"
    );

    private static final Set<String> WORKSPACE_INTENT_MARKERS = Set.of(
            "workspace",
            "repo",
            "repository",
            "read ",
            "inspect ",
            "search ",
            "list ",
            "show files",
            "what files",
            "my files",
            "this folder",
            "the folder",
            "notes.md"
    );

    private static final Set<String> POSITIVE_WORKSPACE_ACTION_MARKERS = Set.of(
            "what is in this workspace",
            "what's in this workspace",
            "what is in the repo",
            "what is in this repo",
            "what is in the repository",
            "show repository structure",
            "show the repository structure",
            "search ",
            "list ",
            "show files",
            "what files"
    );

    private static final Set<String> PRIVACY_NO_WORKSPACE_MARKERS = Set.of(
            "only chatting",
            "just chat",
            "don't inspect my files",
            "dont inspect my files",
            "do not inspect my files",
            "don't inspect the files",
            "dont inspect the files",
            "do not inspect the files",
            "do not inspect files",
            "don't read my files",
            "dont read my files",
            "do not read files",
            "do not read my files",
            "don't search my files",
            "dont search my files",
            "do not search my files",
            "no workspace access",
            "no workspace",
            "don't use the workspace",
            "dont use the workspace",
            "do not use the workspace",
            "don't use workspace",
            "dont use workspace",
            "do not use workspace",
            "no file access",
            "just answer, no workspace",
            "without reading files",
            "without checking files",
            "without searching files",
            "without inspecting files"
    );

    private static final Pattern POSITIVE_FILE_ACTION = Pattern.compile(
            ".*\\b(?:create|edit|modify|change|update|fix|repair|overwrite|rewrite|replace|write|"
                    + "save|apply|add|remove|delete|refactor|read|inspect|search|list|show|"
                    + "explain|summarize|summary|describe)\\b"
                    + ".{0,80}\\b[\\w./\\\\-]+\\.(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|"
                    + "yaml|yml|xml|properties|gradle|kts|toml|ini|env|csv)\\b.*");

    private static final Pattern POSITIVE_WORKSPACE_INSPECTION = Pattern.compile(
            ".*\\b(?:read|inspect|diagnose)\\b.{0,80}\\b(?:this\\s+)?"
                    + "(?:repo|repository|workspace|project)\\b.*");

    private static final Pattern NEAR_SLASH_COMMAND = Pattern.compile(
            "(?:"
                    + "debug\\s+/?trace|"
                    + "last\\s+/?trace|"
                    + "show\\s+(?:me\\s+)?(?:the\\s+)?last\\s+trace|"
                    + "show\\s+/?trace"
                    + ")");

    private static final Pattern POSITIVE_WORKSPACE_QUERY = Pattern.compile(
            ".*(?:"
                    + "\\bwhat(?:'s|\\s+is)\\s+in\\s+(?:this\\s+|the\\s+)?"
                    + "(?:repo|repository|workspace|project|folder|directory)\\b"
                    + "|\\bshow\\b.{0,80}\\b(?:repo|repository|workspace|project|folder|directory)\\b"
                    + ".{0,80}\\b(?:structure|tree|files|contents|entries)\\b"
                    + "|\\b(?:read|inspect|diagnose|explain|summarize|search|grep|find|list|show)\\b"
                    + ".{0,80}\\b(?:repo|repository|workspace|project|folder|directory|files?)\\b"
                    + ").*");

    private ConversationBoundaryPolicy() {}

    public enum Classification {
        NONE,
        DIRECT_CHAT,
        PRIVACY_NO_WORKSPACE,
        NEAR_SLASH_COMMAND
    }

    public static Classification classification(String userRequest) {
        String normalized = normalize(userRequest);
        if (normalized.isEmpty()) return Classification.NONE;
        boolean explicitMutation = MutationIntent.looksExplicitMutationRequest(userRequest);
        boolean positiveWorkspaceAction = hasPositiveWorkspaceAction(normalized);
        if (containsAny(normalized, PRIVACY_NO_WORKSPACE_MARKERS)
                && !explicitMutation
                && !positiveWorkspaceAction) {
            return Classification.PRIVACY_NO_WORKSPACE;
        }
        if (NEAR_SLASH_COMMAND.matcher(stripTerminalPunctuation(normalized)).matches()) {
            return Classification.NEAR_SLASH_COMMAND;
        }
        if (explicitMutation || hasWorkspaceIntent(normalized)) {
            return Classification.NONE;
        }
        if (DIRECT_CHAT_PROMPTS.contains(normalized)) {
            return Classification.DIRECT_CHAT;
        }
        return Classification.NONE;
    }

    public static boolean isDirectAnswerOnly(String userRequest) {
        return classification(userRequest) != Classification.NONE;
    }

    public static String deterministicAnswer(String userRequest) {
        if (classification(userRequest) == Classification.NEAR_SLASH_COMMAND) {
            return NEAR_SLASH_COMMAND_ANSWER;
        }
        return null;
    }

    private static String normalize(String userRequest) {
        if (userRequest == null) return "";
        return userRequest.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripTerminalPunctuation(String normalized) {
        if (normalized == null) return "";
        return normalized.replaceAll("[.!?]+$", "");
    }

    private static boolean hasWorkspaceIntent(String normalized) {
        if (containsFileName(normalized)) return true;
        return containsAny(normalized, WORKSPACE_INTENT_MARKERS);
    }

    private static boolean hasPositiveWorkspaceAction(String normalized) {
        String positiveSpan = removePrivacyNoWorkspaceMarkers(normalized);
        return containsAny(positiveSpan, POSITIVE_WORKSPACE_ACTION_MARKERS)
                || POSITIVE_FILE_ACTION.matcher(positiveSpan).matches()
                || POSITIVE_WORKSPACE_INSPECTION.matcher(positiveSpan).matches()
                || POSITIVE_WORKSPACE_QUERY.matcher(positiveSpan).matches();
    }

    private static String removePrivacyNoWorkspaceMarkers(String normalized) {
        String out = normalized == null ? "" : normalized;
        for (String marker : PRIVACY_NO_WORKSPACE_MARKERS) {
            out = out.replace(marker, " ");
        }
        return out.replaceAll("\\s+", " ").strip();
    }

    private static boolean containsFileName(String normalized) {
        return normalized.matches(".*\\b[\\w./\\\\-]+\\.(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|yaml|yml|xml|properties|gradle|kts|toml|ini|env|csv)\\b.*");
    }

    private static boolean containsAny(String normalized, Set<String> markers) {
        for (String marker : markers) {
            if (normalized.contains(marker)) return true;
        }
        return false;
    }
}
