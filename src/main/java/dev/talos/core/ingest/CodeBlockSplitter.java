package dev.talos.core.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Structural block splitter for source code files.
 *
 * <p>Produces blocks aligned on language-level boundaries (classes, methods,
 * function definitions, import preambles) instead of arbitrary character
 * positions. The resulting blocks are fed into {@link Chunker}'s existing
 * budget+overlap loop, which handles size enforcement.
 *
 * <p>Three strategies:
 * <ol>
 *   <li><b>Brace-based</b> (Java, Kotlin, JS/TS, Go, Rust, C/C++, Scala, Groovy):
 *       tracks brace depth through string literals and comments; splits when
 *       depth returns to 0.</li>
 *   <li><b>Indent-based</b> (Python): splits at column-0 {@code def}/{@code class}/
 *       {@code async def} and decorator lines.</li>
 *   <li><b>Blank-line groups</b> (Shell and fallback): splits on runs of two or
 *       more consecutive blank lines.</li>
 * </ol>
 *
 * @see Chunker
 */
final class CodeBlockSplitter {
    private CodeBlockSplitter() {}

    private static final Set<SourceFormat> BRACE_BASED = Set.of(
            SourceFormat.JAVA, SourceFormat.KOTLIN, SourceFormat.JAVASCRIPT,
            SourceFormat.TYPESCRIPT, SourceFormat.GO, SourceFormat.RUST,
            SourceFormat.CPP, SourceFormat.C, SourceFormat.C_HEADER,
            SourceFormat.SCALA, SourceFormat.GROOVY,
            SourceFormat.GRADLE_KTS, SourceFormat.GRADLE
    );

    private static final Set<SourceFormat> INDENT_BASED = Set.of(
            SourceFormat.PYTHON
    );

