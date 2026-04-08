package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for spinner suppression in non-interactive (piped) mode.
 */
final class RenderEngineSpinnerTest {

    @Test
    void spinner_suppressed_in_non_interactive_mode() throws Exception {
        var sink = new ByteArrayOutputStream();
        // Explicitly non-interactive
        var render = new RenderEngine(new Config(), new Redactor(), new PrintStream(sink), false);

        render.startSpinner();
        Thread.sleep(300); // Give spinner thread time to print if it were active
        render.stopSpinner();

        String output = sink.toString();
        assertFalse(output.contains("Thinking"), "Spinner should not print in non-interactive mode");
        assertFalse(output.contains("Answering"), "Spinner should not print in non-interactive mode");
    }

    @Test
    void spinner_runs_in_interactive_mode() throws Exception {
        var sink = new ByteArrayOutputStream();
        // Explicitly interactive
        var render = new RenderEngine(new Config(), new Redactor(), new PrintStream(sink), true);

        render.startSpinner();
        Thread.sleep(300); // Give spinner thread time to print
        render.stopSpinner();

        String output = sink.toString();
        // The spinner should have written something (the status label)
        assertFalse(output.isEmpty(), "Spinner should produce output in interactive mode");
    }

    @Test
    void default_constructor_with_byte_stream_is_non_interactive() throws Exception {
        var sink = new ByteArrayOutputStream();
        // Default constructor: ByteArrayOutputStream != System.out → non-interactive
        var render = new RenderEngine(new Config(), new Redactor(), new PrintStream(sink));

        render.startSpinner();
        Thread.sleep(300);
        render.stopSpinner();

        String output = sink.toString();
        assertFalse(output.contains("Thinking"), "Default non-System.out should be non-interactive");
    }

    @Test
    void stop_spinner_safe_when_not_started() {
        var sink = new ByteArrayOutputStream();
        var render = new RenderEngine(new Config(), new Redactor(), new PrintStream(sink), false);
        assertDoesNotThrow(render::stopSpinner);
    }

    @Test
    void stop_spinner_safe_when_interactive_not_started() {
        var sink = new ByteArrayOutputStream();
        var render = new RenderEngine(new Config(), new Redactor(), new PrintStream(sink), true);
        assertDoesNotThrow(render::stopSpinner);
    }
}

