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
 * using {@link #extractText(Result)}, which handles all text-carrying
 * result types — including {@link Result.Streamed} (the primary streaming
 * path) and {@link Result.Ok} (non-streaming / tool-call fallback).
 */
public final class MemoryUpdateListener implements SessionListener {

    private final ConversationManager conversationManager;

    public MemoryUpdateListener(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public void onTurnComplete(TurnResult result, String userInput) {
        if (result == null || userInput == null || userInput.isBlank()) return;

        String answer = extractText(result.result());
        if (answer != null && !answer.isBlank()) {
            conversationManager.addTurn(userInput, answer.strip());
        }
    }

    /**
     * Extracts memorizable text from a Result.
     *
     * <p>Only LLM response types are memorized:
     * <ul>
     *   <li>{@link Result.Ok}       — non-streamed LLM answers (tool-call fallback, non-interactive)</li>
     *   <li>{@link Result.Streamed}  — streamed LLM answers (primary path; uses fullText, excludes suffix)</li>
     * </ul>
     *
     * <p>System messages (Info, TrustedInfo), errors, tables, and streaming lifecycle
     * markers are NOT memorized — they are not conversational exchanges.
     *
     * @param r the result to extract text from
     * @return the text content, or null if the result type is not memorizable
     */
    static String extractText(Result r) {
        if (r == null) return null;
        return switch (r) {
            case Result.Ok ok           -> ok.text;
            case Result.Streamed s      -> s.fullText;
            case Result.Info ignored     -> null;
            case Result.TrustedInfo ignored -> null;
            case Result.Error ignored   -> null;
            case Result.Table ignored   -> null;
            case Result.StreamStart ignored  -> null;
            case Result.StreamChunk ignored  -> null;
            case Result.StreamEnd ignored    -> null;
        };
    }
}

