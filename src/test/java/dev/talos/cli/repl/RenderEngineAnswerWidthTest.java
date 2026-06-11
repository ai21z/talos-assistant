package dev.talos.cli.repl;

import dev.talos.cli.ui.AnswerPaneRenderer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T772: the answer pane resolves its width from the live terminal, while
 * paths without a terminal render byte-identical to the historical fixed
 * width of 96.
 */
class RenderEngineAnswerWidthTest {

    private static final String LONG_BODY =
            "This characterization body is deliberately longer than any candidate pane width so "
                    + "that word wrapping decisions become visible in the rendered block and any "
                    + "width drift in the default path changes bytes immediately and fails here.";

    private static final TerminalCapabilities PLAIN = TerminalCapabilities.detect(
            java.util.Map.of(), false, "Windows 11", StandardCharsets.UTF_8, ColorPolicy.NEVER);

    private static String rendered(RenderEngine engine, ByteArrayOutputStream sink) {
        engine.render(new Result.Ok(LONG_BODY));
        return sink.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withoutTerminalThePaneStaysAtTheHistoricalWidth96() {
        var sink = new ByteArrayOutputStream();
        CliTheme theme = CliTheme.forCapabilities(PLAIN);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), false, theme, null);

        String output = rendered(engine, sink);
        String oracle = new AnswerPaneRenderer(theme, 96).renderBlock(LONG_BODY, "answer");

        assertTrue(output.contains(oracle),
                "no-terminal path must render the exact pre-T772 width-96 block\n" + output);
    }

    @Test
    void liveTerminalWidthDrivesThePaneClamped() {
        var sink = new ByteArrayOutputStream();
        CliTheme theme = CliTheme.forCapabilities(PLAIN);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), false, theme, () -> 70);

        String output = rendered(engine, sink);
        String oracle = new AnswerPaneRenderer(theme, 70).renderBlock(LONG_BODY, "answer");

        assertTrue(output.contains(oracle),
                "live width 70 must drive the pane\n" + output);
    }

    @Test
    void oversizedTerminalClampsTo120() {
        var sink = new ByteArrayOutputStream();
        CliTheme theme = CliTheme.forCapabilities(PLAIN);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), false, theme, () -> 500);

        String output = rendered(engine, sink);
        String oracle = new AnswerPaneRenderer(theme, 120).renderBlock(LONG_BODY, "answer");

        assertTrue(output.contains(oracle), "width 500 must clamp to 120\n" + output);
    }

    @Test
    void brokenWidthSupplierFallsBackToTheDefault() {
        var sink = new ByteArrayOutputStream();
        CliTheme theme = CliTheme.forCapabilities(PLAIN);
        var engine = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), false, theme,
                () -> { throw new IllegalStateException("terminal closed"); });

        String output = rendered(engine, sink);
        String oracle = new AnswerPaneRenderer(theme, 96).renderBlock(LONG_BODY, "answer");

        // No COLUMNS in this JVM's relevant env on the CI/dev host is not
        // assumed; a throwing supplier plus clamped COLUMNS both render
        // between 60 and 120 — assert the strict contract only when the
        // env carries no COLUMNS override.
        if (System.getenv("COLUMNS") == null) {
            assertTrue(output.contains(oracle),
                    "broken supplier must fall back to width 96\n" + output);
        } else {
            assertEquals(false, output.isBlank());
        }
    }
}
