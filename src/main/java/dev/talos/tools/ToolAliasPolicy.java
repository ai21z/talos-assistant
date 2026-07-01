package dev.talos.tools;

import dev.talos.core.tool.ToolNamePolicy;

import java.util.Optional;

/** Compatibility facade for canonical Talos tool names and accepted model/backend aliases. */
public final class ToolAliasPolicy {
    private ToolAliasPolicy() {}

    public enum AliasDecisionStatus {
        CANONICAL,
        ACCEPTED_ALIAS,
        REJECTED_UNKNOWN_NAMESPACE,
        UNKNOWN
    }

    public record Decision(
            String rawName,
            String canonicalToolName,
            AliasDecisionStatus status,
            BackendToolProfile profile
    ) {
        public boolean accepted() {
            return status == AliasDecisionStatus.CANONICAL
                    || status == AliasDecisionStatus.ACCEPTED_ALIAS;
        }

        public boolean traceWorthy() {
            return status == AliasDecisionStatus.ACCEPTED_ALIAS
                    || status == AliasDecisionStatus.REJECTED_UNKNOWN_NAMESPACE;
        }

        public boolean readOnly() {
            return ToolNamePolicy.isReadOnly(canonicalToolName);
        }

        public boolean mutating() {
            return ToolNamePolicy.isMutating(canonicalToolName);
        }

        public String localCanonicalName() {
            return ToolNamePolicy.localCanonicalName(canonicalToolName);
        }
    }

    public static Decision resolve(String rawName) {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(rawName);
        return new Decision(
                decision.rawName(),
                decision.canonicalToolName(),
                AliasDecisionStatus.valueOf(decision.status().name()),
                BackendToolProfile.valueOf(decision.profile().name()));
    }

    public static boolean isReadOnly(String rawName) {
        return ToolNamePolicy.isReadOnly(rawName);
    }

    public static boolean isMutating(String rawName) {
        return ToolNamePolicy.isMutating(rawName);
    }

    public static String localCanonicalName(String rawName) {
        return ToolNamePolicy.localCanonicalName(rawName);
    }

    public static Optional<String> firstToolAliasToken(String text) {
        return ToolNamePolicy.firstToolAliasToken(text);
    }

    public static String normalizeTalosSeparator(String rawName) {
        return ToolNamePolicy.normalizeTalosSeparator(rawName);
    }
}
