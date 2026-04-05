package dev.loqj.cli.repl;

import dev.loqj.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal rolling-window session memory for conversational context.
 * Extracted from {@code RagService} where it did not belong — session memory
 * is a CLI/REPL concern, not a knowledge-engine concern.
 *
 * <p>Stores a rolling text window of recent user inputs and answers,
 * capped at {@link #MAX_CHARS} characters. Oldest content is trimmed
 * from the front when the window overflows.
 *
 * <p>Also maintains a parallel structured list of {@link ChatMessage}
 * turns for use with the {@code /api/chat} conversation endpoint.
 * When the flat buffer overflows, the oldest structured turns are
 * also pruned to stay in sync.
 *
 * <p>Thread-safe: all methods synchronize on the instance.
 */
public final class SessionMemory {

    /**
     * Maximum characters retained in the legacy rolling text window.
     * Generous budget — the structured turns list is the primary constraint;
     * this only caps the backward-compatible flat buffer.
     */
    public static final int MAX_CHARS = 64_000;

    /**
     * Maximum number of structured ChatMessage entries retained.
     * 200 entries = 100 user/assistant exchanges — enough for long sessions
     * while staying well within typical model context windows.
     */
    private static final int MAX_TURNS = 200;

    private String buffer;
    private final List<ChatMessage> turns = new ArrayList<>();

    public SessionMemory() {
        this.buffer = null;
    }

    /** Returns the current memory content, or null if empty. */
    public synchronized String get() {
        return buffer;
    }

    /** Returns an unmodifiable list of structured conversation turns. */
    public synchronized List<ChatMessage> getTurns() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    /** Clears all memory. */
    public synchronized void clear() {
        buffer = null;
        turns.clear();
    }

    /** Returns true if memory has content. */
    public synchronized boolean hasContent() {
        return buffer != null && !buffer.isEmpty();
    }

    /**
     * Appends a user input + answer pair to the rolling memory window.
     * Trims from the front if the result exceeds {@link #MAX_CHARS}.
     *
     * @param userInput the user's input text
     * @param answer    the system's response text
     */
    public synchronized void update(String userInput, String answer) {
        // Flat buffer (backward-compatible)
        String entry = userInput + "\n" + answer;
        String s = (buffer == null ? "" : buffer + "\n") + entry;
        if (s.length() > MAX_CHARS) {
            s = s.substring(s.length() - MAX_CHARS);
        }
        buffer = s;

        // Structured turns
        turns.add(ChatMessage.user(userInput));
        turns.add(ChatMessage.assistant(answer));
        // Prune oldest turns (remove in pairs) if we exceed the limit
        while (turns.size() > MAX_TURNS) {
            turns.removeFirst();
            if (!turns.isEmpty()) turns.removeFirst();
        }
    }
}

