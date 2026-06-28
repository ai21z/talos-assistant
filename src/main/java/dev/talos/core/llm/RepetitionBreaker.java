package dev.talos.core.llm;

/**
 * Lexical detector for degenerate-output repetition loops in streaming LLM
 * responses.
 *
 * <p><b>Why this exists.</b> {@code LlmClient.withWallClockBudget} has two
 * pre-existing guards - a wall-clock budget (default 300s) and an idle-chunk
 * watchdog (default 30s). Neither observes chunk <i>content</i>. A local
 * model that falls into a repetition attractor keeps emitting tokens at a
 * normal rate, so {@code lastChunkAt} keeps advancing and the idle watchdog
 * never fires. In one real transcript (gemma4:26b-a4b-it-q4_K_M, Apr 2026
 * test-output.txt), the model generated 200+ lines of nested "The user's
 * prompt is 'The user's prompt is '..." before the wall-clock finally
 * aborted at 387.8s. This detector catches that pattern in &lt;1s of
 * sustained repetition.
 *
 * <p><b>How it works.</b> A rolling tail buffer (default 2048 chars) is
 * kept in sync with the streamed output. After each chunk, the last
 * {@code substringLen} characters of the tail are treated as a "probe" and
 * counted (non-overlapping) against the rest of the tail. If the probe
 * appears {@code maxRepeats} or more times, the breaker trips. Purely
 * lexical: no regex, no tokenization, no ML, no model-specific heuristics.
 *
 * <p><b>Why the defaults.</b> {@code substringLen=48} × {@code maxRepeats=6}
 * means the detector only trips after at least 288 characters of back-to-back
 * identical substring. Legitimate model output - even repetitive code
 * formatting, markdown lists, or JSON arrays - does not exhibit exact
 * 48-char repeats six times in a row. The transcript's degenerate "[...]
 * The user's prompt is 'The user's prompt is '..." pattern does. Tuning
 * happens via the constructor; defaults live in
 * {@link #DEFAULT_SUBSTRING_LEN} / {@link #DEFAULT_MAX_REPEATS} /
 * {@link #DEFAULT_WINDOW_SIZE}.
 *
 * <p><b>Thread-safety.</b> Instances are mutated only from the worker
 * thread that drives the engine stream. {@link #onChunk(String)} is the
 * only mutator; {@link #tripped()} is a volatile read so the watchdog
 * thread can safely poll trip state.
 */
final class RepetitionBreaker {

    /** 48 characters - long enough that exact repeats don't happen in legitimate prose. */
    static final int DEFAULT_SUBSTRING_LEN = 48;

    /** 6 consecutive repeats - 288+ characters of sustained degenerate output. */
    static final int DEFAULT_MAX_REPEATS = 6;

    /** 2048-character rolling window - covers multiple pathological repeats without O(n²) cost. */
    static final int DEFAULT_WINDOW_SIZE = 2048;

    private final int substringLen;
    private final int maxRepeats;
    private final int windowSize;
    private final StringBuilder tail;
    private volatile boolean tripped;

    RepetitionBreaker() {
        this(DEFAULT_SUBSTRING_LEN, DEFAULT_MAX_REPEATS, DEFAULT_WINDOW_SIZE);
    }

    RepetitionBreaker(int substringLen, int maxRepeats, int windowSize) {
        if (substringLen < 1) throw new IllegalArgumentException("substringLen must be >= 1");
        if (maxRepeats < 2) throw new IllegalArgumentException("maxRepeats must be >= 2");
        if (windowSize < substringLen * maxRepeats) {
            throw new IllegalArgumentException(
                    "windowSize (" + windowSize + ") must be >= substringLen * maxRepeats (" +
                            (substringLen * maxRepeats) + ")");
        }
        this.substringLen = substringLen;
        this.maxRepeats = maxRepeats;
        this.windowSize = windowSize;
        this.tail = new StringBuilder(windowSize + 64);
    }

    /**
     * Append a chunk to the rolling window and re-evaluate the trip state.
     *
     * @param chunk new streamed text (may be empty; null is treated as empty)
     * @return {@code true} if the breaker just transitioned to tripped
     *         (only on the transition, not on subsequent calls while
     *         already tripped - this lets callers act exactly once).
     */
    boolean onChunk(String chunk) {
        if (tripped) return false;
        if (chunk == null || chunk.isEmpty()) return false;

        tail.append(chunk);
        if (tail.length() > windowSize) {
            tail.delete(0, tail.length() - windowSize);
        }

        if (tail.length() < substringLen * maxRepeats) return false;

        // Probe: the last substringLen characters of the tail - i.e., what
        // the model has MOST RECENTLY emitted. Counting non-overlapping
        // occurrences across the whole tail catches the repetition-attractor
        // pattern where the probe itself is a chunk of the looping output.
        String probe = tail.substring(tail.length() - substringLen);
        int count = 0;
        int idx = 0;
        while ((idx = tail.indexOf(probe, idx)) != -1) {
            count++;
            if (count >= maxRepeats) {
                tripped = true;
                return true;
            }
            idx += substringLen; // non-overlapping scan
        }
        return false;
    }

    /** True once the breaker has detected pathological repetition. Monotonic - never resets. */
    boolean tripped() {
        return tripped;
    }

    int substringLen()  { return substringLen; }
    int maxRepeats()    { return maxRepeats; }
    int windowSize()    { return windowSize; }
}


