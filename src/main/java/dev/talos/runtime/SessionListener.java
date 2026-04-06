package dev.talos.runtime;

/**
 * Lifecycle listener for session events.
 *
 * <p>Implementations are registered with {@link TurnProcessor} and receive
 * callbacks after each turn completes and when the session ends. This
 * centralizes cross-cutting concerns (memory updates, audit logging,
 * transcript persistence) without touching mode code.
 *
 * <p>All methods have empty defaults so listeners can implement only
 * the hooks they care about.
 */
public interface SessionListener {

    /**
     * Called after each turn completes successfully.
     *
     * @param result    the turn result (contains rendered result, turn number, elapsed time)
     * @param userInput the raw user input that triggered this turn
     */
    default void onTurnComplete(TurnResult result, String userInput) {}

    /**
     * Called when the session is ending (user quit or programmatic close).
     * Use for resource cleanup, audit flush, transcript persistence.
     */
    default void onSessionEnd() {}
}

