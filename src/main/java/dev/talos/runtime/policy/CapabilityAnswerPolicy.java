package dev.talos.runtime.policy;

import dev.talos.runtime.toolcall.ToolAliasPolicy;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Deterministic identity/capability answers that must not inspect the workspace. */
public final class CapabilityAnswerPolicy {
    private static final String IDENTITY_ANSWER =
            "I am Talos, a local-first workspace assistant that can inspect files "
            + "and apply approved changes in this workspace.";

    private static final String CAPABILITY_ANSWER =
            "Talos can inspect this local workspace, list, read and search files, retrieve indexed context, "
            + "apply approved file/workspace changes, and run approved bounded command profiles such as "
            + "Gradle checks through talos.run_command. It uses approval, checkpointing, and verification "
            + "for workspace changes. It cannot use browser automation or inspect unsupported "
            + "binary-document contents unless those capabilities are added.";

    private static final String WORKSPACE_SWITCH_UNSUPPORTED_ANSWER =
            "Talos cannot change workspace inside the current session. Use /workspace to see the current "
                    + "workspace, then start Talos from the folder you want to work in.";

    private static final Set<String> IDENTITY_MARKERS = Set.of(
            "who are you",
            "what are you",
            "what is talos",
            "who is talos",
            "tell me what you are",
            "tell me about yourself"
    );

    private static final Set<String> CAPABILITY_MARKERS = Set.of(
            "what can you do",
            "what can you do for me",
            "what can you help me with",
            "what can you help with",
            "how can you assist me",
            "how can you help me",
            "how can you help",
            "how can talos help",
            "what can talos do",
            "what can talos help me with"
    );

    private CapabilityAnswerPolicy() {}

    public static boolean looksLikeIdentityTurn(String userRequest) {
        return containsAny(userRequest, IDENTITY_MARKERS);
    }

    public static boolean looksLikeCapabilityTurn(String userRequest) {
        return containsAny(userRequest, CAPABILITY_MARKERS);
    }

    public static boolean looksLikeToolAliasCapabilityTurn(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (!lower.contains("alias")) return false;
        boolean asksCapability = lower.contains("can talos use")
                || lower.contains("can you use")
                || lower.contains("can it use")
                || lower.contains("is this alias supported")
                || lower.contains("is that alias supported")
                || lower.contains("is the alias supported")
                || lower.contains("alias supported");
        return asksCapability && ToolAliasPolicy.firstToolAliasToken(userRequest).isPresent();
    }

    public static boolean looksLikeWorkspaceSwitchRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (!lower.contains("workspace")) return false;
        return lower.contains("change workspace")
                || lower.contains("switch workspace")
                || lower.contains("set workspace")
                || lower.contains("open workspace")
                || lower.contains("change the workspace")
                || lower.contains("change your workspace")
                || lower.contains("change its workspace")
                || lower.contains("change current workspace")
                || lower.contains("switch the workspace")
                || lower.contains("switch your workspace")
                || lower.contains("switch its workspace")
                || lower.contains("switch current workspace")
                || lower.contains("set the workspace")
                || lower.contains("set your workspace")
                || lower.contains("set its workspace")
                || lower.contains("set current workspace")
                || lower.contains("open the workspace")
                || lower.contains("use desktop as the current workspace")
                || (lower.contains("use ") && lower.contains(" as the current workspace"))
                || lower.contains("point talos at")
                || lower.contains("point you at");
    }

    public static boolean looksLikeIdentityOrCapabilityTurn(String userRequest) {
        return looksLikeIdentityTurn(userRequest)
                || looksLikeCapabilityTurn(userRequest)
                || looksLikeWorkspaceSwitchRequest(userRequest);
    }

    public static String identityAnswer() {
        return IDENTITY_ANSWER;
    }

    public static String capabilityAnswer() {
        return CAPABILITY_ANSWER;
    }

    public static String workspaceSwitchUnsupportedAnswer() {
        return WORKSPACE_SWITCH_UNSUPPORTED_ANSWER;
    }

    public static String toolAliasCapabilityAnswer(String userRequest) {
        Optional<String> maybeAlias = ToolAliasPolicy.firstToolAliasToken(userRequest);
        if (maybeAlias.isEmpty()) {
            return "That tool alias is unsupported here. Talos will not replay it or modify files from this question.";
        }
        String alias = maybeAlias.get();
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(alias);
        if (decision.accepted()) {
            String risk = decision.mutating()
                    ? "It is a mutating tool alias, so Talos can use it only inside an explicit approved edit turn."
                    : "It is a read-only tool alias.";
            return alias + " is supported here and resolves to " + decision.canonicalToolName()
                    + ". " + risk;
        }
        return alias + " is unsupported here. Talos rejects unknown provider namespaces, "
                + "will not use that alias, and will not replay it or modify files from this question.";
    }

    private static boolean containsAny(String value, Set<String> markers) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}
