package dev.loqj.core.util;

import java.util.regex.Pattern;

/**
 * Self-contained sanitizer used by RenderEngine. Safe defaults.
 * Keeping it here in PR-1 so nothing else is required to compile.
 */
public final class Sanitize {
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]+");
    private static final Pattern TAG_SCRIPT = Pattern.compile("(?is)<script.*?>.*?</script>");
    private static final Pattern TAG_STYLE  = Pattern.compile("(?is)<style.*?>.*?</style>");
    private static final Pattern HTML_COMMS = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern HTML_EVT   = Pattern.compile("(?i)\\son\\w+\\s*=\\s*(['\"]).*?\\1");
    private static final Pattern THINK_TAGS = Pattern.compile("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>");

    private Sanitize() {}

    public static String sanitizeForPrompt(String s) {
        if (s == null || s.isEmpty()) return "";
        s = stripAnsi(s);
        s = stripControls(s);
        s = stripSuspiciousHtml(s);
        s = stripThinkTags(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    public static String stripAnsi(String s) { return s == null ? "" : ANSI.matcher(s).replaceAll(""); }
    public static String stripControls(String s) { return s == null ? "" : CONTROLS.matcher(s).replaceAll(""); }

    public static String stripSuspiciousHtml(String s) {
        if (s == null) return "";
        s = HTML_COMMS.matcher(s).replaceAll("");
        s = TAG_STYLE.matcher(s).replaceAll("");
        s = TAG_SCRIPT.matcher(s).replaceAll("");
        s = HTML_EVT.matcher(s).replaceAll("");
        return s;
    }

    public static String stripThinkTags(String s) { return s == null ? "" : THINK_TAGS.matcher(s).replaceAll(""); }

    public static String clip(String s, int maxChars) {
        if (s == null || maxChars <= 0) return "";
        int cp = s.codePointCount(0, s.length());
        if (cp <= maxChars) return s;
        int end = s.offsetByCodePoints(0, maxChars);
        return s.substring(0, end);
    }
}
