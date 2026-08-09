package dev.talos.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Fail-closed inventory of physical turn logs for one workspace.
 *
 * <p>Only {@link State#EXACT} may authorize cross-session diagnostic
 * selection. An unavailable inventory exposes no partial identities.
 */
public record TurnLogInventoryObservation(
        State state,
        List<String> sessionIds
) {
    public enum State {
        EXACT,
        UNAVAILABLE
    }

    public TurnLogInventoryObservation {
        state = state == null ? State.UNAVAILABLE : state;
        if (state == State.EXACT) {
            sessionIds = List.copyOf(Objects.requireNonNull(
                    sessionIds,
                    "exact inventory requires an observed list"));
            if (sessionIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || new java.util.LinkedHashSet<>(sessionIds).size()
                            != sessionIds.size()) {
                throw new IllegalArgumentException(
                        "exact inventory identities must be nonblank and unique");
            }
        } else {
            sessionIds = List.of();
        }
    }

    public static TurnLogInventoryObservation exact(List<String> sessionIds) {
        return new TurnLogInventoryObservation(State.EXACT, sessionIds);
    }

    public static TurnLogInventoryObservation unavailable() {
        return new TurnLogInventoryObservation(State.UNAVAILABLE, List.of());
    }

    public boolean exact() {
        return state == State.EXACT;
    }
}
