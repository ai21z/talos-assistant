package dev.talos.cli.repl;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.spi.types.ChatMessage;

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
    private ActiveTaskContext activeTaskContext;
    private ArtifactGoal artifactGoal;
    private ChangeSummaryContext changeSummaryContext;

    public SessionMemory() {
        this.buffer = null;
        this.activeTaskContext = ActiveTaskContext.none();
        this.artifactGoal = ArtifactGoal.none();
        this.changeSummaryContext = ChangeSummaryContext.none();
    }

    /** Returns the current memory content, or null if empty. */
    public synchronized String get() {
        return buffer;
    }

    /** Returns an unmodifiable list of structured conversation turns. */
    public synchronized List<ChatMessage> getTurns() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    public synchronized ActiveTaskContext activeTaskContext() {
        return activeTaskContext;
    }

    public synchronized ArtifactGoal artifactGoal() {
        return artifactGoal;
    }

    public synchronized ChangeSummaryContext changeSummaryContext() {
        return changeSummaryContext;
    }

    public synchronized void setActiveTaskContext(ActiveTaskContext activeTaskContext) {
        this.activeTaskContext = activeTaskContext == null ? ActiveTaskContext.none() : activeTaskContext;
    }

    public synchronized void setArtifactGoal(ArtifactGoal artifactGoal) {
        this.artifactGoal = artifactGoal == null ? ArtifactGoal.none() : artifactGoal;
    }

    public synchronized void setChangeSummaryContext(ChangeSummaryContext changeSummaryContext) {
        this.changeSummaryContext = changeSummaryContext == null ? ChangeSummaryContext.none() : changeSummaryContext;
    }

    public synchronized void clearActiveTaskContext() {
        activeTaskContext = ActiveTaskContext.none();
        artifactGoal = ArtifactGoal.none();
    }

    /** Clears all memory. */
    public synchronized void clear() {
        buffer = null;
        turns.clear();
        clearActiveTaskContext();
        changeSummaryContext = ChangeSummaryContext.none();
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

    /**
     * Remove the oldest N entries from the structured turns list.
     * Used by {@link dev.talos.core.context.ConversationManager} after
     * compaction to discard turns that have been summarized into a sketch.
     *
     * <p>The flat buffer is rebuilt from the remaining turns.
     *
     * @param count number of entries (not pairs) to remove from the front
     */
    public synchronized void pruneOldest(int count) {
        int toRemove = Math.min(count, turns.size());
        for (int i = 0; i < toRemove; i++) {
            if (!turns.isEmpty()) turns.removeFirst();
        }

        // Rebuild flat buffer from remaining turns
        if (turns.isEmpty()) {
            buffer = null;
        } else {
            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : turns) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(msg.content());
            }
            String s = sb.toString();
            if (s.length() > MAX_CHARS) {
                s = s.substring(s.length() - MAX_CHARS);
            }
            buffer = s;
        }
    }
}

