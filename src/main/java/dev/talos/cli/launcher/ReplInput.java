package dev.talos.cli.launcher;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.Function;

/**
 * Single owner for REPL input.
 *
 * <p>Interactive sessions use JLine. Scripted sessions use a plain
 * {@link BufferedReader} so redirected stdin is consumed deterministically and
 * approval responses cannot drift into a later REPL turn.
 */
final class ReplInput {
    private final LineReader lineReader;
    private final BufferedReader scriptedReader;
    private final PrintStream out;

    private ReplInput(LineReader lineReader, BufferedReader scriptedReader, PrintStream out) {
        this.lineReader = lineReader;
        this.scriptedReader = scriptedReader;
        this.out = out == null ? System.out : out;
    }

    static ReplInput jline(LineReader lineReader) {
        return new ReplInput(Objects.requireNonNull(lineReader, "lineReader"), null, null);
    }

    static ReplInput scripted(InputStream in, PrintStream out) {
        return scripted(in, out, Charset.defaultCharset());
    }

    static ReplInput scripted(InputStream in, PrintStream out, Charset charset) {
        InputStream effectiveIn = in == null ? System.in : in;
        Charset effectiveCharset = charset == null ? Charset.defaultCharset() : charset;
        return new ReplInput(null,
                new BufferedReader(new InputStreamReader(effectiveIn, effectiveCharset)),
                out);
    }

    String readLine(String prompt) {
        if (lineReader != null) {
            return lineReader.readLine(prompt);
        }
        if (prompt != null && !prompt.isEmpty()) {
            out.print(prompt);
            out.flush();
        }
        try {
            return scriptedReader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Function<String, String> approvalReader() {
        return prompt -> {
            try {
                return readLine(prompt);
            } catch (EndOfFileException | UserInterruptException | UncheckedIOException e) {
                return null;
            }
        };
    }
}
