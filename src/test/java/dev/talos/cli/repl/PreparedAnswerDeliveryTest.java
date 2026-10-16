package dev.talos.cli.repl;

import dev.talos.cli.ui.TerminalOutput;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.AnswerDeliveryObservation;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnResult;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PreparedAnswerDeliveryTest {
    @TempDir Path workspace;

    @Test
    void asciiNormalizationReceivesCompleteDestinationRedaction() throws Exception {
        var config = new Config();
        var redactor = new Redactor(java.util.Map.of("redact", java.util.Map.of("secrets", java.util.List.of("fixture-secret"))));
        var context = Context.builder(config).redactor(redactor).build();
        var processor = new TurnProcessor((request, root, ctx) -> Optional.of(new Result.Ok("fixture\u2013secret")));
        try (var session = new Session(workspace, config)) {
            var turn = processor.process(session, "hello", context);
            var bytes = new ByteArrayOutputStream();
            var renderer = ExactAnswerRenderFixture.create(bytes, false, false, 96, redactor);
            renderer.render(turn.result());
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("fixture-secret"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("[secret]"));
        }
    }

    @Test
    void preparationIsNotDeliveryAndRenderingDoesNotRepeatSettlement() throws Exception {
        var publications = new AtomicInteger();
        var turn = prepare("A useful answer.", publications);
        var ok = assertInstanceOf(Result.Ok.class, turn.result());
        assertNotNull(ok.presentation);
        var bytes = new ByteArrayOutputStream();
        var renderer = new RenderEngine(new Config(), new Redactor(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), false);
        assertNull(renderer.lastAnswerDelivery());
        renderer.render(ok);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("A useful answer."));
        assertEquals(AnswerDeliveryObservation.State.NO_DETECTED_SINK_ERROR, renderer.lastAnswerDelivery().state());
        assertEquals(ok.presentation.id(), renderer.lastAnswerDelivery().presentationId());
        String first = bytes.toString(StandardCharsets.UTF_8);
        renderer.render(ok);
        assertEquals(first, bytes.toString(StandardCharsets.UTF_8));
        assertEquals(1, publications.get());
    }

    @Test
    void failedOuterStreamIsUncertainAndIsNotRetried() throws Exception {
        var publications = new AtomicInteger();
        var turn = prepare("A useful answer.", publications);
        var writes = new AtomicInteger();
        var broken = new OutputStream() {
            @Override public void write(int value) throws IOException {
                writes.incrementAndGet();
                throw new IOException("synthetic output failure");
            }
        };
        var renderer = new RenderEngine(new Config(), new Redactor(), new PrintStream(broken), false);
        renderer.render(turn.result());
        assertEquals(AnswerDeliveryObservation.State.FAILED_OR_UNCERTAIN, renderer.lastAnswerDelivery().state());
        int attempted = writes.get();
        assertTrue(attempted > 0);
        renderer.render(turn.result());
        assertEquals(attempted, writes.get());
        assertEquals(1, publications.get());
        assertEquals("A useful answer.", assertInstanceOf(Result.Ok.class, turn.result()).text);
    }

    @Test
    void underlyingJLineWriterErrorIsDetected() throws Exception {
        var broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("synthetic writer failure"); }
        };
        try (var terminal = new DumbTerminal("sink-failure", "dumb", new ByteArrayInputStream(new byte[0]),
                broken, StandardCharsets.UTF_8)) {
            var renderer = new RenderEngine(new Config(), new Redactor(), TerminalOutput.printStreamFor(terminal), false);
            renderer.render(prepare("A useful answer.", new AtomicInteger()).result());
            assertEquals(AnswerDeliveryObservation.State.FAILED_OR_UNCERTAIN, renderer.lastAnswerDelivery().state());
        }
    }

    private TurnResult prepare(String answer, AtomicInteger publications) throws Exception {
        var config = new Config();
        var context = Context.builder(config).build();
        var processor = new TurnProcessor((request, root, ctx) -> Optional.of(new Result.Ok(answer)));
        processor.addListener(new dev.talos.runtime.SessionListener() {
            @Override public void onTurnComplete(TurnResult result, String request) { publications.incrementAndGet(); }
        });
        try (var session = new Session(workspace, config)) {
            return processor.process(session, "Explain a concept without inspecting files.", context);
        }
    }
}
