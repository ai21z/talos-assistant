package dev.talos.cli.repl;

import dev.talos.runtime.Result;

import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.ColorPolicy;
import dev.talos.cli.ui.TerminalCapabilities;
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

    private RenderEngine semanticEngine(boolean interactive) {
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, interactive, false, true, false);
        return new RenderEngine(new Config(), new Redactor(), out, interactive, CliTheme.forCapabilities(caps));
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

    // ── printCompactionNotice (T805) ─────────────────────────────────────

    @Nested
    class CompactionNotice {

        @Test
        void printsOneNoticeLineComposedFromTheUiChromeConstant() {
            var re = semanticEngine(true);
            re.printCompactionNotice(6, 4);

            String text = output();
            assertTrue(text.contains(
                            dev.talos.core.util.UiChrome.CONTEXT_COMPACTED_PREFIX
                                    + ": 6 older exchanges summarized"),
                    text);
            assertTrue(text.contains("4 kept verbatim]"), text);
        }

        @Test
        void singularExchangeReadsNaturally() {
            var re = semanticEngine(true);
            re.printCompactionNotice(1, 4);

            assertTrue(output().contains("1 older exchange summarized"), output());
        }

        @Test
        void suppressedInNonInteractiveMode() {
            var re = semanticEngine(false);
            re.printCompactionNotice(6, 4);

            assertEquals("", output(),
                    "scripted/redirected transcripts must stay byte-identical");
        }
    }

    // ── printRouteHint ───────────────────────────────────────────────────

    @Nested
    class RouteHint {

        @Test
        void showsRouteLabel() {
            var re = semanticEngine(true);
            re.printRouteHint("rag");

            assertTrue(output().contains("  • route rag"), "Should include semantic route line");
            assertFalse(output().contains("[auto ->"), "Route hint should not use old bracket debug style");
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
            var re = semanticEngine(false);
            re.render(new Result.Ok("hello world"));

            assertTrue(output().contains("hello world"), "Should render Ok text");
            assertTrue(output().contains("┌─ answer"), "Ok answers should render in the answer pane");
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
            var re = semanticEngine(false);
            re.render(new Result.Ok("Answer body\n\n[Sources]\n - src/App.java#0\n - README.md#1\n"));

            String text = output();
            assertTrue(text.contains("Answer body"));
            assertTrue(text.contains("Sources"));
            assertTrue(text.contains("src/App.java#0"));
            assertFalse(text.contains("[Sources]"), "Raw source marker should not be blended into answer body");
        }

        @Test
        void rendersSourcesAsSeparateSectionForStreamedSuffix() {
            var re = semanticEngine(false);
            re.render(new Result.Streamed("Answer body\n\n[Sources]\n - src/App.java#0\n",
                    "\n\n[Sources]\n - src/App.java#0\n"));

            String text = output();
            assertTrue(text.contains("Sources"));
            assertTrue(text.contains("src/App.java#0"));
            assertFalse(text.contains("[Sources]"), "Streamed source suffix should be normalized");
        }

        @Test
        void streamedChunksUseSameAnswerRailAsOkResults() {
            var re = semanticEngine(true);

            re.render(new Result.StreamChunk("hello\nwor"));
            re.render(new Result.StreamChunk("ld"));
            re.render(new Result.StreamEnd());

            String text = output();
            assertTrue(text.contains("┌─ answer"));
            assertTrue(text.contains("│ hello"));
            assertTrue(text.contains("│ world"));
            assertTrue(text.contains("└─ answer"));
        }
    }

    @Nested
    class ToolProgress {

        @Test
        void rendersSemanticToolProgressLines() {
            var re = semanticEngine(true);

            re.printToolProgress("talos.read_file", "executing", "src/App.java");
            re.printToolProgress("talos.read_file", "completed", null);
            re.printToolProgress("talos.write_file", "warning", "no focused test");
            re.printToolProgress("talos.run_command", "error", "command rejected");

            String text = output();
            assertTrue(text.contains("  → read src/App.java"));
            assertTrue(text.contains("  ✓ read_file done"));
            assertTrue(text.contains("  ! verification warning no focused test"));
            assertTrue(text.contains("  x run_command failed command rejected"));
            assertFalse(text.contains("> Using"));
        }
    }
}

