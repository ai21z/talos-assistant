package dev.talos.runtime;

import java.util.regex.Pattern;

/**
 * Narrow, lexical guard against tool-call payloads that are obviously
 * template-placeholder debris rather than real content.
 *
 * <p><b>Driven directly by the real Talos CLI transcript</b>
 * ({@code test-output.txt}, Turn 6, qwen2.5-coder:14b, April 2026):
 * the model emitted a pedagogical "step-by-step" answer containing
 * literal Python-style variable names, then — in the SAME turn —
 * issued {@code write_file} tool calls whose {@code content} argument
 * was the variable name itself:
 *
 * <pre>
 * {"name":"talos.write_file","arguments":
 *  {"path":"index.html","content":"&lt;updated_index_html_content&gt;"}}
 * </pre>
 *
 * Talos wrote 28 bytes of literal placeholder text over the user's
 * real {@code index.html}, and the approval preview just mirrored it
 * back ("preview: &lt;updated_index_html_content&gt;") so the user's
 * "y" reflex finished the destruction.
 *
 * <p>A warning-in-approval-detail would not have saved that user —
 * they pressed y after seeing two small "28 bytes, 1 lines" writes
 * land. The only safe posture for this failure class is <b>reject
 * at tool-call time</b>: the call is definitionally garbage, the
 * model should retry with real content, and the approval gate must
 * never see a payload this obviously wrong.
 *
 * <p><b>Deliberately lexical, not semantic.</b> We only catch the
 * "content is exactly one angle-bracketed placeholder identifier"
 * shape observed in the transcript. Any realistic file content —
 * even a tiny stub like {@code <html></html>} or {@code // TODO}
 * — has more structure and passes through untouched.
 */
public final class TemplatePlaceholderGuard {

    private TemplatePlaceholderGuard() {}

    /**
     * Exactly one angle-bracketed snake/kebab-case identifier, optional
     * surrounding whitespace, nothing else. The identifier must start
     * with a letter and may contain letters / digits / underscore /
     * hyphen. Intentionally refuses to match anything that resembles
     * real HTML (no closing tags, no attributes, no child content).
     */
    private static final Pattern PLACEHOLDER_ONLY = Pattern.compile(
            "^\\s*<\\s*[A-Za-z][A-Za-z0-9_\\-]*\\s*>\\s*$");

    private static final Pattern TOOL_RESULT_PLACEHOLDER_PREFIX = Pattern.compile(
            "^\\s*<\\s*(?:(?:content|output|result|text|file\\s+content)\\s+from\\s+"
                    + "(?:talos\\.)?[A-Za-z][A-Za-z0-9_.\\-]*"
                    + "|content\\s+of\\s+[^>]{1,120})\\s*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANGLE_CONTENT_PLACEHOLDER_PREFIX = Pattern.compile(
            "^\\s*<\\s*[A-Za-z0-9_\\-]*"
                    + "(?:content|previous|current|existing|original|read_file|talos)"
                    + "[A-Za-z0-9_\\-]*\\s*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BRACED_CONTENT_PLACEHOLDER_PREFIX = Pattern.compile(
            "^\\s*\\{\\s*[A-Za-z0-9_\\-]*"
                    + "(?:content|previous|current|existing|original|read_file|talos)"
                    + "[A-Za-z0-9_\\-]*\\s*\\}",
            Pattern.CASE_INSENSITIVE);

    /**
     * True iff {@code content} is a bare template-placeholder token with
     * no real structure (transcript-observed shape).
     *
     * <p>Returns false (permissive) for:
     * <ul>
     *   <li>null / empty / blank content</li>
     *   <li>content containing any newline (real files have structure)</li>
     *   <li>content containing a closing tag {@code </} (real HTML)</li>
     *   <li>content with an {@code =} after the tag name (real HTML attrs)</li>
     *   <li>content longer than 120 chars (real content, whatever shape)</li>
     *   <li>anything that doesn't match the strict identifier-only pattern</li>
     * </ul>
     */
    public static boolean looksLikeTemplatePlaceholder(String content) {
        if (content == null) return false;
        String trimmed = content.strip();
        if (trimmed.isEmpty()) return false;
        if (TOOL_RESULT_PLACEHOLDER_PREFIX.matcher(trimmed).find()) return true;
        if (ANGLE_CONTENT_PLACEHOLDER_PREFIX.matcher(trimmed).find()) return true;
        if (BRACED_CONTENT_PLACEHOLDER_PREFIX.matcher(trimmed).find()) return true;
        if (trimmed.length() > 120) return false;
        if (trimmed.indexOf('\n') >= 0) return false;
        if (trimmed.contains("</")) return false;
        // Real HTML opening tags have attributes or child content; a bare
        // "<identifier>" with nothing else is the template-debris shape.
        return PLACEHOLDER_ONLY.matcher(trimmed).matches();
    }

    /**
     * Human-readable explanation fed back to the model when a call is
     * rejected. Phrased so the model understands the rejection is about
     * its own output, not about user permissions — prevents the same
     * "permissions" hallucination loop the denial-wording fix in
     * {@code TurnProcessor} already reshapes.
     */
    public static String rejectionMessage(String toolName, String paramName, String content) {
        String snippet = content == null ? "" : content.strip();
        if (snippet.length() > 60) snippet = snippet.substring(0, 57) + "...";
        return "rejected " + toolName + ": the '" + paramName
                + "' argument looks like a literal template placeholder (\""
                + snippet + "\"), not real content. "
                + "Emit the full actual file content directly in the tool call; "
                + "do NOT use placeholder variables like <updated_foo> that you "
                + "intend the user or another step to fill in — tool calls execute "
                + "verbatim, there is no templating layer.";
    }
}
