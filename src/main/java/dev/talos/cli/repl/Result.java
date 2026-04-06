package dev.talos.cli.repl;

/**
 * Uniform result model for CLI outputs. Nothing prints directly; a RenderEngine renders these.
 * Sealed for exhaustiveness in switch statements (Java 21).
 */
public sealed interface Result
        permits Result.Ok, Result.Info, Result.Error, Result.Table,
        Result.StreamStart, Result.StreamChunk, Result.StreamEnd, Result.TrustedInfo {

    /* -------- Simple text results -------- */

    public static final class Ok implements Result {
        public final String text;
        public Ok(String text) { this.text = text == null ? "" : text; }
        @Override public String toString() { return text; }
    }

    public static final class Info implements Result {
        public final String text;
        public Info(String text) { this.text = text == null ? "" : text; }
        @Override public String toString() { return text; }
    }

    /**
     * Trusted information that bypasses path redaction (for workspace commands).
     */
    public static final class TrustedInfo implements Result {
        public final String text;
        public TrustedInfo(String text) { this.text = text == null ? "" : text; }
        @Override public String toString() { return text; }
    }

    public static final class Error implements Result {
        public final String message;
        public final int code; // 2xx: user error, 3xx: recoverable mode error, 5xx: unexpected
        public Error(String message, int code) {
            this.message = message == null ? "" : message;
            this.code = code;
        }
        @Override public String toString() { return "[" + code + "] " + message; }
    }

    /* -------- Structured results -------- */

    public static final class Table implements Result {
        public final String title;
        public final java.util.List<String> columns;
        public final java.util.List<java.util.List<String>> rows;
        public Table(String title,
                     java.util.List<String> columns,
                     java.util.List<java.util.List<String>> rows) {
            this.title = title == null ? "" : title;
            this.columns = columns == null ? java.util.List.of() : java.util.List.copyOf(columns);
            this.rows = rows == null ? java.util.List.of() : java.util.List.copyOf(rows);
        }
    }

    /* -------- Streaming lifecycle -------- */

    public static final class StreamStart implements Result {
        public final String preface;
        public StreamStart(String preface) { this.preface = preface == null ? "" : preface; }
    }

    public static final class StreamChunk implements Result {
        public final String text;
        public StreamChunk(String text) { this.text = text == null ? "" : text; }
    }

    public static final class StreamEnd implements Result {
        @Override public String toString() { return "<end>"; }
    }

    /* -------- Convenience factories -------- */

    static Info info(String s) { return new Info(s); }
    static Ok ok(String s) { return new Ok(s); }
    static Error error(String s, int code) { return new Error(s, code); }
    static TrustedInfo trustedInfo(String s) { return new TrustedInfo(s); }
}
