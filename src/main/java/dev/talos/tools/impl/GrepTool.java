package dev.talos.tools.impl;

import dev.talos.tools.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool that searches workspace files for text or regex patterns.
 *
 * <p>Walks the workspace directory tree, respects sandbox policy,
 * and returns matching lines with file paths and line numbers.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code pattern} — text or regex pattern to search for (required)</li>
 *   <li>{@code include} — glob pattern for file names, e.g. "*.java" (optional)</li>
 *   <li>{@code max_results} — maximum total matching lines to return (optional, default: 50)</li>
 *   <li>{@code regex} — "true" to treat pattern as regex (optional, default: false)</li>
 * </ul>
 */
public final class GrepTool implements TalosTool {

    private static final String NAME = "talos.grep";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final long MAX_FILE_SIZE = 1024 * 1024L; // 1 MiB — skip huge files

    // Directories to always skip during walk
    private static final List<String> SKIP_DIRS = List.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__",
            ".gradle", "build", ".idea", ".talos", ".loqj"
    );

    @Override public String name() { return NAME; }
    @Override public String description() { return "Search workspace files for a text or regex pattern."; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"Text or regex pattern to search for"},
                  "include":{"type":"string","description":"Glob for filenames, e.g. *.java (optional)"},
                  "max_results":{"type":"integer","description":"Max matching lines (default 50)"},
                  "regex":{"type":"string","description":"'true' to use regex (default plain text)"}
                },"required":["pattern"]}""");
    }

    /** Legacy no-context execute — returns error. */
    @Override
    public ToolResult execute(ToolCall call) {
        return ToolResult.fail(ToolError.internal("GrepTool requires a ToolContext"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) return execute(call);

        String patternStr = resolveParam(call, "pattern", "query", "search", "text", "search_pattern", "search_text");
        if (patternStr == null || patternStr.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: pattern"));
        }

        boolean useRegex = "true".equalsIgnoreCase(call.param("regex"));
        int maxResults = parseIntParam(call, "max_results", DEFAULT_MAX_RESULTS);
        String includeGlob = call.param("include"); // nullable

        // Compile the search pattern
        Pattern pattern;
        try {
            if (useRegex) {
                pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            } else {
                pattern = Pattern.compile(Pattern.quote(patternStr), Pattern.CASE_INSENSITIVE);
            }
        } catch (PatternSyntaxException e) {
            return ToolResult.fail(ToolError.invalidParams("Invalid regex: " + e.getMessage()));
        }

        // Optional filename glob matcher
        PathMatcher globMatcher = null;
        if (includeGlob != null && !includeGlob.isBlank()) {
            try {
                globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
            } catch (Exception e) {
                return ToolResult.fail(ToolError.invalidParams("Invalid glob pattern: " + includeGlob));
            }
        }

        Path root = ctx.workspace();
        List<String> matches = new ArrayList<>();
        final PathMatcher matcher = globMatcher;

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (SKIP_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!ctx.sandbox().allowedPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) return FileVisitResult.TERMINATE;
                    if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

                    // Sandbox check
                    if (!ctx.sandbox().allowedPath(file)) return FileVisitResult.CONTINUE;

                    // Glob filter
                    if (matcher != null) {
                        Path fileName = file.getFileName();
                        if (fileName == null || !matcher.matches(fileName)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    // Skip binary-looking files (quick heuristic: check first bytes)
                    if (looksLikeBinary(file)) return FileVisitResult.CONTINUE;

                    searchFile(file, root, pattern, matches, maxResults);
                    return matches.size() >= maxResults
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // skip unreadable files
                }
            });
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Search failed: " + e.getMessage()));
        }

        if (matches.isEmpty()) {
            return ToolResult.ok("No matches found for: " + patternStr);
        }

        var sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" match(es):\n\n");
        for (String match : matches) {
            sb.append(match).append('\n');
        }
        if (matches.size() >= maxResults) {
            sb.append("\n(results capped at ").append(maxResults).append(")\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private static void searchFile(Path file, Path root, Pattern pattern,
                                   List<String> matches, int maxResults) {
        try {
            String relPath = root.relativize(file).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                String line = lines.get(i);
                if (pattern.matcher(line).find()) {
                    matches.add(relPath + ":" + (i + 1) + " | " + truncate(line.stripTrailing(), 200));
                }
            }
        } catch (IOException ignored) {
            // skip files that can't be read as text
        }
    }

    private static boolean looksLikeBinary(Path file) {
        try (var is = Files.newInputStream(file)) {
            byte[] head = is.readNBytes(512);
            int nullCount = 0;
            for (byte b : head) {
                if (b == 0) nullCount++;
            }
            return nullCount > 4; // more than 4 null bytes in first 512 → likely binary
        } catch (IOException e) {
            return true; // can't read → skip
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int parseIntParam(ToolCall call, String key, int defaultValue) {
        String v = call.param(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Resolve a parameter by trying the canonical key first, then known aliases. */
    private static String resolveParam(ToolCall call, String canonical, String... aliases) {
        String value = call.param(canonical);
        if (value != null) return value;
        for (String alias : aliases) {
            value = call.param(alias);
            if (value != null) return value;
        }
        return null;
    }
}

