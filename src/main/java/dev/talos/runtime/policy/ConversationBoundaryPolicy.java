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
            "hello friend, how are you?",
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
            "without inspecting files",
            "without using this workspace",
            "without using the workspace",
            "without using workspace",
            "without inspecting or using this workspace",
            "without inspecting or using the workspace",
            "without inspecting or using workspace"
    );

    private static final Pattern POSITIVE_FILE_ACTION = Pattern.compile(
            ".*\\b(?:create|edit|modify|change|update|fix|repair|overwrite|rewrite|replace|write|"
                    + "save|apply|add|remove|delete|refactor|read|inspect|search|list|show|"
                    + "explain|summarize|summary|describe)\\b"
                    + ".{0,80}\\b[\\w./\\\\-]+\\.(?:html|htm|css|js|jsx|ts|tsx|java|md|txt|json|"
                    + "yaml|yml|xml|properties|gradle|kts|toml|ini|env|csv)\\b.*");

    private static final Pattern POSITIVE_WORKSPACE_INSPECTION = Pattern.compile(
            ".*\\b(?:read|open|inspect|diagnose|search|grep|find|list|show)\\b"
                    + ".{0,80}\\b(?:this\\s+|the\\s+|my\\s+)?"
                    + "(?:repo|repository|workspace|project)\\b.*");
    private static final Pattern POSITIVE_WORKSPACE_EXPLANATION = Pattern.compile(
            ".*\\b(?:explain|summarize|describe)\\b.{0,80}\\b(?:this|the|my)\\s+"
                    + "(?:repo|repository|workspace|project)\\b.*");

    private static final String WORKSPACE_ACCESS_VERB =
            "(?:access(?:ing)?|open(?:ing)?|read(?:ing)?|inspect(?:ing)?|search(?:ing)?|"
                    + "list(?:ing)?|check(?:ing)?|use|using|scan(?:ning)?|grep(?:ping)?)";
    private static final String GENERIC_WORKSPACE_OBJECT =
            "(?:(?:workspace|repo|repository|codebase|project|folder|directory)"
                    + "(?:\\s+(?:files?|contents?))?|files?)";
    private static final Pattern BLANKET_NO_WORKSPACE_ACCESS = Pattern.compile(
            "\\b(?:do\\s+not|don't|dont|without)\\s+(?:please\\s+)?"
                    + WORKSPACE_ACCESS_VERB
                    + "(?:\\s+(?:or|and)\\s+" + WORKSPACE_ACCESS_VERB + ")*\\s+"
                    + "(?:(?:any|all|my|our|your|the|this|these|those|local)\\s+)?"
                    + GENERIC_WORKSPACE_OBJECT + "\\b");

    private static final Pattern NEAR_SLASH_COMMAND = Pattern.compile(
            "(?:"
                    + "debug\\s+/?trace|"
                    + "last\\s+/?trace|"
                    + "show\\s+(?:me\\s+)?(?:the\\s+)?last\\s+trace|"
                    + "show\\s+/?trace|"
                    + ".*\\bwhat\\s+command\\s+shows?\\b.{0,80}\\blast\\s+/?trace\\b.*"
                    + ")");

    private static final Pattern FRIENDLY_HOW_ARE_YOU = Pattern.compile(
            "^\\s*(?:hi|hello|hey|hey\\s+there|hello\\s+there|yo)\\b.{0,120}\\bhow\\s+are\\s+you\\b.*");

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
        if (isBlanketNoWorkspaceRequest(normalized) && !explicitMutation) {
            return Classification.PRIVACY_NO_WORKSPACE;
        }
        if (NEAR_SLASH_COMMAND.matcher(stripTerminalPunctuation(normalized)).matches()) {
            return Classification.NEAR_SLASH_COMMAND;
        }
        if (explicitMutation || hasWorkspaceIntent(normalized)) {
            return Classification.NONE;
        }
        if (DIRECT_CHAT_PROMPTS.contains(normalized)
                || FRIENDLY_HOW_ARE_YOU.matcher(normalized).matches()) {
            return Classification.DIRECT_CHAT;
        }
        return Classification.NONE;
    }

    public static boolean isDirectAnswerOnly(String userRequest) {
        return classification(userRequest) != Classification.NONE;
    }

    /** Returns true only when the request denies workspace access as a whole. */
    public static boolean isBlanketNoWorkspaceRequest(String userRequest) {
        String normalized = normalize(userRequest);
        if (normalized.isEmpty() || MutationIntent.looksExplicitMutationRequest(userRequest)) return false;
        String residual = removeBlanketNoWorkspaceClauses(normalized);
        if (residual.equals(normalized)) return false;
        return !hasPositiveWorkspaceAction(residual);
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
        return containsAny(normalized, POSITIVE_WORKSPACE_ACTION_MARKERS)
                || POSITIVE_FILE_ACTION.matcher(normalized).matches()
                || POSITIVE_WORKSPACE_INSPECTION.matcher(normalized).matches()
                || POSITIVE_WORKSPACE_EXPLANATION.matcher(normalized).matches();
    }

    private static String removeBlanketNoWorkspaceClauses(String normalized) {
        String out = normalized == null ? "" : normalized;
        for (String marker : PRIVACY_NO_WORKSPACE_MARKERS) {
            out = out.replace(marker, " ");
        }
        out = BLANKET_NO_WORKSPACE_ACCESS.matcher(out).replaceAll(" ");
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
