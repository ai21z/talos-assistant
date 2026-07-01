package dev.talos.cli.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T774: bytes printed through the adapter must reach the terminal's writer. */
class TerminalOutputTest {

    @Test
    void printedBytesReachTheTerminalWriterLosslessly() throws Exception {
        var sink = new ByteArrayOutputStream();
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .stdoutEncoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(new byte[0]), sink)
                .build()) {
            PrintStream out = TerminalOutput.printStreamFor(terminal);

            out.print("plain ascii, ");
            out.println("unicode rail │ and check ✓");
            out.flush();
            terminal.flush();

            String written = sink.toString(StandardCharsets.UTF_8);
            assertTrue(written.contains("plain ascii, unicode rail │ and check ✓"),
                    "terminal writer must receive the exact printed characters: " + written);
        }
    }

    @Test
    void printWithoutNewlineIsVisibleAfterFlush() throws Exception {
        // Streaming chunks rarely end in newlines; an explicit flush must be
        // enough to push them through the encoder/decoder round-trip.
        var sink = new ByteArrayOutputStream();
        try (Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .stdoutEncoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(new byte[0]), sink)
                .build()) {
            PrintStream out = TerminalOutput.printStreamFor(terminal);

            out.print("partial-chunk");
            out.flush();
            terminal.flush();

            String written = sink.toString(StandardCharsets.UTF_8);
            assertTrue(written.contains("partial-chunk"), written);
            assertFalse(written.contains("\n"), "no newline should have been added: " + written);
        }
    }
}
