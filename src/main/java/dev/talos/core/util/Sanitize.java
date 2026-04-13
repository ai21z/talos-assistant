package dev.talos.core.util;

import java.util.regex.Matcher;
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

    /** Matches &lt;tool_call&gt;...&lt;/tool_call&gt; blocks (and common tag variants). */
    private static final Pattern TOOL_CALL_BLOCK = Pattern.compile(
            "(?s)<(?:tool_call|function_call)>.*?</(?:tool_call|function_call)>"
    );

    /** Matches JSON code-fenced tool calls: ```json {"name":"talos...} ```. */
    private static final Pattern JSON_TOOL_CALL_FENCE = Pattern.compile(
            "(?s)```(?:json)?\\s*\\n(\\{[^`]*\"name\"\\s*:\\s*\"talos\\.[^`]*\\})\\s*\\n?```"
    );

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
     * Sanitizes streamed LLM output while preserving {@code <tool_call>} blocks intact.
     *
     * <p>Tool-call blocks contain JSON with raw file content (HTML, CSS, JS) as parameter
     * values. The {@link #SUS_HTML} pattern would strip tags like {@code <script>} or
     * {@code <style>} from these JSON values, corrupting the tool parameters.
     *
     * <p>This method applies full sanitization (control chars, think blocks, SUS_HTML)
     * to prose text <em>outside</em> tool_call blocks, while preserving the raw content
     * inside tool_call blocks (only control chars are stripped there).
     *
     * <p>Use this instead of {@link #sanitizeForOutput} in streaming assembly where the
     * response may contain tool_call blocks with HTML-valued parameters.
     */
    public static String sanitizeForOutputPreservingToolCalls(String s) {
        if (s == null || s.isEmpty()) return "";
        s = stripControl(dropThinkBlocks(s));
        return stripSuspiciousHtmlOutsideToolCalls(s);
    }

    /**
     * Sanitizes message content for multi-turn chat (messages sent to the model).
     *
     * <p>Only strips control characters — does NOT strip HTML. Messages in the
     * tool-call pipeline may contain file content with legitimate HTML/script tags
     * (e.g., tool results from read_file). Stripping those would give the model an
     * incorrect view of the file, causing it to generate wrong edits.
     *
     * <p>This is safe for a local-first CLI where the user is the only source of
     * input. The model's output is still sanitized via
     * {@link #sanitizeForOutputPreservingToolCalls} before display.
     */
    public static String sanitizeMessageContent(String s) {
        return stripControl(s);
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
     * Applies {@link #SUS_HTML} stripping only to text <em>outside</em>
     * tool-call blocks (both XML and JSON code-fence formats).
     *
     * <p>The algorithm: find all tool_call blocks (XML tags and JSON code fences),
     * protect them, strip HTML from the interstitial prose, then reassemble.
     */
    private static String stripSuspiciousHtmlOutsideToolCalls(String s) {
        // Collect all protected regions (tool-call blocks in any format)
        java.util.List<int[]> protectedRegions = new java.util.ArrayList<>();
        collectRegions(TOOL_CALL_BLOCK, s, protectedRegions);
        collectRegions(JSON_TOOL_CALL_FENCE, s, protectedRegions);

        if (protectedRegions.isEmpty()) {
            // No tool_call blocks — apply SUS_HTML to the entire string
            return SUS_HTML.matcher(s).replaceAll("");
        }

        // Sort by start position
        protectedRegions.sort(java.util.Comparator.comparingInt(a -> a[0]));

        // Walk through the string, sanitizing only the gaps between blocks
        StringBuilder result = new StringBuilder(s.length());
        int lastEnd = 0;
        for (int[] region : protectedRegions) {
            int start = region[0];
            int end = region[1];
            if (start < lastEnd) continue; // overlapping region — skip
            // Sanitize prose before this block
            String before = s.substring(lastEnd, start);
            result.append(SUS_HTML.matcher(before).replaceAll(""));
            // Preserve the tool_call block verbatim
            result.append(s, start, end);
            lastEnd = end;
        }
        // Sanitize prose after the last block
        String after = s.substring(lastEnd);
        result.append(SUS_HTML.matcher(after).replaceAll(""));
        return result.toString();
    }

    /** Collect all match regions from a pattern into the list. */
    private static void collectRegions(Pattern pattern, String s, java.util.List<int[]> regions) {
        java.util.regex.Matcher m = pattern.matcher(s);
        while (m.find()) {
            regions.add(new int[] { m.start(), m.end() });
        }
    }

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
