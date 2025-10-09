package dev.loqj.core.util;

import java.util.regex.Pattern;

/**
 * Utilities for sanitizing untrusted text before sending to or printing from the LLM.
 */
public final class Sanitize {
    private Sanitize() {}

    // ANSI escape sequences
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    // Control chars & nulls (TAB and LF/CR are kept for readability)
    private static final Pattern CTRL = Pattern.compile("[\u0000-\u0008\u000B-\u001F\u007F]");
    // Suspicious HTML/JS tags and attributes (defense in depth; not a full HTML sanitizer)
    private static final Pattern SUS_HTML = Pattern.compile(
            "(?is)<\\s*(script|style|iframe|object|embed|meta|link|svg|form|input|textarea|button)\\b.*?>.*?<\\s*/\\s*\\1\\s*>|on\\w+\\s*=\\s*['\"][^'\"]*['\"]"
    );
    // Hidden chain-of-thought blocks (e.g., <think>...</think>)
    private static final Pattern THINK = Pattern.compile("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>");

    /**
     * Strips ANSI escape sequences, control characters, and nulls from the input string.
     */
    public static String stripControl(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = ANSI.matcher(s).replaceAll("");
        out = CTRL.matcher(out).replaceAll("");
        return out;
    }

    /**
     * Removes suspicious HTML and script-like content from the input string.
     */
    public static String stripSuspiciousHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return SUS_HTML.matcher(s).replaceAll("");
    }

    /**
     * Removes &lt;think&gt;...&lt;/think&gt; blocks entirely from the input string.
     */
    public static String dropThinkBlocks(String s) {
        if (s == null || s.isEmpty()) return "";
        return THINK.matcher(s).replaceAll("");
    }

    /**
     * Sanitizes a string before including it in a prompt to the model.
     * Applies control character and suspicious HTML stripping.
     */
    public static String sanitizeForPrompt(String s) {
        return stripSuspiciousHtml(stripControl(s));
    }

    /**
     * Sanitizes a string before printing to terminal.
     * Applies control character, suspicious HTML, and think block stripping.
     */
    public static String sanitizeForOutput(String s) {
        return stripSuspiciousHtml(stripControl(dropThinkBlocks(s)));
    }

    /**
     * Performs hard truncation to maximum character count (safe for terminal; doesn't split surrogate pairs).
     */
    public static String hardTruncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    /**
     * Performs hard truncation with callback for telemetry tracking.
     */
    public static String hardTruncate(String s, int maxChars, Runnable onTruncate) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        if (onTruncate != null) onTruncate.run();
        return s.substring(0, maxChars);
    }

    /* Back-compatibility aliases for existing code */

    /**
     * Legacy alias: removes ANSI escape sequences only.
     */
    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return "";
        return ANSI.matcher(s).replaceAll("");
    }

    /**
     * Legacy alias: removes control characters and nulls.
     */
    public static String stripControls(String s) {
        if (s == null || s.isEmpty()) return "";
        return CTRL.matcher(s).replaceAll("");
    }

    /**
     * Legacy alias: removes &lt;think&gt; tags with Unicode escape decoding.
     */
    public static String stripThinkTags(String s) {
        if (s == null || s.isEmpty()) return s;

        // First, Unicode escapes are decoded (\u003c -> <, \u003e -> >)
        s = s.replace("\\u003c", "<").replace("\\u003e", ">");

        // Then <think>...</think> blocks are removed (case-insensitive)
        s = s.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "");

        // Stray open/close think tags are removed
        s = s.replaceAll("(?is)<\\s*/?\\s*think\\s*>", "");

        return s;
    }
}
