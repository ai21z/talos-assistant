package dev.talos.core.context;

import java.util.Objects;

/** Audit-only decision about how a context item was handled. */
public record ContextDecision(Action action, String reasonCode) {
    public enum Action {
        INCLUDED_IN_MODEL_PROMPT,
        WITHHELD_FROM_MODEL,
        SHOWN_LOCALLY_ONLY,
        PERSISTED_REDACTED,
        EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY,
        REFUSED_UNSUPPORTED_BOUNDARY
    }

    public ContextDecision {
        action = action == null ? Action.EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY : action;
        reasonCode = normalizeReason(reasonCode);
    }

    public static ContextDecision includedInModel(String reasonCode) {
        return new ContextDecision(Action.INCLUDED_IN_MODEL_PROMPT, reasonCode);
    }

    public static ContextDecision withheldFromModel(String reasonCode) {
        return new ContextDecision(Action.WITHHELD_FROM_MODEL, reasonCode);
    }

    public static ContextDecision shownLocallyOnly(String reasonCode) {
        return new ContextDecision(Action.SHOWN_LOCALLY_ONLY, reasonCode);
    }

    public static ContextDecision persistedRedacted(String reasonCode) {
        return new ContextDecision(Action.PERSISTED_REDACTED, reasonCode);
    }

    public static ContextDecision excludedByPrivacyOrTrustPolicy(String reasonCode) {
        return new ContextDecision(Action.EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY, reasonCode);
    }

    public static ContextDecision refusedUnsupportedBoundary(String reasonCode) {
        return new ContextDecision(Action.REFUSED_UNSUPPORTED_BOUNDARY, reasonCode);
    }

    private static String normalizeReason(String value) {
        String raw = Objects.requireNonNullElse(value, "").strip();
        if (raw.isBlank()) return "UNSPECIFIED";
        String normalized = raw.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return normalized.isBlank() ? "UNSPECIFIED" : normalized;
    }
}
