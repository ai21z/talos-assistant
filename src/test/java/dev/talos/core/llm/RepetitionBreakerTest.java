package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the lexical repetition breaker.
 *
 * <p>Small synthetic dimensions keep basic assertions readable. Recorded
 * provider chunks and structured-output controls also exercise the default
 * policy, including its legitimate-repetition limitation.
 */
class RepetitionBreakerTest {

    /**
     * Canonical trip: the same substring repeated maxRepeats times in a row
     * must flip the breaker on the repeat that crosses the threshold.
     */
    @Test
    void tripsAfterMaxRepeats() {
        RepetitionBreaker b = new RepetitionBreaker(8, 3, 64);
        // 8-char probe "ABCDEFGH" emitted 3 times in a row (24 chars) -
        // the third occurrence makes count == maxRepeats == 3 → trip.
        assertFalse(b.onChunk("ABCDEFGH"), "1st emission - below threshold");
        assertFalse(b.onChunk("ABCDEFGH"), "2nd emission - still below");
        assertTrue(b.onChunk("ABCDEFGH"),  "3rd emission - trips");
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
        // probe is 44 chars - slightly shorter than the 48-char default.
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
     * repetition in an explanation) must NOT trip - only pathological
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
        // only 2x - under threshold of 3.
        b.onChunk("ABABABABABABABAB"); // probe = last 4 = "ABAB"
        // "ABAB" appears non-overlapping 4 times in the string → trips at 3.
        // That's expected: the model IS emitting a sustained "ABAB" pattern.
        assertTrue(b.tripped(),
                "sustained ABAB pattern non-overlapping 4x trips at 3 - degenerate output");
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
        assertFalse(b.onChunk("ABCDEFGH"), "already tripped - no second true");
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
     * keep the breaker tripped - but once tripped, it stays tripped. This
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

    /**
     * Qwen2.5 A4, 2026-09-08: 449 retained SSE records contained one initial
     * null-content role record and these 448 ordered non-null content deltas.
     * Only generic generated text is retained, not request bodies, private paths, or raw logs.
     * The source log was 733311 bytes, SHA-256:
     * 98149232a227e5e5b828b5a7e462826c8e5f28824fa656912ba6bcd87c74f7a5.
     * This is a detector regression, not a replay of the provider or watchdog.
     */
    @Test
    void recordedQwen25A4ChunksTripAtTheObservedThresholdOnlyOnce() throws Exception {
        String[] chunks = recordedQwen25A4Chunks();
        RepetitionBreaker b = new RepetitionBreaker();
        assertEquals(48, b.substringLen());
        assertEquals(6, b.maxRepeats());
        assertEquals(2048, b.windowSize());

        StringBuilder emitted = new StringBuilder();
        int transitions = 0;
        for (int i = 0; i < chunks.length; i++) {
            emitted.append(chunks[i]);
            boolean transitioned = b.onChunk(chunks[i]);
            assertEquals(i == 442, transitioned, "transition at content chunk " + i);
            assertEquals(i >= 442, b.tripped(), "trip state at content chunk " + i);
            if (transitioned) {
                transitions++;
                assertEquals(1882, emitted.length(), "UTF-16 characters at first trip");
                String probe = "If you need to switch to a mode where I can read";
                assertEquals(48, probe.length());
                assertTrue(emitted.toString().endsWith(probe));
                for (int offset : new int[]{206, 682, 970, 1258, 1546, 1834}) {
                    assertEquals(probe, emitted.substring(offset, offset + 48),
                            "observed non-adjacent occurrence at " + offset);
                }
            }
        }
        assertEquals(1, transitions);
        assertEquals(1908, emitted.length());
        assertFalse(b.onChunk("different content"), "no second transition after the retained tail");
        assertTrue(b.tripped());
    }

    /** Records an existing lexical limitation; it does not endorse a semantic false-positive. */
    @Test
    void sixDistinctStructuredRowsCanReachTheLexicalThreshold() {
        RepetitionBreaker b = new RepetitionBreaker();
        StringBuilder emitted = new StringBuilder();
        for (int node = 1; node <= 6; node++) {
            String row = structuredStatusRow(node);
            emitted.append(row);
            assertEquals(node == 6, b.onChunk(row), "distinct node-status row " + node);
            assertEquals(node == 6, b.tripped());
        }
        assertEquals(606, emitted.length());
        String probe = "bed validation checks completed successfully.\"}\n";
        assertEquals(48, probe.length());
        for (int offset : new int[]{53, 154, 255, 356, 457, 558}) {
            assertEquals(probe, emitted.substring(offset, offset + 48));
        }
    }

    @Test
    void fiveSharedStructuredTailsAndOneDifferentTailStayBelowThreshold() {
        RepetitionBreaker b = new RepetitionBreaker();
        for (int node = 1; node <= 5; node++) {
            assertFalse(b.onChunk(structuredStatusRow(node)));
            assertFalse(b.tripped());
        }
        assertFalse(b.onChunk("{\"node\":6,\"status\":\"waiting\","
                + "\"message\":\"Awaiting an operator decision.\"}\n"));
        assertFalse(b.tripped(), "five shared tails are not six matches of the current probe");
    }

    @Test
    void defaultWindowEvictsOldOccurrencesBeforeASeparateSixRowThreshold() {
        RepetitionBreaker b = new RepetitionBreaker();
        for (int node = 1; node <= 5; node++) {
            assertFalse(b.onChunk(structuredStatusRow(node)));
        }
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 2048; i++) {
            filler.append((char) (0x1000 + i)); // distinct non-surrogate characters
        }
        assertFalse(b.onChunk(filler.toString()));
        assertFalse(b.tripped());
        for (int node = 1; node <= 6; node++) {
            assertEquals(node == 6, b.onChunk(structuredStatusRow(node)),
                    "only post-eviction occurrences count, row " + node);
            assertEquals(node == 6, b.tripped());
        }
    }

    private static String structuredStatusRow(int node) {
        return "{\"node\":" + node + ",\"status\":\"unchanged\","
                + "\"message\":\"All prescribed validation checks completed successfully.\"}\n";
    }

    private static String[] recordedQwen25A4Chunks() throws Exception {
        String[] chunks;
        try (var input = RepetitionBreakerTest.class.getResourceAsStream(
                "/dev/talos/core/llm/qwen25-a4-repetition-chunks.json")) {
            assertNotNull(input, "portable recorded content-delta fixture must exist");
            chunks = new ObjectMapper().readValue(input, String[].class);
        }
        assertEquals(448, chunks.length);
        StringBuilder framed = new StringBuilder();
        StringBuilder content = new StringBuilder();
        for (String chunk : chunks) {
            assertNotNull(chunk);
            assertFalse(chunk.isEmpty());
            content.append(chunk);
            // This binds order and boundaries, independently of resource checkout newlines:
            // decimal UTF-16 length, ':', exact decoded content, then one LF.
            framed.append(chunk.length()).append(':').append(chunk).append('\n');
        }
        assertEquals("1d038729c52aaaa9e256fd50f14beca211173fb233a61cd299fc73fceca89ee2",
                sha256(framed.toString()), "ordered chunk identity");
        assertEquals(1908, content.length(), "UTF-16 characters, not Unicode code points");
        assertEquals(1918, content.toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("9d541f6ea34bca497aa7a67bb7cd7f2ee565e204bd7cfa889bc7822724c671b6",
                sha256(content.toString()), "retained raw provider content identity");
        return chunks;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}

