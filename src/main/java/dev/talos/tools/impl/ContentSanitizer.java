package dev.talos.tools.impl;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips trailing markdown commentary that LLMs accidentally include in
 * tool {@code content} parameters.
 *
 * <p>Common pattern: the model outputs file content, closes the code fence
 * ({@code ```}), then adds explanation (headings, bullets, bold text).
 * Because the fence and explanation are inside the JSON string value of the
 * {@code content} parameter, they end up written to the actual file.
 *
 * <p>This sanitizer detects a stray closing fence followed by markdown-like
 * commentary and strips it. Conservative: it only acts when the post-fence
 * text is clearly markdown, not more code. {@code .md} files are exempt
 * because triple backticks are valid markdown content.
 */
final class ContentSanitizer {

    private ContentSanitizer() {}

    /** Markdown file extensions that are exempt from sanitization. */
    private static final Pattern MD_EXTENSION = Pattern.compile(
            "(?i)\\.(?:md|markdown|mdx)$"
    );

    /**
     * A line that is a stray code fence: optional whitespace, three or more
     * backticks, optional language tag, then end of line.
     */
    private static final Pattern FENCE_LINE = Pattern.compile(
            "^\\s*`{3,}\\w*\\s*$"
    );

    /**
     * Patterns that indicate markdown commentary (not code):
     * headings, bullets, numbered lists, bold/italic openers, horizontal rules,
     * or lines starting with common explanation markers.
     */
    private static final Pattern MARKDOWN_COMMENTARY = Pattern.compile(
            "^\\s*(?:" +
                "#{1,6}\\s|" +                          // headings: # Title
                "[-*+]\\s|" +                            // unordered list: - item, * item
                "\\d+\\.\\s|" +                          // ordered list: 1. item
                "\\*{2,}[^*]|" +                         // bold: **text
                "_{2,}[^_]|" +                           // bold underscores: __text
                "---+\\s*$|" +                           // horizontal rule: ---
                "\\*{3,}\\s*$|" +                        // horizontal rule: ***
                ">{1,2}\\s|" +                           // blockquote: > text
                "\\[.+\\]\\(.+\\)|" +                    // link: [text](url)
                "!\\[|" +                                // image: ![
                "(?:Note|Warning|Important|Tip|Explanation|" +
                "Key Changes|Summary|Changes|Action|Improvements|" +
                "Remember|Please|To use|This version)\\b" +  // common explanation starters
            ")"
    );

    /**
     * Sanitize file content by stripping trailing markdown commentary.
     *
     * @param content  the raw content from the LLM's tool call (may be null)
     * @param filePath the target file path (used to exempt .md files; may be null)
     * @return sanitized content, or the original content unchanged
     */
    static String sanitize(String content, String filePath) {
        if (content == null || content.isEmpty()) return content;

        // Exempt markdown files — triple backticks are valid content
        if (filePath != null && MD_EXTENSION.matcher(filePath).find()) {
            return content;
        }

        // Find the last occurrence of a stray code fence line
        int fenceStart = findTrailingFence(content);
        if (fenceStart < 0) return content;

        // Extract text after the fence line
        String afterFence = content.substring(fenceStart);
        // Skip past the fence line itself
        int fenceEnd = afterFence.indexOf('\n');
        if (fenceEnd < 0) {
            // Fence is the very last line — could be legitimate EOF fence
            // Only strip if there's nothing after it
            return content;
        }

        String postFenceText = afterFence.substring(fenceEnd + 1);

        // Require at least one non-blank line of markdown-like commentary
        if (!looksLikeMarkdown(postFenceText)) {
            return content;
        }

        // Strip from the fence line onward
        String cleaned = content.substring(0, fenceStart).stripTrailing();
        return cleaned.isEmpty() ? content : cleaned + "\n";
    }

    /**
     * Find the start index of the last stray code fence line in the content.
     * Returns -1 if none found.
     *
     * <p>Scans backward from the end. Only considers fences in the last portion
     * of the content (last 20% or last 2000 chars, whichever is larger) to
     * avoid matching code fences that are legitimate parts of the file content.
     */
    private static int findTrailingFence(String content) {
        // Only scan the trailing portion of the content
        int scanStart = Math.max(0, content.length() - Math.max(2000, content.length() / 5));

        // Find the last occurrence of ``` in the scan region
        int lastFence = -1;
        int searchFrom = content.length();

        while (searchFrom > scanStart) {
            int idx = content.lastIndexOf("```", searchFrom - 1);
            if (idx < scanStart) break;

            // Check if this ``` is at the start of a line (allowing leading whitespace)
            int lineStart = content.lastIndexOf('\n', idx - 1) + 1;
            String line = content.substring(lineStart, Math.min(content.length(),
                    content.indexOf('\n', idx) >= 0 ? content.indexOf('\n', idx) : content.length()));

            if (FENCE_LINE.matcher(line).matches()) {
                lastFence = lineStart;
                break;
            }

            searchFrom = idx;
        }

        return lastFence;
    }

    /**
     * Matches lines that look like plain English sentences (not code).
     * Used after markdown has been detected — continuation sentences
     * in LLM explanations (e.g., "This final version is complete.").
     */
    private static final Pattern PLAIN_PROSE = Pattern.compile(
            "^[A-Z][a-z].*[.!?:]\\s*$|" +          // sentence: "This version is complete."
            "^\\*\\*[^*]+\\*\\*.*$|" +               // bold wrapper: **text**...
            "^\\([^)]+\\)\\s*$"                      // parenthetical: (some note)
    );

    /**
     * Check if the text after a stray fence looks like markdown commentary
     * rather than code content.
     *
     * <p>Strategy: the first non-blank line must match a markdown pattern.
     * Subsequent lines may be markdown, plain English prose, or blank.
     * If we find a line that looks like code (doesn't match markdown,
     * prose, or blank), we conservatively return false — but only if
     * no markdown was yet detected. Once markdown is confirmed, plain
     * prose continuation is allowed.
     */
    private static boolean looksLikeMarkdown(String text) {
        if (text == null || text.isBlank()) return false;

        String[] lines = text.split("\n", -1);
        boolean foundMarkdown = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue; // skip blank lines

            if (MARKDOWN_COMMENTARY.matcher(trimmed).find()) {
                foundMarkdown = true;
            } else if (foundMarkdown && PLAIN_PROSE.matcher(trimmed).find()) {
                // Plain English after confirmed markdown — continuation text, OK
            } else if (!foundMarkdown) {
                // First non-blank line isn't markdown — not a commentary block
                return false;
            } else {
                // After confirmed markdown, a non-prose line could be code
                // Be conservative: if it looks nothing like prose, stop
                return false;
            }
        }

        return foundMarkdown;
    }
}


