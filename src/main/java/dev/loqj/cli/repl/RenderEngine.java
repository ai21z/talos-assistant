package dev.loqj.cli.repl;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.util.Sanitize;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The only place that prints to the console.
 * - Applies redaction
 * - Removes <think>…</think> (stateful across chunks)
 * - Clips safely by Unicode code points
 * PR-1: Not wired into RunCmd yet (no behavior change).
 */
public final class RenderEngine {
    private final Redactor redactor;
    private final PrintStream out;
    private final int responseMaxCodePoints;
    private final int streamMaxCodePoints;
    private final ThinkFilter thinkFilter = new ThinkFilter();

    public RenderEngine(dev.loqj.core.Config cfg,
                        dev.loqj.core.security.Redactor redactor,
                        java.io.PrintStream out) {
        this.redactor = (redactor == null ? new dev.loqj.core.security.Redactor() : redactor);
        this.out = (out == null ? System.out : out);

        var lim = dev.loqj.core.CfgUtil.map(cfg.data.get("limits"));
        int resp = dev.loqj.core.CfgUtil.intAt(lim, "response_max_chars", 12_000);
        int strm = dev.loqj.core.CfgUtil.intAt(lim, "stream_max_chars", 8_000);

        this.responseMaxCodePoints = Math.max(0, resp);
        this.streamMaxCodePoints = Math.max(0, strm);
    }

    /* ===================== Public API for future wiring ===================== */

    public void render(Result r) {
        if (r == null) return;
        switch (r) {
            case Result.Ok ok -> println(sanitizeAndClip(ok.text, responseMaxCodePoints));
            case Result.Info info -> println(sanitizeAndClip(info.text, responseMaxCodePoints));
            case Result.Error err -> println(sanitizeAndClip(err.toString(), responseMaxCodePoints));
            case Result.Table table -> printTable(table);
            case Result.StreamStart start -> println(sanitizeAndClip(start.preface, responseMaxCodePoints));
            case Result.StreamChunk chunk -> printStreamChunk(chunk.text);
            case Result.StreamEnd ignored -> { flushPending(); println(""); }
        }
    }

    /** For streaming: feed a chunk and print the safe, filtered portion. */
    private void printStreamChunk(String rawChunk) {
        if (rawChunk == null || rawChunk.isEmpty()) return;
        String sanitized = Sanitize.stripAnsi(Sanitize.stripControls(rawChunk));
        String filtered = thinkFilter.accept(sanitized);
        if (filtered.isEmpty()) return;
        String clipped = clipCodePoints(filtered, streamMaxCodePoints - thinkFilter.totalOut());
        if (!clipped.isEmpty()) {
            out.print(redactor.redactBlock(clipped));
        }
    }

    /** For StreamEnd: flush any pending tail from the think-filter (non-injected). */
    private void flushPending() {
        String tail = thinkFilter.flushTail();
        if (!tail.isEmpty()) {
            String clipped = clipCodePoints(tail, streamMaxCodePoints - thinkFilter.totalOut());
            if (!clipped.isEmpty()) out.print(redactor.redactBlock(clipped));
        }
        thinkFilter.reset();
    }

    /* ===================== Helpers ===================== */

    private String sanitizeAndClip(String s, int max) {
        if (s == null) return "";
        String cleaned = Sanitize.sanitizeForPrompt(s);
        // Final hardening: strip any overlooked think tags
        cleaned = Sanitize.stripThinkTags(cleaned);
        String clipped = clipCodePoints(cleaned, max);
        return redactor.redactBlock(clipped);
    }

    private void println(String s) { out.println(s == null ? "" : s); }

    private void printTable(Result.Table t) {
        // Minimal monospace table; paging comes later.
        if (!t.title.isBlank()) println(t.title);
        if (!t.columns.isEmpty()) {
            println(String.join(" | ", t.columns));
            println("-".repeat(Math.max(3, t.columns.stream().mapToInt(String::length).sum()
                    + Math.max(0, (t.columns.size() - 1) * 3))));
        }
        for (var row : t.rows) {
            println(String.join(" | ", row));
        }
    }

    /** Unicode-safe clipping by code points (avoids breaking surrogate pairs/combining chars). */
    public static String clipCodePoints(String s, int maxCodePoints) {
        if (s == null || maxCodePoints <= 0) return "";
        int len = s.length();
        int cpCount = s.codePointCount(0, len);
        if (cpCount <= maxCodePoints) return s;
        int endIdx = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, endIdx);
    }

    /* ===================== Stateful <think> filter ===================== */

    /**
     * Removes <think>…</think> across chunk boundaries, case-insensitive, minimal buffering.
     * Keeps at most K trailing chars as lookbehind to detect boundary-spanning tags.
     */
    static final class ThinkFilter {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";
        private static final int K = Math.max(OPEN.length(), CLOSE.length()); // lookbehind window

        private final StringBuilder tail = new StringBuilder(K * 2);
        private boolean inThink = false;
        private int outSoFar = 0;

        /** Accept a new chunk; return safe text to print immediately. */
        String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            String data = tail + chunk; // prepend tail from previous call
            tail.setLength(0);

            StringBuilder out = new StringBuilder(data.length());
            int i = 0, n = data.length();
            while (i < n) {
                // Lowercase compare without allocating substring by peeking chars
                if (!inThink && regionMatchesCI(data, i, OPEN)) {
                    i += OPEN.length();
                    inThink = true;
                    continue;
                }
                if (inThink && regionMatchesCI(data, i, CLOSE)) {
                    i += CLOSE.length();
                    inThink = false;
                    continue;
                }
                if (!inThink) out.append(data.charAt(i));
                i++;
            }

            // Keep trailing K chars in tail to catch split tags on next chunk
            String outStr = out.toString();
            int safeLen = Math.max(0, outStr.length() - K);
            String emit = outStr.substring(0, safeLen);
            String remain = outStr.substring(safeLen);
            tail.append(remain);
            outSoFar += emit.codePointCount(0, emit.length());
            return emit;
        }

        /** Flush any remaining non-think tail at stream end. */
        String flushTail() {
            String s = tail.toString();
            tail.setLength(0);
            outSoFar += s.codePointCount(0, s.length());
            return s;
        }

        void reset() { tail.setLength(0); inThink = false; outSoFar = 0; }

        int totalOut() { return outSoFar; }

        private static boolean regionMatchesCI(String s, int offset, String token) {
            int n = token.length();
            if (offset + n > s.length()) return false;
            for (int j = 0; j < n; j++) {
                char a = s.charAt(offset + j);
                char b = token.charAt(j);
                if (Character.toLowerCase(a) != Character.toLowerCase(b)) return false;
            }
            return true;
        }
    }
}