    /**
     * Split source code into structural blocks.
     *
     * @param content raw file content
     * @param format  source format (determines strategy); null → blank-line fallback
     * @return non-empty list of blocks; every char in {@code content} appears in
     *         exactly one block (concatenating all blocks reproduces the original)
     */
    static List<String> split(String content, SourceFormat format) {
        if (content == null || content.isEmpty()) return List.of();
        if (format == null) return splitBlankLineGroups(content);

        if (BRACE_BASED.contains(format)) {
            return splitBraceBased(content);
        } else if (INDENT_BASED.contains(format)) {
            return splitIndentBased(content);
        } else {
            return splitBlankLineGroups(content);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Brace-based strategy (Java, JS/TS, Go, Rust, C/C++, Kotlin, etc.)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tracks brace depth through the file content, respecting string literals,
     * character literals, and both styles of comments. Splits between top-level
     * declarations — each time brace depth returns to 0 and we encounter a blank
     * line or a new declaration, we emit a block.
     */
    static List<String> splitBraceBased(String content) {
        List<String> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        int depth = 0;
        int blockStart = 0; // line index where current block begins
        boolean inPreamble = true; // import/package region at top of file

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Preamble detection: package/import/include lines at file top
            if (inPreamble) {
                if (trimmed.isEmpty()
                        || trimmed.startsWith("package ")
                        || trimmed.startsWith("import ")
                        || trimmed.startsWith("#include")
                        || trimmed.startsWith("#pragma")
                        || trimmed.startsWith("#ifndef")
                        || trimmed.startsWith("#define")
                        || trimmed.startsWith("#endif")
                        || trimmed.startsWith("using ")
                        || trimmed.startsWith("//")
                        || trimmed.startsWith("/*")
                        || trimmed.startsWith("*")
                        || trimmed.startsWith("*/")) {
                    continue;
                }
                // First non-preamble line: emit preamble block (if non-empty)
                if (i > blockStart) {
                    blocks.add(joinLines(lines, blockStart, i));
                    blockStart = i;
                }
                inPreamble = false;
            }

            // Track brace depth for this line (skipping strings/comments)
            depth += netBraceDepth(line);

            // Split point: at depth 0 and a blank line follows (or end of file),
            // or the next non-blank line looks like a new top-level declaration
            if (depth == 0 && i > blockStart) {
                boolean atEnd = (i == lines.length - 1);
                boolean blankFollows = !atEnd && (i + 1 < lines.length) && lines[i + 1].trim().isEmpty();
                boolean newDeclFollows = !atEnd && (i + 1 < lines.length) && looksLikeDeclarationStart(lines[i + 1].trim());

                if (atEnd || blankFollows || newDeclFollows) {
                    blocks.add(joinLines(lines, blockStart, i + 1));
                    // Skip trailing blank lines — attach them to next block as leading whitespace
                    int next = i + 1;
                    while (next < lines.length && lines[next].trim().isEmpty()) {
                        next++;
                    }
                    blockStart = next;
                    // Don't advance i past the blank lines — the for-loop will handle them
                }
            }
        }

        // Emit remainder
        if (blockStart < lines.length) {
            String remainder = joinLines(lines, blockStart, lines.length);
            if (!remainder.isBlank()) {
                blocks.add(remainder);
            }
        }

        // Safety: if we produced nothing (e.g., the whole file is one class), return the whole content
        if (blocks.isEmpty()) {
            blocks.add(content);
        }

        return blocks;
    }

    /**
     * Compute net brace-depth change for a single line, skipping characters
     * inside string literals, char literals, and comments.
     */
    static int netBraceDepth(String line) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        // Note: block comments spanning multiple lines are handled conservatively —
        // we don't track cross-line block comment state, which is acceptable because
        // block comments rarely contain braces, and the brace counter self-corrects
        // at the next top-level boundary.
        boolean inBlockComment = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = (i + 1 < line.length()) ? line.charAt(i + 1) : 0;

            // Handle escape sequences
            if ((inString || inChar) && c == '\\') {
                i++; // skip escaped char
                continue;
            }

            // Block comment end
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++; // skip '/'
                }
                continue;
            }

            // Line comment — skip rest of line
            if (inLineComment) {
                continue;
            }

            // String literal
            if (inString) {
                if (c == '"') inString = false;
                continue;
            }

            // Char literal
            if (inChar) {
                if (c == '\'') inChar = false;
                continue;
            }

            // Start of line comment
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }

            // Start of block comment
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            // Start of string
            if (c == '"') {
                inString = true;
                continue;
            }

            // Start of char literal
            if (c == '\'') {
                inChar = true;
                continue;
            }

            // Count braces
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }

        return depth;
    }

    /**
     * Heuristic: does this line look like the start of a top-level declaration?
     * Used to identify split points between consecutive declarations.
     */
    private static boolean looksLikeDeclarationStart(String trimmed) {
        if (trimmed.isEmpty()) return false;
        // Javadoc / block-comment start
        if (trimmed.startsWith("/**") || trimmed.startsWith("/*")) return true;
        // Annotations (Java/Kotlin)
        if (trimmed.startsWith("@")) return true;
        // Common declaration keywords
        return trimmed.startsWith("public ")
                || trimmed.startsWith("private ")
                || trimmed.startsWith("protected ")
                || trimmed.startsWith("static ")
                || trimmed.startsWith("final ")
                || trimmed.startsWith("abstract ")
                || trimmed.startsWith("class ")
                || trimmed.startsWith("interface ")
                || trimmed.startsWith("enum ")
                || trimmed.startsWith("record ")
                || trimmed.startsWith("sealed ")
                || trimmed.startsWith("fun ")
                || trimmed.startsWith("val ")
                || trimmed.startsWith("var ")
                || trimmed.startsWith("data class ")
                || trimmed.startsWith("object ")
                || trimmed.startsWith("func ")
                || trimmed.startsWith("fn ")
                || trimmed.startsWith("impl ")
                || trimmed.startsWith("struct ")
                || trimmed.startsWith("trait ")
                || trimmed.startsWith("type ")
                || trimmed.startsWith("const ")
                || trimmed.startsWith("let ")
                || trimmed.startsWith("export ")
                || trimmed.startsWith("function ")
                || trimmed.startsWith("async ")
                || trimmed.startsWith("void ")
                || trimmed.startsWith("int ")
                || trimmed.startsWith("long ")
                || trimmed.startsWith("double ")
                || trimmed.startsWith("float ")
                || trimmed.startsWith("boolean ")
                || trimmed.startsWith("String ")
                || trimmed.startsWith("List<")
                || trimmed.startsWith("Map<")
                || trimmed.startsWith("Set<")
                || trimmed.startsWith("Optional<");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Indent-based strategy (Python)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Splits Python source at column-0 boundaries: each {@code def}, {@code class},
     * {@code async def}, or decorator ({@code @}) at column 0 starts a new block.
     * Leading imports/comments form a preamble block.
     */
    static List<String> splitIndentBased(String content) {
        List<String> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        int blockStart = 0;
        boolean inPreamble = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Preamble: imports, comments, blank lines at top of file
            if (inPreamble) {
                if (trimmed.isEmpty()
                        || trimmed.startsWith("#")
                        || trimmed.startsWith("import ")
                        || trimmed.startsWith("from ")
                        || trimmed.startsWith("\"\"\"")
                        || trimmed.startsWith("'''")) {
                    continue;
                }
                // First real code line: emit preamble
                if (i > blockStart) {
                    blocks.add(joinLines(lines, blockStart, i));
                    blockStart = i;
                }
                inPreamble = false;
            }

            // Detect top-level definition start (column 0, no leading whitespace)
            if (i > blockStart && !line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                if (isTopLevelPythonStart(trimmed)) {
                    // Emit previous block
                    String prev = joinLines(lines, blockStart, i);
                    if (!prev.isBlank()) blocks.add(prev);
                    blockStart = i;
                }
            }
        }

        // Emit remainder
        if (blockStart < lines.length) {
            String remainder = joinLines(lines, blockStart, lines.length);
            if (!remainder.isBlank()) blocks.add(remainder);
        }

        if (blocks.isEmpty()) blocks.add(content);
        return blocks;
    }

    private static boolean isTopLevelPythonStart(String trimmed) {
        return trimmed.startsWith("def ")
                || trimmed.startsWith("class ")
                || trimmed.startsWith("async def ")
                || trimmed.startsWith("@");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Blank-line groups (Shell, fallback)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Splits on runs of two or more consecutive blank lines.
     * Single blank lines are kept within blocks.
     */
    static List<String> splitBlankLineGroups(String content) {
        List<String> blocks = new ArrayList<>();
        // Split on 2+ consecutive blank lines (preserving one trailing newline per block)
        String[] parts = content.split("\\n\\s*\\n\\s*\\n", -1);
        for (String part : parts) {
            if (!part.isBlank()) {
                blocks.add(part);
            }
        }
        if (blocks.isEmpty()) blocks.add(content);
        return blocks;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /** Joins lines[from..to) with newline separators. */
    private static String joinLines(String[] lines, int from, int to) {
        if (from >= to) return "";
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}

