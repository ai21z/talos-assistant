package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-hoc extraction of implicit tool calls from LLM code blocks.
 *
 * <p>When the LLM fails to use the {@code <tool_call>} format and instead
 * produces a fenced code block with a filename header, this extractor
 * detects the pattern and converts it to a {@code talos.write_file}
 * {@link ToolCall}. This is a <strong>safety net</strong>, not a primary path —
 * the canonical tool-call format via {@link ToolCallParser} is always preferred.
 *
 * <p>Recognized patterns (case-insensitive):
 * <pre>{@code
 *   ```json // settings.json        →  write_file(path="settings.json", content=...)
 *   ```python # src/main.py         →  write_file(path="src/main.py", content=...)
 *   ```java // src/App.java          →  write_file(path="src/App.java", content=...)
 *   ```// config.yaml               →  write_file(path="config.yaml", content=...)
 *   ``` filename: package.json       →  write_file(path="package.json", content=...)
 * }</pre>
 *
 * <p>Additionally recognizes heading/prose patterns where the filename appears
 * in backticks on a preceding line (up to 5 lines before the code block):
 * <pre>{@code
 *   ### Updated `index.html`        →  write_file(path="index.html", content=...)
 *   ### ✅ `styles.css` (Copy This)  →  write_file(path="styles.css", content=...)
 *   Replace your `app.js`:          →  write_file(path="app.js", content=...)
 * }</pre>
 *
 * <p>The extractor is deliberately conservative:
 * <ul>
 *   <li>Only matches code blocks with a recognizable filename (must have an extension)</li>
 *   <li>Ignores blocks that look like explanatory snippets (no filename hint)</li>
 *   <li>Returns an empty list if no extractable blocks are found</li>
 * </ul>
 *
 * <p>All methods are stateless and thread-safe.
 *
 * @see ToolCallParser
 * @see ToolCall
 */
public final class CodeBlockToolExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(CodeBlockToolExtractor.class);

    private CodeBlockToolExtractor() {} // utility class

    /**
     * Pattern for fenced code blocks where the opening fence contains a filename hint.
     *
     * <p>Matches:
     * <ul>
     *   <li>{@code ```lang // path/file.ext} — C-style comment after language tag</li>
     *   <li>{@code ```lang # path/file.ext}  — Shell/Python comment after language tag</li>
     *   <li>{@code ```// path/file.ext}      — No language tag, C-style comment</li>
     *   <li>{@code ```# path/file.ext}       — No language tag, shell comment</li>
     *   <li>{@code ```lang filename: path/file.ext} — "filename:" prefix</li>
     *   <li>{@code ```lang file: path/file.ext}     — "file:" prefix</li>
     * </ul>
     *
     * <p>Group 1 = filename (with path), Group 2 = block content.
     */
    private static final Pattern CODE_BLOCK_WITH_FILENAME = Pattern.compile(
            "```[a-zA-Z]*\\s*" +                    // opening fence + optional language
            "(?://|#|filename:|file:)\\s*" +         // comment marker or filename: prefix
            "([A-Za-z0-9_./ \\\\-]+\\.[a-zA-Z0-9]+)" + // filename with extension (group 1)
            "\\s*\\n" +                              // rest of the line
            "(.*?)" +                                // block content (group 2, lazy)
            "\\n?```",                               // closing fence
            Pattern.DOTALL
    );

    /**
     * Alternative: block has no inline filename, but the preceding text line
     * says something like "Here is `src/App.java`:" or "Create `config.yaml`:".
     *
     * <p>Group 1 = filename, Group 2 = language tag (unused), Group 3 = content.
     */
    private static final Pattern PRECEDING_FILENAME = Pattern.compile(
            "`([A-Za-z0-9_./\\\\-]+\\.[a-zA-Z0-9]+)`\\s*[:：]\\s*\\n" +  // filename in backticks + colon (group 1)
            "```([a-zA-Z]*)\\s*\\n" +                                     // opening fence (group 2)
            "(.*?)" +                                                      // content (group 3)
            "\\n?```",
            Pattern.DOTALL
    );

    /**
     * Third alternative: the filename appears in backticks on a preceding line
     * (heading, bold text, or prose paragraph) with up to 4 intervening lines
     * of text or blank lines before the opening fence.
     *
     * <p>Matches real-world LLM patterns like:
     * <ul>
     *   <li>{@code ### Updated `index.html`} + blank lines + fence</li>
     *   <li>{@code ### ✅ `styles.css` (Copy This Entire Block)} + text + fence</li>
     *   <li>{@code Replace your `app.js` content:} + blank lines + fence</li>
     * </ul>
     *
     * <p>Group 1 = filename, Group 2 = language tag (unused), Group 3 = content.
     */
    private static final Pattern HEADING_FILENAME = Pattern.compile(
            "`([A-Za-z0-9_./\\\\-]+\\.[a-zA-Z0-9]+)`" + // filename in backticks (group 1)
            "[^`\\n]*\\n" +                                // rest of the line (no more backticks)
            "(?:[^\\n]*\\n){0,4}" +                        // up to 4 intervening lines
            "```([a-zA-Z]*)\\s*\\n" +                      // opening fence (group 2)
            "(.*?)" +                                      // content (group 3, lazy)
            "\\n?```",                                     // closing fence
            Pattern.DOTALL
    );

    /** File extensions that are definitely not filenames (e.g., language tags the regex might grab). */
    private static final Set<String> IGNORE_EXTENSIONS = Set.of(
            "com", "org", "net", "io"  // domain-like TLDs
    );

    /**
     * Scan the LLM response for fenced code blocks with filename headers
     * and convert them to {@code talos.write_file} tool calls.
     *
     * @param llmResponse the full LLM response text
     * @return list of extracted tool calls (empty if none found)
     */
    public static List<ToolCall> extract(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }

        List<ToolCall> calls = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        // Pass 1: inline filename in the fence opening
        extractFromPattern(CODE_BLOCK_WITH_FILENAME, 1, 2, llmResponse, calls, seenPaths);

        // Pass 2: filename in preceding backtick-quoted text (immediately before fence)
        extractFromPattern(PRECEDING_FILENAME, 1, 3, llmResponse, calls, seenPaths);

        // Pass 3: filename in heading/prose up to 5 lines before fence
        extractFromPattern(HEADING_FILENAME, 1, 3, llmResponse, calls, seenPaths);

        if (!calls.isEmpty()) {
            LOG.debug("Extracted {} implicit write_file call(s) from code blocks", calls.size());
        }

        return Collections.unmodifiableList(calls);
    }

    /**
     * Check if the response contains code blocks with extractable filenames.
     * Cheaper than {@link #extract(String)} when you only need a boolean.
     */
    public static boolean containsExtractableBlocks(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return false;
        return CODE_BLOCK_WITH_FILENAME.matcher(llmResponse).find()
                || PRECEDING_FILENAME.matcher(llmResponse).find()
                || HEADING_FILENAME.matcher(llmResponse).find();
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private static void extractFromPattern(Pattern pattern, int pathGroup, int contentGroup,
                                           String text, List<ToolCall> calls,
                                           Set<String> seenPaths) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String rawPath = m.group(pathGroup).strip();
            String content = m.group(contentGroup);

            // Normalize path separators
            rawPath = rawPath.replace('\\', '/');

            // Skip if path looks bogus
            if (rawPath.isBlank() || rawPath.contains("..")) continue;
            String ext = extensionOf(rawPath);
            if (ext.isEmpty() || IGNORE_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) continue;

            // Deduplicate by path (same file mentioned twice in one response)
            if (!seenPaths.add(rawPath)) continue;

            // Content must be non-empty
            if (content == null || content.isBlank()) continue;

            calls.add(new ToolCall("talos.write_file", Map.of(
                    "path", rawPath,
                    "content", content
            )));
        }
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1);
    }
}

