package dev.talos.cli.ui.md;

import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.ColorPolicy;
import dev.talos.cli.ui.StreamingAnswerShaper;
import dev.talos.cli.ui.TerminalCapabilities;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T777: trusted streaming markdown. The load-bearing invariant is
 * markers-visible/colorize-only — stripping ANSI from the styled stream
 * recovers the plain wrapped text byte-for-byte, so transcript
 * string-matching can never regress and column math stays plain.
 */
class StreamingMarkdownShaperTest {

    private static final CliTheme COLOR = CliTheme.forCapabilities(TerminalCapabilities.detect(
            Map.of("WT_SESSION", "wt"), true, "Windows 11", StandardCharsets.UTF_8, ColorPolicy.ALWAYS));

    private static final int WIDTH = 92;

    private static String shaped(String text, List<String> chunks) {
        StreamingMarkdownShaper shaper = new StreamingMarkdownShaper(WIDTH, COLOR);
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append(shaper.accept(chunk));
        }
        out.append(shaper.flush());
        return out.toString();
    }

    private static String plainWrapped(String text) {
        StreamingAnswerShaper plain = new StreamingAnswerShaper(WIDTH);
        return plain.accept(text) + plain.flush();
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static List<String> randomChunks(String text, long seed) {
        var chunks = new java.util.ArrayList<String>();
        Random random = new Random(seed);
        int i = 0;
        while (i < text.length()) {
            int size = 1 + random.nextInt(11);
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
            i += size;
        }
        return chunks;
    }

    // ── The load-bearing invariant ────────────────────────────────────────

    @Test
    void strippedStyledOutputEqualsPlainWrappedOutputForProseFamilies() {
        List<String> texts = List.of(
                "# Heading One\nplain prose follows the heading line",
                "- bullet one\n- bullet two with **bold** tail\n1. numbered entry",
                "prose with **bold span**, *italic span*, and `inline code` markers "
                        + "stretched out far enough that the line wraps across several rows "
                        + "so span state must carry over wrapped row boundaries correctly.",
                "## Second *level* heading\nfollow-up paragraph");
        for (String text : texts) {
            for (long seed : new long[]{7L, 77L}) {
                String styled = shaped(text, randomChunks(text, seed));
                assertEquals(plainWrapped(text), stripAnsi(styled),
                        "markers-visible invariant broken (seed " + seed + ") for:\n" + text);
            }
        }
    }

    @Test
    void fenceContentPreservesEveryCharacterInOrder() {
        String text = "before\n```java\nint x = 1;   // spacing   preserved\n\n"
                + "String s = \"y\";\n```\nafter";
        String styled = shaped(text, randomChunks(text, 42L));
        // Hard-cutting only injects '\n'; no character may be lost or reordered.
        assertEquals(text.replace("\n", ""), stripAnsi(styled).replace("\n", ""));
        assertTrue(stripAnsi(styled).contains("int x = 1;   // spacing   preserved"),
                "code spacing must not collapse:\n" + stripAnsi(styled));
    }

    // ── Chrome passthrough ────────────────────────────────────────────────

    @Test
    void talosChromeLinesPassThroughByteIdenticalWithNoAnsi() {
        for (String chrome : List.of(
                "✓ Edited script.js: replaced 1 line(s) at line 3",
                "✓ Updated app.css (12 lines, 310 bytes)",
                "[Used 2 tool(s): talos.read_file, talos.edit_file | 1 iteration(s)]")) {
            String styled = shaped(chrome + "\n", List.of(chrome + "\n"));
            assertEquals(chrome + "\n", styled,
                    "chrome lines carry no markdown tokens and must gain no ANSI: " + chrome);
        }
    }

    // ── Structure styling ─────────────────────────────────────────────────

    @Test
    void headingsAreStyledAndKeepTheirCharacters() {
        String styled = shaped("# Title\n", List.of("# Title\n"));
        assertTrue(styled.startsWith("\u001B["), "heading row must be styled: " + styled);
        assertTrue(stripAnsi(styled).startsWith("# Title"), styled);
    }

    @Test
    void fenceDelimitersToggleAndContentStaysPlainUntilT778() {
        String text = "```python\nvalue = compute()\n```\n**bold** prose resumes";
        String styled = shaped(text, List.of(text));
        String[] rows = styled.split("\n", -1);
        assertTrue(rows[0].contains("```python") || stripAnsi(rows[0]).equals("```python"));
        assertTrue(rows[0].startsWith("\u001B["), "delimiter styled as metadata: " + rows[0]);
        assertEquals("value = compute()", rows[1], "fence content stays plain in T777");
        assertTrue(rows[3].contains("\u001B["), "prose styling must resume after the fence: " + rows[3]);
    }

    @Test
    void unterminatedFenceFlushesItsContentPlain() {
        String text = "```js\nlet a = 1;\nlet b = 2;";
        String styled = shaped(text, List.of(text));
        assertTrue(stripAnsi(styled).contains("let a = 1;"), styled);
        assertTrue(stripAnsi(styled).contains("let b = 2;"),
                "unterminated fence content must never be swallowed: " + styled);
    }

    @Test
    void bulletMarkersDoNotToggleItalicState() {
        String text = "* starred bullet\nplain *italic* prose";
        String styled = shaped(text, List.of(text));
        String plain = stripAnsi(styled);
        assertEquals(plainWrapped(text), plain);
        // The bullet's '*' must not leave italic open across lines: the
        // second line's first '*' OPENS italic (styled), so an escape code
        // must appear before "italic".
        String secondLine = styled.substring(styled.indexOf('\n') + 1);
        assertTrue(secondLine.indexOf("\u001B[3m") >= 0,
                "italic span must open on the prose line: " + secondLine);
    }

    @Test
    void rowsStreamBeforeTheLogicalLineCompletes() {
        StreamingMarkdownShaper shaper = new StreamingMarkdownShaper(20, COLOR);
        String early = shaper.accept("alpha beta gamma delta epsilon zeta");
        assertFalse(early.isEmpty(), "completed rows must stream before the line ends");
        assertTrue(stripAnsi(early).contains("\n"), early);
    }
}
