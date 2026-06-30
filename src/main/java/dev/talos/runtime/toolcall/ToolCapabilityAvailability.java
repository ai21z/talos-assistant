package dev.talos.runtime.toolcall;

import dev.talos.core.Config;
import dev.talos.core.privacy.PrivacyConfigFacts;
import dev.talos.tools.ToolOperationMetadata;

/** Deterministic per-turn availability for capability-gated tools. */
public record ToolCapabilityAvailability(boolean retrievalAvailable) {

    public static ToolCapabilityAvailability allEnabled() {
        return new ToolCapabilityAvailability(true);
    }

    public static ToolCapabilityAvailability fromConfig(Config cfg) {
        return new ToolCapabilityAvailability(PrivacyConfigFacts.ragEnabledInPrivateMode(cfg));
    }

    boolean allows(ToolOperationMetadata metadata) {
        if (metadata == null) return true;
        return switch (metadata.capabilityKind()) {
            case RETRIEVE -> retrievalAvailable;
            default -> true;
        };
    }
}
