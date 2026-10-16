package dev.talos.runtime;

import java.util.List;
import java.util.UUID;

/** Session-only historical associations do not establish complete project scope. */
public record TypedResultHistory(List<Turn> turns, int evictedTurns, boolean conversationPresent) {
    public record Turn(UUID lifetimeId, int turnNumber, TypedResultSettlement settlement) {}
    public TypedResultHistory { turns = List.copyOf(turns); }
    public boolean scopeEstablished() { return false; }
}
