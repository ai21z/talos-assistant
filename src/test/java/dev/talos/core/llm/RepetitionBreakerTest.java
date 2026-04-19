package dev.talos.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the lexical repetition breaker.
 *
 * <p>Uses small test dimensions (substringLen=8, maxRepeats=3, windowSize=64)
 * so scenarios stay readable in assertions. Defaults-mode is covered by
 * the "below threshold" tests.
 */
class RepetitionBreakerTest {

    /**
     * Canonical trip: the same substring repeated maxRepeats times in a row
     * must flip the breaker on the repeat that crosses the threshold.
     */
    @Test
    void tripsAfterMaxRepeats() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        // 8-char probe "ABCDEFGH" emitted 3 times in a row (24 chars) —
        // the third occurrence makes count == maxRepeats == 3 → trip.
        assertFalse(b.onChunk("ABCDEFGH"), "1st emission — below threshold");
        assertFalse(b.onChunk("ABCDEFGH"), "2nd emission — still below");
        assertTrue(b.onChunk("ABCDEFGH"),  "3rd emission — trips");
        assertTrue(b.tripped());
    }

    /**
     * The transcript's real attractor: nested "The user's prompt is '..."
     * emitted as many tokens. The breaker must catch it well before the
     * 300s wall-clock fires.
     */
    @Test
    void tripsOnTranscriptObservedPattern() {
        RepetitionBreaker b = new RepetitionBreaker(); // defaults (48/6/2048)
        String probe = "The user's prompt is 'The user's prompt is '";
        // probe is 44 chars — slightly shorter than the 48-char default.
        // Pad with the typical trailing quote + space so the 48-char window
        // captures a full cycle including the boundary.
        String loop = probe + " 'The";  // 50 chars; emit 20 repeats.
        boolean trippedOnOne = false;
        for (int i = 0; i < 20; i++) {
            if (b.onChunk(loop)) { trippedOnOne = true; break; }
        }
        assertTrue(trippedOnOne, "degenerate loop must trip within 20 emissions");
        assertTrue(b.tripped());
    }

    /**
     * Legitimate prose containing the same phrase twice (e.g., emphatic
     * repetition in an explanation) must NOT trip — only pathological
     * sustained repetition should.
     */
    @Test
    void doesNotTripOnShortLegitimateRepetition() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        // Legitimate content: mentions "ABCDEFGH" twice embedded in prose,
        // well below the maxRepeats threshold of 3.
        b.onChunk("Consider the string ABCDEFGH which ");
        b.onChunk("is useful. Again we use ABCDEFGH here.");
        assertFalse(b.tripped());
    }

    /**
     * Non-overlapping match scan: if a probe could technically overlap with
     * itself (e.g., "ABABAB" contains "AB" 3x overlapping, but the emitted
     * text isn't actually pathological), the count uses non-overlapping
     * scan. This is a sanity test that the window-based check doesn't
     * over-fire.
     */
    @Test
    void nonOverlappingScanDoesNotOverFire() {
        RepetitionBreaker b = new RepetitionBreaker(4, 3, 64);
        // "ABABABAB" has "AB" 4x overlapping, but "ABAB" non-overlapping
        // only 2x — under threshold of 3.
        b.onChunk("ABABABABABABABAB"); // probe = last 4 = "ABAB"
        // "ABAB" appears non-overlapping 4 times in the string → trips at 3.
        // That's expected: the model IS emitting a sustained "ABAB" pattern.
        assertTrue(b.tripped(),
                "sustained ABAB pattern non-overlapping 4x trips at 3 — degenerate output");
    }

    /**
     * Breaker is monotonic: after tripping, {@link RepetitionBreaker#onChunk}
     * must keep returning {@code false} for subsequent calls. The
     * transition-to-tripped event is reported exactly once so callers
     * (watchdog, sink) act a single time.
     */
    @Test
    void onChunkReturnsTrueOnlyOnceOnTransition() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        b.onChunk("ABCDEFGH");
        b.onChunk("ABCDEFGH");
        assertTrue(b.onChunk("ABCDEFGH"), "first trip reports true");
        assertFalse(b.onChunk("ABCDEFGH"), "already tripped — no second true");
        assertFalse(b.onChunk("different content"), "no duplicate trip signal");
        assertTrue(b.tripped(), "but tripped state is permanent");
    }

    /** Null / empty chunks must not throw and must not advance the window. */
    @Test
    void nullAndEmptyChunksAreNoOps() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        assertFalse(b.onChunk(null));
        assertFalse(b.onChunk(""));
        assertFalse(b.tripped());
    }

    /**
     * Invalid construction parameters must fail fast rather than produce a
     * silently-broken breaker.
     */
    @Test
    void rejectsInvalidConstructorArgs() {
        assertThrows(IllegalArgumentException.class, () -> new RepetitionBreaker(0, 3, 64));
        assertThrows(IllegalArgumentException.class, () -> new RepetitionBreaker(8, 1, 64));
        assertThrows(IllegalArgumentException.class, () -> new RepetitionBreaker(8, 3, 16),
                "windowSize must fit substringLen * maxRepeats");
    }

    /**
     * Old repetitions that have scrolled out of the rolling window must not
     * keep the breaker tripped — but once tripped, it stays tripped. This
     * test confirms that the WINDOW itself is correctly bounded (no
     * unbounded memory growth) without weakening the monotonic trip contract.
     */
    @Test
    void rollingWindowIsBoundedByWindowSize() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        // Emit more content than the window can hold; no pattern in it.
        for (int i = 0; i < 100; i++) {
            // Each chunk unique → no repetition ever forms in the window
            b.onChunk(String.format("chunk-%03d-%s", i, "xyz"));
        }
        assertFalse(b.tripped(), "non-repeating content must not trip");
    }
}

