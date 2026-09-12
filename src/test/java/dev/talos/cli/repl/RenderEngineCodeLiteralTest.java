package dev.talos.cli.repl;

import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.ColorPolicy;
import dev.talos.cli.ui.TerminalCapabilities;
import dev.talos.core.Config;
import dev.talos.core.llm.RecordedMixedCodeAnswer;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import dev.talos.runtime.ToolCallStreamFilter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class RenderEngineCodeLiteralTest {
    private static final String SCRIPT = "<script src=\"script.js\"></script>";
    private static final String BUTTON = "<button onclick=\"calculate()\">Calculate</button>";
    private static final String MIXED = "The selector is `#calculate`.\n\n"
            + "```html\n" + BUTTON + "\n```\n\n"
            + "The page loads `" + SCRIPT + "`, with `<p id=\"result\">` as output.";

    @Test
    void completeCapturedMixedAnswerKeepsAllCodeOnBothDisplayPaths() {
        String source = RecordedMixedCodeAnswer.text();
        var capture = new ByteArrayOutputStream();
        renderer(capture, true, new Redactor()).render(new Result.Ok(source));
        for (String output : List.of(capture.toString(StandardCharsets.UTF_8),
                stream(source, 1, false), stream(source, 7, true))) {
            assertTrue(output.contains("<button id=\"calculate\">Calculate</button>"));
            assertTrue(output.contains(SCRIPT));
            assertTrue(output.contains("`<p id=\"result\">`"));
            assertTrue(output.contains("`<script src=\"scripts.js\">`"));
            assertFalse(output.contains("answer display omitted"));
            assertFalse(output.contains("answer display truncated"));
        }
    }

    @Test
    void completeAnswerPreservesClosedArbitraryHtmlCode() {
        var capture = new ByteArrayOutputStream();
        renderer(capture, true, new Redactor()).render(new Result.Ok(MIXED));
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(BUTTON));
        assertTrue(output.contains(SCRIPT));
        assertTrue(output.contains("`<p id=\"result\">`"));
    }

    @Test
    void streamPreservationDoesNotDependOnProviderChunkBoundaries() {
        String expected = stream(MIXED, MIXED.length(), false);
        assertTrue(expected.contains(SCRIPT));
        assertTrue(expected.contains(BUTTON));
        for (int width : List.of(1, 2, 3, 7, 19)) {
            assertEquals(expected, stream(MIXED, width, false), "chunk width " + width);
            assertEquals(expected, stream(MIXED, width, true), "writer chunk width " + width);
        }
    }

    @Test
    void ordinaryPrefixStreamsBeforeCodeSettles() {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        engine.render(new Result.StreamChunk("Ordinary prose now. "));
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains("Ordinary prose now."));
        engine.render(new Result.StreamChunk("`" + SCRIPT));
        assertFalse(capture.toString(StandardCharsets.UTF_8).contains(SCRIPT));
        engine.render(new Result.StreamChunk("` Done."));
        engine.render(new Result.StreamEnd());
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains(SCRIPT));
    }

    @Test
    void tildesAndLongInlineDelimitersAreRetainedAcrossChunks() {
        String source = "~~~html\n" + SCRIPT + "\n~~~\n"
                + "Use ``<button title=\"`literal`\">Go</button>``.";
        String output = stream(source, 1, true);
        assertTrue(output.contains(SCRIPT));
        assertTrue(output.contains("<button title=\"`literal`\">Go</button>"));
    }

    @Test
    void malformedHiddenControlAndOutsideMarkupKeepSanitation() {
        List<String> sources = List.of(
                "`" + SCRIPT,
                "```html\n" + SCRIPT,
                "`<script>visible\u001B[31m</script>`",
                "`<script><think>hidden-marker</think></script>`",
                "`" + SCRIPT + "` <script>outside-marker</script>",
                "<script>before `" + BUTTON + "` after</script>",
                "`" + SCRIPT + "` <think>hidden-marker</think>");
        for (String source : sources) {
            String output = stream(source, 1, false);
            assertFalse(output.contains(SCRIPT), source);
            assertFalse(output.contains(BUTTON), source);
            assertFalse(output.contains("hidden-marker"), source);
            assertFalse(output.contains("\u001B"), source);
        }
    }

    @Test
    void toolProtocolFilterStillDominatesLiteralPresentation() {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        var filter = new ToolCallStreamFilter(engine.answerStreamSink(null));
        String source = "```json\n{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"a.txt\"}}\n```";
        for (int index = 0; index < source.length(); index++) {
            filter.accept(source.substring(index, index + 1));
        }
        filter.flush();
        engine.render(new Result.StreamEnd());
        assertFalse(capture.toString(StandardCharsets.UTF_8).contains("talos.read_file"));
    }

    @Test
    void literalPresentationStillAppliesAsciiAndSecretRedaction() {
        var capture = new ByteArrayOutputStream();
        var redactor = new Redactor(Map.of("redact", Map.of(
                "paths", false, "ips", false, "secrets", List.of("fixture-secret"))));
        RenderEngine engine = renderer(capture, false, redactor);
        String source = "`<button onclick=\"fixture-secret\">Go — now</button>`";
        engine.render(new Result.StreamChunk(source));
        engine.render(new Result.StreamEnd());
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("<button onclick=\"[secret]\">Go - now</button>"));
        assertFalse(output.contains("fixture-secret"));
        assertFalse(output.contains("—"));
    }

    @Test
    void retentionCannotSplitASingleChunkSecretBeforeRedaction() {
        for (String secret : List.of("fixture`secret", "fixture~secret")) {
            var capture = new ByteArrayOutputStream();
            var redactor = new Redactor(Map.of("redact", Map.of(
                    "secrets", List.of(java.util.regex.Pattern.quote(secret)))));
            RenderEngine engine = renderer(capture, true, redactor);
            engine.render(new Result.StreamChunk("Before " + secret + " after. `" + SCRIPT + "`"));
            engine.render(new Result.StreamEnd());
            String output = capture.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("[secret]"));
            assertFalse(output.contains(secret));
            assertFalse(output.contains("fixture"));
        }
    }

    @Test
    void streamCloseAndShutdownFlushThroughTheOriginalWriterAndReset() {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        var writer = new StringBuilder();
        Consumer<String> sink = engine.answerStreamSink(writer::append);
        sink.accept("`" + SCRIPT + "`");
        engine.render(new Result.Streamed("`" + SCRIPT + "`", ""));
        assertTrue(writer.toString().contains(SCRIPT));
        writer.setLength(0);
        sink.accept("`" + BUTTON + "`");
        engine.shutdown();
        assertTrue(writer.toString().contains(BUTTON));
        assertFalse(writer.toString().contains(SCRIPT));
    }

    @Test
    void boundedRetentionReportsDisplayLossWithoutChangingNextAnswer() {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        engine.render(new Result.StreamChunk("`" + SCRIPT));
        engine.render(new Result.StreamChunk("x".repeat(AnswerCodeLiteralStreamProjection.MAX_SOURCE_CHARS)));
        engine.render(new Result.StreamChunk("overflow-tail`"));
        engine.render(new Result.StreamEnd());
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("answer display truncated"));
        assertFalse(output.contains(SCRIPT));
        assertFalse(output.contains("overflow-tail"));
        capture.reset();
        engine.render(new Result.StreamChunk("`" + SCRIPT + "`"));
        engine.render(new Result.StreamEnd());
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains(SCRIPT));
    }

    @Test
    void informationAndErrorsDoNotAcquireTheAnswerException() {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        engine.render(new Result.Info("`" + SCRIPT + "`"));
        engine.render(new Result.Error("`" + SCRIPT + "`", 200));
        assertFalse(capture.toString(StandardCharsets.UTF_8).contains(SCRIPT));
    }

    private static String stream(String source, int width, boolean useWriter) {
        var capture = new ByteArrayOutputStream();
        RenderEngine engine = renderer(capture, true, new Redactor());
        var writer = new StringBuilder();
        Consumer<String> sink = useWriter ? engine.answerStreamSink(writer::append)
                : chunk -> engine.render(new Result.StreamChunk(chunk));
        for (int start = 0; start < source.length(); start += width) {
            sink.accept(source.substring(start, Math.min(source.length(), start + width)));
        }
        engine.render(new Result.StreamEnd());
        return useWriter ? writer.toString() : capture.toString(StandardCharsets.UTF_8);
    }

    private static RenderEngine renderer(ByteArrayOutputStream capture, boolean unicode, Redactor redactor) {
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, false, false, unicode, false);
        return new RenderEngine(new Config(), redactor,
                new PrintStream(capture, true, StandardCharsets.UTF_8), false,
                CliTheme.forCapabilities(caps));
    }
}
