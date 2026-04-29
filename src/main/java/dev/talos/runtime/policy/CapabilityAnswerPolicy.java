package dev.talos.runtime.policy;

import java.util.Locale;
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

    public static boolean looksLikeIdentityOrCapabilityTurn(String userRequest) {
        return looksLikeIdentityTurn(userRequest) || looksLikeCapabilityTurn(userRequest);
    }

    public static String identityAnswer() {
        return IDENTITY_ANSWER;
    }

    public static String capabilityAnswer() {
        return CAPABILITY_ANSWER;
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
