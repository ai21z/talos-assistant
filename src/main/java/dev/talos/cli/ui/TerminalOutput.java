package dev.talos.cli.ui;

import org.jline.terminal.Terminal;
import org.jline.utils.WriterOutputStream;

import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Single authoritative output stream over a JLine terminal (T774).
 *
 * <p>JLine tracks the terminal's cursor/column/virtual-line state from the
 * characters that pass through {@code terminal.writer()}. Any write that
 * bypasses it (a raw {@code System.out.print}) diverges that model from
 * reality, and the next {@code readLine()} redraw then corrupts the display —
 * the documented Apr 2026 incident spliced leaked scrollback into the prompt
 * line. The fix is structural: every byte the interactive session prints —
 * banner, render engine, approval window, spinner, notices — flows through
 * one {@link PrintStream} backed by the terminal's writer, so JLine stays
 * authoritative over everything that reaches the terminal.
 *
 * <p>The stream encodes with {@code terminal.encoding()} and the adapter
 * decodes with the same charset before handing chars to the writer, so the
 * round-trip is lossless and Windows code-page mojibake cannot arise from a
 * charset mismatch between the two layers.
 */
public final class TerminalOutput {

    /** Autoflush PrintStream whose bytes reach the terminal through its writer. */
    public static PrintStream printStreamFor(Terminal terminal) {
        Charset charset = terminal.encoding();
        return new PrintStream(new WriterOutputStream(terminal.writer(), charset), true, charset);
    }

    private TerminalOutput() {
    }
}
