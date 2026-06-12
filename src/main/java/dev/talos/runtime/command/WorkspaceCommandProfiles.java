package dev.talos.runtime.command;

import java.util.List;
import java.util.Objects;

/**
 * The parsed workspace verification-profile declaration
 * ({@code <workspace>/.talos/profiles.yaml}). Either a list of valid
 * {@code ws:}-prefixed profiles, or one human-readable rejection reason —
 * never a partial mix: one bad profile poisons the whole file, which is
 * simpler to reason about than partial trust.
 */
public record WorkspaceCommandProfiles(
        boolean declared,
        List<CommandProfile> profiles,
        String rejectionReason
) {
    public WorkspaceCommandProfiles {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        rejectionReason = Objects.toString(rejectionReason, "");
        if (!rejectionReason.isBlank() && !profiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "a rejected declaration must carry zero profiles");
        }
    }

    /** No declaration file in the workspace. */
    public static WorkspaceCommandProfiles none() {
        return new WorkspaceCommandProfiles(false, List.of(), "");
    }

    /** A declaration exists but failed validation — zero profiles register. */
    public static WorkspaceCommandProfiles invalid(String reason) {
        return new WorkspaceCommandProfiles(true, List.of(),
                reason == null || reason.isBlank() ? "invalid declaration" : reason);
    }

    /** A valid declaration. */
    public static WorkspaceCommandProfiles of(List<CommandProfile> profiles) {
        return new WorkspaceCommandProfiles(true, profiles, "");
    }

    public boolean valid() {
        return declared && rejectionReason.isBlank();
    }
}
