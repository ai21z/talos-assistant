package dev.talos.runtime;

/**
 * Lifecycle listener for session events (turn completion, session end).
 * Registered with TurnProcessor. All methods have empty defaults.
 */
public interface SessionListener {

    /** Called after each turn completes successfully. */
    default void onTurnComplete(TurnResult result, String userInput) {}

    /** Called when the session is ending (user quit or programmatic close). */
    default void onSessionEnd() {}
}

