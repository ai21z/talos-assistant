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
            + "and apply file changes only after approval. It uses approval, checkpointing, and verification "
            + "for workspace changes, and cannot use browser, shell, or unsupported binary-document tools "
            + "unless those capabilities are added.";

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

    public static boolean looksLikeIdentityOrCapabilityTurn(String userRequest) {
        return looksLikeIdentityTurn(userRequest) || looksLikeCapabilityTurn(userRequest);
    }

    public static String identityAnswer() {
        return IDENTITY_ANSWER;
    }

    public static String capabilityAnswer() {
        return CAPABILITY_ANSWER;
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
