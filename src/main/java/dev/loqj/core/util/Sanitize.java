package dev.loqj.core.util;

import java.util.regex.Pattern;

/** Utilities to sanitize untrusted text before sending to/printing from the LLM. */
public final class Sanitize {
    private Sanitize() {}

    // ANSI escapes
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    // Control chars & nulls (keep TAB and LF/CR for readability)
    private static final Pattern CTRL = Pattern.compile("[\u0000-\u0008\u000B-\u001F\u007F]");
    // Very light HTML/JS suspicious tags/attrs (defense in depth; not a full HTML sanitizer)
    private static final Pattern SUS_HTML = Pattern.compile(
            "(?is)<\\s*(script|style|iframe|object|embed|meta|link|svg|form|input|textarea|button)\\b.*?>.*?<\\s*/\\s*\\1\\s*>|on\\w+\\s*=\\s*['\"][^'\"]*['\"]"
    );
    // Hidden chain-of-thought blocks (e.g., <think>...</think>)
    private static final Pattern THINK = Pattern.compile("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>");

    /* ---------------- New API ---------------- */

    /** Strip ANSI, control chars, and nulls. */
    public static String stripControl(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = ANSI.matcher(s).replaceAll("");
        out = CTRL.matcher(out).replaceAll("");
        return out;
    }

    /** Remove suspicious HTML/script-ish content. */
    public static String stripSuspiciousHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return SUS_HTML.matcher(s).replaceAll("");
    }

    /** Drop <think>…</think> blocks entirely. */
    public static String dropThinkBlocks(String s) {
        if (s == null || s.isEmpty()) return "";
        return THINK.matcher(s).replaceAll("");
    }

    /** Sanitize a string before including it in a prompt to the model. */
    public static String sanitizeForPrompt(String s) {
        // Keep aliases internally for consistency
        return stripSuspiciousHtml(stripControl(s));
    }

    /** Sanitize a string before printing to terminal. */
    public static String sanitizeForOutput(String s) {
        return stripSuspiciousHtml(stripControl(dropThinkBlocks(s)));
    }

    /** Hard truncate to max characters (safe for terminal; doesn’t split surrogate pairs). */
    public static String hardTruncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    /* ---------------- Back-compat aliases (for existing code) ---------------- */

    /** Alias for legacy code: remove ANSI only. */
    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return "";
        return ANSI.matcher(s).replaceAll("");
    }

    /** Alias for legacy code: remove control chars (and nulls). */
    public static String stripControls(String s) {
        if (s == null || s.isEmpty()) return "";
        return CTRL.matcher(s).replaceAll("");
    }

    /** Alias for legacy code: drop <think> tags. */
    public static String stripThinkTags(String s) {
        if (s == null || s.isEmpty()) return s;
        // Literal <think>...</think>
        s = s.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "");
        // Escaped \u003cthink\u003e...\u003c/think\u003e
        s = s.replaceAll("(?is)\\u003c\\s*think\\s*\\u003e.*?\\u003c\\s*/\\s*think\\s*\\u003e", "");
        // Stray open/close, literal and escaped
        s = s.replaceAll("(?is)<\\s*/?\\s*think\\s*>", "");
        s = s.replaceAll("(?is)\\u003c\\s*/?\\s*think\\s*\\u003e", "");
        return s;
    }
}
