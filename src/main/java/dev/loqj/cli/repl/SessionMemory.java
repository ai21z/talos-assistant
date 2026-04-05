package dev.loqj.cli.repl;

/**
 * Minimal rolling-window session memory for conversational context.
 * Extracted from {@code RagService} where it did not belong — session memory
 * is a CLI/REPL concern, not a knowledge-engine concern.
 *
 * <p>Stores a rolling text window of recent user inputs and answers,
 * capped at {@link #MAX_CHARS} characters. Oldest content is trimmed
 * from the front when the window overflows.
 *
 * <p>Thread-safe: all methods synchronize on the instance.
 */
public final class SessionMemory {

    /** Maximum characters retained in the rolling memory window. */
    public static final int MAX_CHARS = 4000;

    private String buffer;

    public SessionMemory() {
        this.buffer = null;
    }

    /** Returns the current memory content, or null if empty. */
    public synchronized String get() {
        return buffer;
    }

    /** Clears all memory. */
    public synchronized void clear() {
        buffer = null;
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
        String entry = userInput + "\n" + answer;
        String s = (buffer == null ? "" : buffer + "\n") + entry;
        if (s.length() > MAX_CHARS) {
            s = s.substring(s.length() - MAX_CHARS);
        }
        buffer = s;
    }
}

