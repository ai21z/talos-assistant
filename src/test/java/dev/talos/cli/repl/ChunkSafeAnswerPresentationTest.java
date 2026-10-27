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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSafeAnswerPresentationTest {
    private static final String SECRET = "SYNTHETIC_VALUE_9f83b42c";
    private static final String PATH = "C:\\private-fixture\\only-local\\notes.txt";
    private static final String SOURCE = "Useful answer. api_key=" + SECRET + " Path " + PATH
            + ". Unicode \uD83D\uDE80. Literal `<button>Go</button>`.";

    @Test
    void everyPartitionIsProjectedBeforeAnyAnswerBytesAreWritten() {
        String expected = renderWhole(SOURCE);
        assertTrue(expected.contains("Useful answer"));
        assertTrue(expected.contains("[secret]"));
        assertTrue(expected.contains("[path]"));
        for (int split = 0; split <= SOURCE.length(); split++) {
            var bytes = new ByteArrayOutputStream();
            var engine = renderer(bytes);
            var sink = engine.answerStreamSink(null);
            sink.accept(SOURCE.substring(0, split));
            sink.accept(SOURCE.substring(split));
            assertEquals("", bytes.toString(StandardCharsets.UTF_8), "Uncommitted text at " + split);
            engine.render(new Result.StreamEnd());
            assertEquals(0, bytes.size(), "EOF is not answer admission");
            engine.render(new Result.Ok(SOURCE));
            assertEquals(expected, bytes.toString(StandardCharsets.UTF_8), "Partition " + split);
        }
        var bytes = new ByteArrayOutputStream();
        var engine = renderer(bytes);
        var sink = engine.answerStreamSink(null);
        for (int i = 0; i < SOURCE.length(); i++) sink.accept(SOURCE.substring(i, i + 1));
        assertEquals(0, bytes.size());
        engine.render(new Result.StreamEnd());
        assertEquals(0, bytes.size());
        engine.render(new Result.Ok(SOURCE));
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8));
        assertFalse(expected.contains(SECRET));
        assertFalse(expected.contains("private-fixture"));
        assertFalse(expected.contains("only-local"));
    }

    @Test
    void shutdownAndReplacementDiscardUncommittedRawCandidates() {
        for (boolean shutdown : List.of(false, true)) {
            var bytes = new ByteArrayOutputStream();
            var engine = renderer(bytes);
            engine.answerStreamSink(null).accept("api_key=" + SECRET);
            if (shutdown) engine.shutdown();
            else engine.render(new Result.Ok("Selected useful answer."));
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertFalse(output.contains(SECRET));
            assertFalse(output.contains("api_key"));
            if (!shutdown) assertTrue(output.contains("Selected useful answer"));
            engine.render(new Result.StreamEnd());
            assertEquals(output, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void legacyFragmentsAreRejectedWithoutRetainingAnyRawTail() {
        var bytes = new ByteArrayOutputStream();
        var engine = renderer(bytes);
        assertThrows(IllegalArgumentException.class,
                () -> engine.render(new Result.StreamChunk("Uncommitted prefix api_key=")));
        engine.answerStreamSink(null).accept("x".repeat(AnswerCodeLiteralStreamProjection.MAX_SOURCE_CHARS));
        engine.answerStreamSink(null).accept(SECRET);
        assertEquals(0, bytes.size());
        engine.render(new Result.StreamEnd());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("Uncommitted prefix"));
        assertFalse(output.contains(SECRET));
        assertEquals("", output);
        bytes.reset();
        engine.render(new Result.Ok("Next useful answer."));
        assertEquals(renderWhole("Next useful answer."), bytes.toString(StandardCharsets.UTF_8));
    }

    private static String renderWhole(String text) {
        var bytes = new ByteArrayOutputStream();
        renderer(bytes).render(new Result.Ok(text));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static RenderEngine renderer(ByteArrayOutputStream bytes) {
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, false, false, true, false);
        var redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of(SECRET))));
        return new RenderEngine(new Config(), redactor,
                new PrintStream(bytes, true, StandardCharsets.UTF_8), false, CliTheme.forCapabilities(caps));
    }
}
