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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T776 activation contract: streamed answers wrap only in fully capable
 * interactive mode; every degraded mode keeps the historical pass-through
 * bytes (the rail-shear behavior is the FROZEN contract for redirected,
 * NO_COLOR, ASCII, and dumb-terminal output - the evidence chain
 * string-matches those transcripts).
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

    private static String streamThrough(RenderEngine engine) {
        StringBuilder collected = new StringBuilder();
        Consumer<String> sink = engine.answerStreamSink(collected::append);
        sink.accept(LONG_LINE);
        engine.render(new Result.StreamEnd());
        return collected.toString();
    }

    @Test
    void redirectedStreamingStaysUnwrappedByteForByte() {
        // Permanent golden for the degraded path: the full logical line must
        // appear unwrapped, exactly as before T776.
        var out = new ByteArrayOutputStream();
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), false,
                CliTheme.forCapabilities(PLAIN), null);

        String rendered = streamThrough(engine);

        assertTrue(rendered.contains(LONG_LINE),
                "non-interactive streaming must pass the line through unwrapped:\n" + rendered);
    }

    @Test
    void interactiveCapableStreamingWrapsAtTheContentWidth() {
        var out = new ByteArrayOutputStream();
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), true,
                CliTheme.forCapabilities(CAPABLE), () -> 96);

        String rendered = streamThrough(engine);

        assertFalse(rendered.contains(LONG_LINE),
                "interactive capable streaming must wrap the long line:\n" + rendered);
        for (String row : rendered.split("\\R")) {
            assertTrue(stripAnsi(row).length() <= 96,
                    "every rendered row must fit the pane width: [" + stripAnsi(row) + "]");
        }
    }

    @Test
    void interactiveNoColorStreamingKeepsThePassThroughBytes() {
        // Owner decision: byte-identity beats polish in degraded interactive
        // modes - NO_COLOR terminals keep the historical unwrapped stream.
        var out = new ByteArrayOutputStream();
        TerminalCapabilities noColor = TerminalCapabilities.detect(
                Map.of("NO_COLOR", "1", "WT_SESSION", "wt"), true, "Windows 11",
                StandardCharsets.UTF_8, null);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(out, true, StandardCharsets.UTF_8), true,
                CliTheme.forCapabilities(noColor), () -> 96);

        String rendered = streamThrough(engine);

        assertTrue(rendered.contains(LONG_LINE),
                "NO_COLOR interactive streaming must stay pass-through:\n" + rendered);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
