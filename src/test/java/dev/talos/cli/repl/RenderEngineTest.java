package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RenderEngine's turn-stats and route-hint rendering.
 * Uses a non-interactive RenderEngine with a captured output stream.
 * Interactive features are tested by explicitly passing interactive=true.
 */
class RenderEngineTest {

    private ByteArrayOutputStream bout;
    private PrintStream out;

    @BeforeEach
    void setUp() {
        bout = new ByteArrayOutputStream();
        out = new PrintStream(bout, true, StandardCharsets.UTF_8);
    }

    private RenderEngine engine(boolean interactive) {
        return new RenderEngine(new Config(), new Redactor(), out, interactive);
    }

    private String output() {
        return bout.toString(StandardCharsets.UTF_8);
    }

    // ── printTurnStats ───────────────────────────────────────────────────

    @Nested
    class TurnStats {

        @Test
        void showsTurnNumberAndElapsedSeconds() {
            var re = engine(true);
            re.printTurnStats(3, 2500, 0);

            String text = output();
            assertTrue(text.contains("Turn 3"), "Should show turn number");
            assertTrue(text.contains("2.5s"), "Should show elapsed in seconds");
        }

        @Test
        void showsMillisecondsForFastTurns() {
            var re = engine(true);
            re.printTurnStats(1, 450, 0);

            String text = output();
            assertTrue(text.contains("450ms"), "Should show milliseconds for <1s");
        }

        @Test
        void showsResponseLength() {
            var re = engine(true);
            re.printTurnStats(2, 1200, 512);

            String text = output();
            assertTrue(text.contains("~512 chars"), "Should show response length");
        }

        @Test
        void omitsResponseLengthWhenZero() {
            var re = engine(true);
            re.printTurnStats(1, 500, 0);

            String text = output();
            assertFalse(text.contains("chars"), "Should omit chars when length is 0");
        }

        @Test
        void suppressedInNonInteractiveMode() {
            var re = engine(false);
            re.printTurnStats(1, 1000, 100);

            assertEquals("", output(), "Non-interactive should produce no output");
        }

        @Test
        void suppressedWhenConfigDisabled() {
            // Create config with show_timing_after_answer = false
            Config cfg = new Config();
            cfg.data.put("ui", java.util.Map.of(
                    "show_timing_after_answer", false,
                    "show_status_during_answer", true,
                    "status_label", "Test"
            ));
            var re = new RenderEngine(cfg, new Redactor(), out, true);
            re.printTurnStats(1, 1000, 100);

            assertEquals("", output(), "Should be suppressed when config is false");
        }
    }

    // ── printRouteHint ───────────────────────────────────────────────────

    @Nested
    class RouteHint {

        @Test
        void showsRouteLabel() {
            var re = engine(true);
            re.printRouteHint("rag");

            assertTrue(output().contains("rag"), "Should include route label");
        }

        @Test
        void suppressedInNonInteractiveMode() {
            var re = engine(false);
            re.printRouteHint("rag");

            assertEquals("", output(), "Non-interactive should produce no output");
        }

        @Test
        void suppressedForBlankLabel() {
            var re = engine(true);
            re.printRouteHint("  ");

            assertEquals("", output(), "Blank label should produce no output");
        }

        @Test
        void suppressedForNullLabel() {
            var re = engine(true);
            re.printRouteHint(null);

            assertEquals("", output(), "Null label should produce no output");
        }
    }

    // ── Basic render ─────────────────────────────────────────────────────

    @Nested
    class BasicRender {

        @Test
        void rendersOkResult() {
            var re = engine(false);
            re.render(new Result.Ok("hello world"));

            assertTrue(output().contains("hello world"), "Should render Ok text");
        }

        @Test
        void rendersInfoResult() {
            var re = engine(false);
            re.render(new Result.Info("some info"));

            assertTrue(output().contains("some info"), "Should render Info text");
            assertTrue(output().contains("i "), "Info result should have a distinct prefix");
        }

        @Test
        void rendersErrorResult() {
            var re = engine(false);
            re.render(new Result.Error("bad thing", 500));

            assertTrue(output().contains("bad thing"), "Should render error message");
        }

        @Test
        void handlesNullResult() {
            var re = engine(false);
            re.render(null);

            assertTrue(output().contains("null"), "Should handle null result gracefully");
        }

        @Test
        void rendersSourcesAsSeparateSectionForOkResult() {
            var re = engine(false);
            re.render(new Result.Ok("Answer body\n\n[Sources]\n - src/App.java#0\n - README.md#1\n"));

            String text = output();
            assertTrue(text.contains("Answer body"));
            assertTrue(text.contains("Sources"));
            assertTrue(text.contains("src/App.java#0"));
            assertFalse(text.contains("[Sources]"), "Raw source marker should not be blended into answer body");
        }

        @Test
        void rendersSourcesAsSeparateSectionForStreamedSuffix() {
            var re = engine(false);
            re.render(new Result.Streamed("Answer body\n\n[Sources]\n - src/App.java#0\n",
                    "\n\n[Sources]\n - src/App.java#0\n"));

            String text = output();
            assertTrue(text.contains("Sources"));
            assertTrue(text.contains("src/App.java#0"));
            assertFalse(text.contains("[Sources]"), "Streamed source suffix should be normalized");
        }
    }
}

