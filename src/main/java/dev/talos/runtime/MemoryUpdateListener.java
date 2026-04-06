package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.core.context.ConversationManager;

/**
 * SessionListener that centralizes memory updates after each turn.
 *
 * <p>Replaces the ad-hoc {@code ctx.memory().update()} calls that were
 * scattered across AskMode and RagMode. Now TurnProcessor fires this
 * listener after every successful turn, and it records the user input
 * and the assistant's response in the ConversationManager.
 *
 * <p>The assistant response is extracted from the {@link TurnResult}
 * by taking the text content of the rendered result.
 */
public final class MemoryUpdateListener implements SessionListener {

    private final ConversationManager conversationManager;

    public MemoryUpdateListener(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public void onTurnComplete(TurnResult result, String userInput) {
        if (result == null || userInput == null || userInput.isBlank()) return;

        Result r = result.result();
        if (r instanceof Result.Ok ok) {
            String answer = ok.toString();
            if (answer != null && !answer.isBlank()) {
                conversationManager.addTurn(userInput, answer.strip());
            }
        }
    }
}

