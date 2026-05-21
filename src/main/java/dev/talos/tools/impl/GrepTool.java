package dev.talos.tools.impl;

import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import dev.talos.safety.ProtectedContentMessages;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedWorkspacePaths;
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
 *   <li>{@code include} — single glob pattern for file names, e.g. "*.java" or "*.{js,css}" (optional)</li>
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
                  "include":{"type":"string","description":"Single glob for filenames, e.g. *.java or *.{js,css} (optional). Do not pass comma-separated globs."},
                  "max_results":{"type":"integer","description":"Max matching lines (default 50)"},
                  "regex":{"type":"string","description":"'true' to use regex (default plain text)"}
                },"required":["pattern"]}""",
                ToolRiskLevel.READ_ONLY,
                ToolOperationMetadata.inspect(NAME, java.util.Map.of(), "WORKSPACE_GREP"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        if (ctx == null) {
            return ToolResult.fail(ToolError.internal("GrepTool requires a ToolContext"));
        }

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
            if (hasTopLevelComma(includeGlob)) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Invalid include glob: comma-separated include values are not supported. "
                                + "Pass one glob such as *.js, or one brace glob such as *.{html,css,js}."));
            }
            try {
                globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
            } catch (Exception e) {
                return ToolResult.fail(ToolError.invalidParams("Invalid glob pattern: " + includeGlob));
            }
        }

        Path root = ctx.workspace();
        boolean privateMode = ProtectedReadScopePolicy.privateMode(ctx.config());
        List<String> matches = new ArrayList<>();
        List<String> skippedUnsupportedDocuments = new ArrayList<>();
        int[] skippedProtected = {0};
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

                    if (ProtectedWorkspacePaths.isProtectedPath(root, file)) {
                        skippedProtected[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    // Glob filter
                    if (matcher != null) {
                        Path fileName = file.getFileName();
                        if (fileName == null || !matcher.matches(fileName)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }

                    FileCapabilityPolicy.FormatInfo capability =
                            FileCapabilityPolicy.describe(file, ctx.config()).orElse(null);
                    if (capability != null && capability.enabled()) {
                        searchExtractedFile(file, root, ctx, pattern, matches, maxResults, skippedUnsupportedDocuments);
                        return matches.size() >= maxResults
                                ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }

                    if (UnsupportedDocumentFormats.isUnsupported(file)) {
                        skippedUnsupportedDocuments.add(root.relativize(file).toString().replace('\\', '/'));
                        return FileVisitResult.CONTINUE;
                    }

                    // Skip binary-looking files (quick heuristic: check first bytes)
                    if (looksLikeBinary(file)) {
                        skippedUnsupportedDocuments.add(root.relativize(file).toString().replace('\\', '/'));
                        return FileVisitResult.CONTINUE;
                    }

                    searchFile(file, root, pattern, matches, maxResults, privateMode);
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
            String safePattern = ProtectedContentSanitizer.sanitizeText(patternStr);
            return ToolResult.ok("No matches found in searchable non-protected text files for: " + safePattern
                    + ProtectedContentMessages.protectedContentNote(skippedProtected[0])
                    + unsupportedDocumentNote(skippedUnsupportedDocuments));
        }

        var sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" match(es):\n\n");
        for (String match : matches) {
            sb.append(match).append('\n');
        }
        if (matches.size() >= maxResults) {
            sb.append("\n(results capped at ").append(maxResults).append(")\n");
        }
        sb.append(ProtectedContentMessages.protectedContentNote(skippedProtected[0]));
        sb.append(unsupportedDocumentNote(skippedUnsupportedDocuments));
        return ToolResult.ok(sb.toString());
    }

    private static boolean hasTopLevelComma(String glob) {
        if (glob == null || glob.isBlank()) return false;
        int braceDepth = 0;
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '{') {
                braceDepth++;
            } else if (ch == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (ch == ',' && braceDepth == 0) {
                return true;
            }
        }
        return false;
    }

    private static String unsupportedDocumentNote(List<String> skippedUnsupportedDocuments) {
        if (skippedUnsupportedDocuments == null || skippedUnsupportedDocuments.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        out.append("\n\nSearch was limited to searchable text files. Skipped unsupported binary document(s): ");
        int limit = Math.min(5, skippedUnsupportedDocuments.size());
        out.append(String.join(", ", skippedUnsupportedDocuments.subList(0, limit)));
        if (skippedUnsupportedDocuments.size() > limit) {
            out.append(", ... ").append(skippedUnsupportedDocuments.size() - limit).append(" more");
        }
        out.append(". Talos grep cannot extract PDF/Office binary contents or other unsupported/binary files with the current local text-tool surface.");
        return out.toString();
    }

    private static void searchFile(Path file, Path root, Pattern pattern,
                                   List<String> matches, int maxResults, boolean privateMode) {
        try {
            String relPath = root.relativize(file).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                String line = lines.get(i);
                if (pattern.matcher(line).find()) {
                    String safeLine = safeSearchLine(line.stripTrailing(), privateMode);
                    matches.add(relPath + ":" + (i + 1) + " | " + truncate(safeLine, 200));
                }
            }
        } catch (IOException ignored) {
            // skip files that can't be read as text
        }
    }

    private static void searchExtractedFile(
            Path file,
            Path root,
            ToolContext ctx,
            Pattern pattern,
            List<String> matches,
            int maxResults,
            List<String> skippedUnsupportedDocuments) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        boolean privateMode = ProtectedReadScopePolicy.privateMode(ctx.config());
        DocumentExtractionResult extraction = new DocumentExtractionService(ctx.config())
                .extract(DocumentExtractionRequest.search(file, root));
        if (extraction.status() != DocumentExtractionStatus.SUCCESS
                && extraction.status() != DocumentExtractionStatus.PARTIAL) {
            skippedUnsupportedDocuments.add(relPath + " (" + extraction.status() + ")");
            return;
        }
        String[] lines = extraction.safeText().split("\\R", -1);
        for (int i = 0; i < lines.length && matches.size() < maxResults; i++) {
            String line = lines[i];
            if (pattern.matcher(line).find()) {
                String safeLine = safeExtractedSearchLine(line.stripTrailing(), privateMode, extraction);
                matches.add(relPath + ":" + (i + 1) + " | " + truncate(safeLine, 200));
            }
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

    private static String safeSearchLine(String line, boolean privateMode) {
        String safeLine = ProtectedContentSanitizer.sanitizeSearchLine(line);
        if (privateMode && !safeLine.equals(line)) {
            return "[line content withheld by private-mode search policy]";
        }
        return safeLine;
    }

    private static String safeExtractedSearchLine(
            String line,
            boolean privateMode,
            DocumentExtractionResult extraction) {
        if (privateMode && extraction != null && !extraction.modelHandoffAllowed()) {
            return "[extracted document match withheld from model context by private-document policy]";
        }
        return safeSearchLine(line, privateMode);
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

