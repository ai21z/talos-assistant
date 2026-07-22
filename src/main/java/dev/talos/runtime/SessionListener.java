package dev.talos.runtime;

/**
 * Lifecycle listener for session events (turn completion, session end).
 * Registered with TurnProcessor. All methods have empty defaults.
 */
public interface SessionListener {

    /**
     * Called after an allocated turn reaches its terminal settlement snapshot.
     * Legacy listeners receive ordinary completions only, so failed/no-result
     * turns cannot enter conversation memory or task state accidentally.
     */
    default TurnPublicationReceipt onTurnSettled(
            TurnSettlement settlement,
            String userInput
    ) {
        if (settlement == null || !settlement.ordinaryCompletion()) {
            return TurnPublicationReceipt.skipped();
        }
        onTurnComplete(settlement.result(), userInput);
        return TurnPublicationReceipt.complete();
    }

    /** Called after each turn completes successfully. */
    default void onTurnComplete(TurnResult result, String userInput) {}

    /** Called when the session is ending (user quit or programmatic close). */
    default void onSessionEnd() {}
}

