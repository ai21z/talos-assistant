package dev.talos.cli.ui;

import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T774: bytes printed through the adapter must reach the terminal's writer. */
class TerminalOutputTest {

    @Test
    void printedBytesReachTheTerminalWriterLosslessly() throws Exception {
        var sink = new ByteArrayOutputStream();
        try (DumbTerminal terminal = directDumbTerminal(sink)) {
            assertNull(terminal.getProvider(),
                    "the fixture must not select a platform terminal provider");
            PrintStream out = TerminalOutput.printStreamFor(terminal);

            out.print("plain ascii, ");
            out.println("unicode rail │ and check ✓");
            out.flush();

            String written = sink.toString(StandardCharsets.UTF_8);
            assertEquals(
                    "plain ascii, unicode rail │ and check ✓" + System.lineSeparator(),
                    written,
                    "terminal writer must receive exactly the printed characters");
        }
    }

    @Test
    void printWithoutNewlineIsVisibleAfterFlush() throws Exception {
        // Streaming chunks rarely end in newlines; an explicit flush must be
        // enough to push them through the encoder/decoder round-trip.
        var sink = new ByteArrayOutputStream();
        try (DumbTerminal terminal = directDumbTerminal(sink)) {
            assertNull(terminal.getProvider(),
                    "the fixture must not select a platform terminal provider");
            PrintStream out = TerminalOutput.printStreamFor(terminal);

            out.print("partial-chunk");
            out.flush();

            String written = sink.toString(StandardCharsets.UTF_8);
            assertEquals("partial-chunk", written);
            assertFalse(written.contains("\n"), "no newline should have been added: " + written);
        }
    }

    private static DumbTerminal directDumbTerminal(ByteArrayOutputStream sink) throws IOException {
        return new DumbTerminal(
                "terminal-output-test",
                "dumb",
                new ByteArrayInputStream(new byte[0]),
                sink,
                StandardCharsets.UTF_8);
    }
}
