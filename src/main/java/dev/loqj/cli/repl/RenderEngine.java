package dev.loqj.cli.repl;

import dev.loqj.core.Config;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.util.Sanitize;

import java.io.PrintStream;
import java.util.List;

/** Renders Results to the terminal with consistent sanitize → redact → print. */
public final class RenderEngine {
    private final Config cfg;
    private final Redactor redactor;
    private final PrintStream out;

    public RenderEngine(Config cfg, Redactor redactor, PrintStream out) {
        this.cfg = (cfg == null ? new Config() : cfg);
        this.redactor = (redactor == null ? new Redactor() : redactor);
        this.out = (out == null ? System.out : out);
    }

    public void render(Result r) {
        if (r == null) {
            println(sro("(null result)"));
            return;
        }

        if (r instanceof Result.Ok ok) {
            println(sro(ok.text));
            return;
        }
        if (r instanceof Result.Info info) {
            println(sro(info.text));
            return;
        }
        if (r instanceof Result.TrustedInfo trustedInfo) {
            // Bypass path redaction for trusted workspace information
            String cleaned = Sanitize.sanitizeForOutput(trustedInfo.text == null ? "" : trustedInfo.text);
            println(cleaned); // Skip redactor.redactBlock() for trusted content
            return;
        }
        if (r instanceof Result.Error err) {
            String msg = sro(err.message);
            if (err.code > 0) println("[error " + err.code + "] " + msg);
            else println("[error] " + msg);
            return;
        }
        if (r instanceof Result.Table tbl) {
            renderTable(tbl);
            return;
        }
        if (r instanceof Result.StreamStart ss) {
            // optional preface then no trailing newline required, but printing one is fine
            String pf = ss.preface == null ? "" : ss.preface;
            if (!pf.isEmpty()) println(sro(pf));
            return;
        }
        if (r instanceof Result.StreamChunk chunk) {
            print(sroInline(chunk.text)); // do not force newline between chunks
            return;
        }
        if (r instanceof Result.StreamEnd) {
            println(""); // ensure we end on a new line after streaming
            return;
        }

        // Fallback for any future Result variants
        println(sro(r.toString()));
    }

    /* ---------------- helpers ---------------- */

    private void renderTable(Result.Table tbl) {
        String title = sro(tbl.title);
        if (!title.isEmpty()) println(title);

        List<String> cols = (tbl.columns == null ? List.of() : tbl.columns);
        List<List<String>> rows = (tbl.rows == null ? List.of() : tbl.rows);

        if (!cols.isEmpty()) {
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) header.append(" | ");
                header.append(sroInline(cols.get(i)));
            }
            println(header.toString());
            println("-".repeat(Math.max(3, header.length())));
        }

        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) line.append(" | ");
                line.append(sroInline(row.get(i)));
            }
            println(line.toString());
        }
    }

    /** sanitize → redact for multi-line blocks. */
    private String sro(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactBlock(cleaned);
    }

    /** sanitize → redact for single-line/inline chunks. */
    private String sroInline(String s) {
        String cleaned = Sanitize.sanitizeForOutput(s == null ? "" : s);
        return redactor.redactLine(cleaned);
    }

    private void println(String s) { out.println(s == null ? "" : s); }
    private void print(String s)   { out.print(s == null ? "" : s); }
}
