package dev.talos.tools.impl;

import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.core.privacy.PrivacyConfigFacts;
import dev.talos.safety.ProtectedContentMessages;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedWorkspacePaths;
import dev.talos.tools.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 *   <li>{@code pattern} - text or regex pattern to search for (required)</li>
 *   <li>{@code include} - single glob pattern for file names, e.g. "*.java" or "*.{js,css}" (optional)</li>
 *   <li>{@code max_results} - maximum total matching lines to return (optional, default: 50)</li>
 *   <li>{@code regex} - "true" to treat pattern as regex (optional, default: false)</li>
 * </ul>
 */
public final class GrepTool implements TalosTool {

    private static final String NAME = "talos.grep";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final long MAX_FILE_SIZE = 1024 * 1024L; // 1 MiB - skip huge files

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
                  "include":{"type":"string","description":"Single filename-only glob, e.g. *.java or *.{js,css} (optional). Do not pass path separators or comma-separated globs."},
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
            if (hasPathSeparator(includeGlob)) {
                return ToolResult.fail(ToolError.invalidParams(
                        "Invalid include glob: include accepts a filename glob only and cannot contain path separators. "
                                + "Use *.json instead of /*.json or src/*.json."));
            }
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
        boolean privateMode = PrivacyConfigFacts.privateMode(ctx.config());
        List<String> matches = new ArrayList<>();
        LinkedHashSet<String> matchedPaths = new LinkedHashSet<>();
        List<String> skippedUnsupportedDocuments = new ArrayList<>();
        int[] skippedProtected = {0};
        final PathMatcher matcher = globMatcher;
        List<SearchCandidate> candidates = new ArrayList<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
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
                    try {
                        BasicFileAttributes noFollowAttributes = Files.readAttributes(
                                file,
                                BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (!isOrdinaryRegularFile(noFollowAttributes)
                                || noFollowAttributes.size() > MAX_FILE_SIZE
                                || !ctx.sandbox().allowedPath(file)) {
                            return FileVisitResult.CONTINUE;
                        }
                        SearchCandidate candidate = normalizeCandidate(root, file);
                        if (candidate == null) {
                            return FileVisitResult.CONTINUE;
                        }
                        candidates.add(candidate);
                    } catch (IOException | SecurityException ignored) {
                        // Entries that cannot be authenticated without following links are skipped.
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // skip unreadable files
                }
            });
        } catch (IOException e) {
            return ToolResult.fail(ToolError.internal("Search failed: " + e.getMessage()));
        }

        candidates.sort(GrepTool::compareCandidatesByRelativeSegments);
        for (SearchCandidate candidate : candidates) {
            if (matches.size() >= maxResults) break;

            CandidateAccess access = revalidateCandidate(candidate, root, ctx);
            if (access == CandidateAccess.SKIP) continue;
            if (access == CandidateAccess.PROTECTED) {
                skippedProtected[0]++;
                continue;
            }
            if (matcher != null) {
                Path fileName = candidate.path().getFileName();
                if (fileName == null || !matcher.matches(fileName)) {
                    continue;
                }
            }

            Path file = candidate.path();
            FileCapabilityPolicy.FormatInfo capability =
                    FileCapabilityPolicy.describe(file, ctx.config()).orElse(null);
            if (capability != null && capability.enabled()) {
                if (revalidateCandidate(candidate, root, ctx) == CandidateAccess.SEARCHABLE) {
                    searchExtractedFile(file, candidate.displayPath(), root, ctx, pattern,
                            matches, matchedPaths, maxResults, skippedUnsupportedDocuments);
                }
                continue;
            }

            if (UnsupportedDocumentFormats.isUnsupported(file)) {
                skippedUnsupportedDocuments.add(candidate.displayPath());
                continue;
            }

            if (revalidateCandidate(candidate, root, ctx) != CandidateAccess.SEARCHABLE) {
                continue;
            }
            // Skip binary-looking files (quick heuristic: check first bytes).
            if (looksLikeBinary(file)) {
                skippedUnsupportedDocuments.add(candidate.displayPath());
                continue;
            }

            if (revalidateCandidate(candidate, root, ctx) == CandidateAccess.SEARCHABLE) {
                searchFile(file, candidate.displayPath(), pattern, matches, matchedPaths,
                        maxResults, privateMode);
            }
        }

        if (matches.isEmpty()) {
            String safePattern = ProtectedContentSanitizer.sanitizeText(patternStr);
            return ToolResult.ok(
                    "No matches found in searchable non-protected text files for: " + safePattern
                            + ProtectedContentMessages.protectedContentNote(skippedProtected[0])
                            + unsupportedDocumentNote(skippedUnsupportedDocuments),
                    ToolContentMetadata.workspaceEvidence(
                            ToolContentMetadata.ContentSource.GREP,
                            List.of(),
                            "grep returned no source paths"));
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
        return ToolResult.ok(
                sb.toString(),
                ToolContentMetadata.workspaceEvidence(
                        ToolContentMetadata.ContentSource.GREP,
                        List.copyOf(matchedPaths),
                        "grep match source paths"));
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

    private static boolean hasPathSeparator(String glob) {
        return glob != null && (glob.indexOf('/') >= 0 || glob.indexOf('\\') >= 0);
    }

    private static String unsupportedDocumentNote(List<String> skippedUnsupportedDocuments) {
        if (skippedUnsupportedDocuments == null || skippedUnsupportedDocuments.isEmpty()) return "";
        // Entries are appended only while iterating the segment-sorted candidate snapshot.
        List<String> orderedDocuments = List.copyOf(skippedUnsupportedDocuments);
        StringBuilder out = new StringBuilder();
        out.append("\n\nSearch was limited to searchable text files. Skipped unsupported binary document(s): ");
        int limit = Math.min(5, orderedDocuments.size());
        out.append(String.join(", ", orderedDocuments.subList(0, limit)));
        if (orderedDocuments.size() > limit) {
            out.append(", ... ").append(orderedDocuments.size() - limit).append(" more");
        }
        out.append(". Talos grep cannot extract PDF/Office binary contents or other unsupported/binary files with the current local text-tool surface.");
        return out.toString();
    }

    private static void searchFile(Path file, String relPath, Pattern pattern,
                                   List<String> matches, LinkedHashSet<String> matchedPaths,
                                   int maxResults, boolean privateMode) {
        try (var input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(MAX_FILE_SIZE + 1L));
            if (bytes.length > MAX_FILE_SIZE) return;
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            List<String> lines = text.lines().toList();
            int remaining = Math.max(0, maxResults - matches.size());
            List<String> fileMatches = new ArrayList<>(Math.min(lines.size(), remaining));
            for (int i = 0; i < lines.size() && fileMatches.size() < remaining; i++) {
                String line = lines.get(i);
                if (pattern.matcher(line).find()) {
                    String safeLine = safeSearchLine(line.stripTrailing(), privateMode);
                    fileMatches.add(
                            relPath + ":" + (i + 1) + " | " + truncate(safeLine, 200));
                }
            }
            if (!fileMatches.isEmpty()) {
                matches.addAll(fileMatches);
                matchedPaths.add(relPath);
            }
        } catch (IOException ignored) {
            // skip files that can't be read as text
        }
    }

    private static void searchExtractedFile(
            Path file,
            String relPath,
            Path root,
            ToolContext ctx,
            Pattern pattern,
            List<String> matches,
            LinkedHashSet<String> matchedPaths,
            int maxResults,
            List<String> skippedUnsupportedDocuments) {
        boolean privateMode = PrivacyConfigFacts.privateMode(ctx.config());
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
                matchedPaths.add(relPath);
            }
        }
    }

    private static boolean looksLikeBinary(Path file) {
        try (var is = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
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

    private static SearchCandidate normalizeCandidate(Path root, Path file) {
        if (root == null || file == null) return null;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) return null;
        Path relative = normalizedRoot.relativize(normalizedFile).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || "..".equals(relative.getName(0).toString())) {
            return null;
        }
        return new SearchCandidate(normalizedFile, relative, displayPath(relative));
    }

    private static String displayPath(Path relative) {
        List<String> segments = new ArrayList<>(relative.getNameCount());
        for (Path segment : relative) {
            segments.add(segment.toString());
        }
        return String.join("/", segments);
    }

    private static int compareCandidatesByRelativeSegments(
            SearchCandidate left, SearchCandidate right) {
        int commonSegments = Math.min(
                left.relativePath().getNameCount(),
                right.relativePath().getNameCount());
        for (int i = 0; i < commonSegments; i++) {
            int compared = left.relativePath().getName(i).toString()
                    .compareTo(right.relativePath().getName(i).toString());
            if (compared != 0) return compared;
        }
        return Integer.compare(
                left.relativePath().getNameCount(),
                right.relativePath().getNameCount());
    }

    private static CandidateAccess revalidateCandidate(
            SearchCandidate candidate, Path root, ToolContext ctx) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate.path(),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!isOrdinaryRegularFile(attributes)
                    || attributes.size() > MAX_FILE_SIZE
                    || !ctx.sandbox().allowedPath(candidate.path())) {
                return CandidateAccess.SKIP;
            }
            if (ProtectedWorkspacePaths.isProtectedPath(root, candidate.path())) {
                return CandidateAccess.PROTECTED;
            }
            return CandidateAccess.SEARCHABLE;
        } catch (IOException | SecurityException ignored) {
            return CandidateAccess.SKIP;
        }
    }

    private static boolean isOrdinaryRegularFile(BasicFileAttributes attributes) {
        return attributes != null
                && attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && !attributes.isOther();
    }

    private enum CandidateAccess {
        SEARCHABLE,
        PROTECTED,
        SKIP
    }

    private record SearchCandidate(Path path, Path relativePath, String displayPath) {}

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
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

