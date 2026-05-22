package dev.talos.runtime;

/**
 * Uniform result model for runtime turn and command outputs. Nothing prints directly;
 * a CLI adapter renders these.
 * Sealed for exhaustiveness in switch statements (Java 21).
 */
public sealed interface Result
        permits Result.Ok, Result.Info, Result.Error, Result.Table,
        Result.StreamStart, Result.StreamChunk, Result.StreamEnd, Result.Streamed, Result.TrustedInfo,
        Result.ToolProgress {

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

    /**
     * Content was already streamed to the terminal during execution.
     * The {@code suffix} (e.g., citations, metadata) is rendered after the streamed body.
     * The {@code fullText} is kept for memory/listener updates but NOT re-rendered.
     */
    public static final class Streamed implements Result {
        public final String fullText;
        public final String suffix;
        public Streamed(String fullText, String suffix) {
            this.fullText = fullText == null ? "" : fullText;
            this.suffix = suffix == null ? "" : suffix;
        }
        @Override public String toString() { return fullText + suffix; }
    }

    /* -------- Tool progress -------- */

    /**
     * Lightweight tool-execution progress event for terminal display.
     * Rendered as a single dimmed status line (not part of the answer body).
     *
     * @see dev.talos.tools.ToolProgressSink
     */
    public static final class ToolProgress implements Result {
        public final String toolName;
        public final String action;
        public final String detail;

        public ToolProgress(String toolName, String action, String detail) {
            this.toolName = toolName == null ? "" : toolName;
            this.action = action == null ? "" : action;
            this.detail = detail;
        }

        @Override public String toString() {
            return detail != null
                    ? action + " " + toolName + ": " + detail
                    : action + " " + toolName;
        }
    }

    /* -------- Convenience factories -------- */

    static Info info(String s) { return new Info(s); }
    static Ok ok(String s) { return new Ok(s); }
    static Error error(String s, int code) { return new Error(s, code); }
    static TrustedInfo trustedInfo(String s) { return new TrustedInfo(s); }
}
