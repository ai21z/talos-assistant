package dev.talos.runtime.policy;

import java.util.List;

/** Decides when prompt construction may preload workspace context. */
public final class PromptWorkspaceContextPolicy {
    private PromptWorkspaceContextPolicy() {}

    public static boolean includeWorkspaceManifest(List<String> visibleToolNames) {
        return visibleToolNames != null && !visibleToolNames.isEmpty();
    }
}
