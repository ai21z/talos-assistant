package dev.talos.cli.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T776 parity contract: streaming text through {@link StreamingAnswerShaper}
 * + {@link AnswerPaneRenderer.Stream} must produce the same pane bytes as
 * {@link AnswerPaneRenderer#renderBlock} for the same full text - under any
 * chunking of the input. renderBlock is the oracle; the shaper replicates
 * it incrementally.
 */
class StreamingAnswerShaperTest {

    private static final CliTheme PLAIN = CliTheme.forCapabilities(TerminalCapabilities.detect(
            Map.of(), false, "Windows 11", StandardCharsets.UTF_8, ColorPolicy.NEVER));

    private static final List<String> PARITY_TEXTS = List.of(
            // long single paragraph (the rail-shear case)
            "This characterization paragraph is deliberately much longer than the pane content width "
                    + "so that greedy word wrapping produces several rows and any drift from the block "
                    + "oracle becomes a byte difference immediately.",
            // short lines, internal double spaces preserved
            "short line with  double  spaces\nand a second one",
            // long line with whitespace runs (collapse semantics)
            "collapse   these    runs " + "word ".repeat(40),
            // overlong word hard-split, flanked by normal words
            "before " + "x".repeat(200) + " after",
            // overlong word at line start
            "y".repeat(150) + " tail words here",
            // leading whitespace on a long line (split artifact: leading token dropped)
            "    leading spaces then " + "filler ".repeat(30),
            // empty lines and blank-ish structure
            "para one\n\npara two\n",
            // whitespace-only overlong line renders one empty row
            " ".repeat(120) + "\nnext",
            // multi-line mix ending without newline
            "alpha beta\n" + "gamma ".repeat(40) + "\ndelta",
            // exact-width word boundary
            "z".repeat(92) + " tail");

    /** Streams {@code text} in the given chunk sizes and returns the full pane output. */
    private static String streamed(AnswerPaneRenderer pane, String text, List<String> chunks) {
        StreamingAnswerShaper shaper = new StreamingAnswerShaper(pane.contentWidth());
        AnswerPaneRenderer.Stream stream = pane.openStream("answer");
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            String shaped = shaper.accept(chunk);
            if (!shaped.isEmpty()) {
                out.append(stream.accept(shaped));
            }
        }
        String tail = shaper.flush();
        if (!tail.isEmpty()) {
            out.append(stream.accept(tail));
        }
        out.append(stream.close("answer"));
        return out.toString();
    }

    private static List<String> chunked(String text, int size) {
        var chunks = new java.util.ArrayList<String>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    private static List<String> randomChunks(String text, long seed) {
        var chunks = new java.util.ArrayList<String>();
        Random random = new Random(seed);
        int i = 0;
        while (i < text.length()) {
            int size = 1 + random.nextInt(17);
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
            i += size;
        }
        return chunks;
    }

    private static String normalized(String paneOutput) {
        return paneOutput.replace("\r\n", "\n");
    }

    private static void assertParity(String text, List<String> chunks, String label) {
        AnswerPaneRenderer pane = new AnswerPaneRenderer(PLAIN, 96);
        String expected = normalized(pane.renderBlock(text, "answer"));
        String actual = normalized(streamed(pane, text, chunks));
        assertEquals(expected, actual, "parity broken for [" + label + "] text:\n" + text);
    }

    @Test
    void parityUnderSingleCharChunking() {
        for (String text : PARITY_TEXTS) {
            assertParity(text, chunked(text, 1), "1-char");
        }
    }

    @Test
    void parityUnderWordSizedChunking() {
        for (String text : PARITY_TEXTS) {
            assertParity(text, chunked(text, 7), "7-char");
        }
    }

    @Test
    void parityUnderWholeTextChunk() {
        for (String text : PARITY_TEXTS) {
            assertParity(text, List.of(text), "whole");
        }
    }

    @Test
    void parityUnderRandomChunkings() {
        for (long seed : new long[]{42L, 4242L, 424242L}) {
            for (String text : PARITY_TEXTS) {
                assertParity(text, randomChunks(text, seed), "seed " + seed);
            }
        }
    }

    @Test
    void parityAtNarrowAndWideWidths() {
        for (int width : new int[]{60, 80, 120}) {
            AnswerPaneRenderer pane = new AnswerPaneRenderer(PLAIN, width);
            for (String text : PARITY_TEXTS) {
                String expected = normalized(pane.renderBlock(text, "answer"));
                String actual = normalized(streamed(pane, text, chunked(text, 3)));
                assertEquals(expected, actual, "parity broken at width " + width + ":\n" + text);
            }
        }
    }

    @Test
    void crlfChunkBoundaryDoesNotDoubleBreak() {
        AnswerPaneRenderer pane = new AnswerPaneRenderer(PLAIN, 96);
        String text = "first\r\nsecond";
        String expected = normalized(pane.renderBlock(text, "answer"));
        // split exactly between CR and LF
        String actual = normalized(streamed(pane, text, List.of("first\r", "\nsecond")));
        assertEquals(expected, actual);
    }

    @Test
    void emittedRowsStreamBeforeTheLineCompletes() {
        // Latency contract: once a row fills, it is emitted without waiting
        // for the rest of the line.
        StreamingAnswerShaper shaper = new StreamingAnswerShaper(20);
        String early = shaper.accept("aaaa bbbb cccc dddd eeee ffff");
        assertTrue(early.contains("\n"),
                "completed rows must stream before the logical line ends: [" + early + "]");
    }
}
