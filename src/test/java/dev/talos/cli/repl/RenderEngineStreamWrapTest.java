package dev.talos.cli.repl;

import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.ColorPolicy;
import dev.talos.cli.ui.TerminalCapabilities;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider fragments remain private. Complete selected answers use the
 * buffered answer pane and its width bound across terminal capabilities.
 */
class RenderEngineStreamWrapTest {

    private static final String LONG_LINE =
            "wrapcheck " + "verylongstreamedword".repeat(3) + " "
                    + "this single logical line is far longer than the answer pane content width and "
                    + "previously sheared the rail because the streaming path never wrapped it at all.";

    private static final TerminalCapabilities CAPABLE = TerminalCapabilities.detect(
            Map.of("WT_SESSION", "wt"), true, "Windows 11", StandardCharsets.UTF_8, ColorPolicy.ALWAYS);

    private static final TerminalCapabilities PLAIN = TerminalCapabilities.detect(
            Map.of(), false, "Windows 11", StandardCharsets.UTF_8, ColorPolicy.NEVER);

    private static String streamThrough(RenderEngine engine, ByteArrayOutputStream out) {
        StringBuilder collected = new StringBuilder();
        Consumer<String> sink = engine.answerStreamSink(collected::append);
        for (int index = 0; index < LONG_LINE.length(); index += 7) {
            sink.accept(LONG_LINE.substring(index, Math.min(LONG_LINE.length(), index + 7)));
            assertEquals(0, out.size(), "Provider fragments must remain private before selection");
            assertTrue(collected.isEmpty(), "A custom writer must not receive provider fragments");
        }
        engine.render(new Result.StreamEnd());
        assertEquals(0, out.size(), "Transport completion must not commit an answer");
        engine.render(new Result.Ok(LONG_LINE));
        assertTrue(collected.isEmpty());
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void redirectedSelectedAnswerWrapsAtTheBufferedContentWidth() {
        var out = new ByteArrayOutputStream();
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), false,
                CliTheme.forCapabilities(PLAIN), () -> 96);

        String rendered = streamThrough(engine, out);

        assertBufferedWidth(rendered);
    }

    @Test
    void interactiveCapableSelectedAnswerWrapsAtTheBufferedContentWidth() {
        var out = new ByteArrayOutputStream();
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), true,
                CliTheme.forCapabilities(CAPABLE), () -> 96);

        String rendered = streamThrough(engine, out);

        assertBufferedWidth(rendered);
    }

    @Test
    void interactiveNoColorSelectedAnswerWrapsAtTheBufferedContentWidth() {
        var out = new ByteArrayOutputStream();
        TerminalCapabilities noColor = TerminalCapabilities.detect(
                Map.of("NO_COLOR", "1", "WT_SESSION", "wt"), true, "Windows 11",
                StandardCharsets.UTF_8, null);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), true,
                CliTheme.forCapabilities(noColor), () -> 96);

        String rendered = streamThrough(engine, out);

        assertFalse(rendered.contains("\u001B"));
        assertBufferedWidth(rendered);
    }

    private static void assertBufferedWidth(String rendered) {
        assertFalse(rendered.contains(LONG_LINE), "The complete long line must be wrapped:\n" + rendered);
        String body = stripAnsi(rendered).lines()
                .filter(line -> line.startsWith("  │ ") || line.startsWith("  | "))
                .map(line -> line.substring(4))
                .collect(java.util.stream.Collectors.joining(" "));
        assertEquals(LONG_LINE, body, "Wrapping must preserve the complete selected answer");
        for (String row : rendered.split("\\R")) {
            assertTrue(stripAnsi(row).length() <= 96,
                    "Every rendered row must fit the pane width: [" + stripAnsi(row) + "]");
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
