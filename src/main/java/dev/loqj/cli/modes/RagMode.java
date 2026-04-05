package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Limits;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.ingest.ParserUtil;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.context.ContextPacker;
import dev.loqj.core.context.ContextResult;
import dev.loqj.core.context.TokenBudget;
import dev.loqj.core.search.SnippetBuilder;
import dev.loqj.core.util.Sanitize;
import dev.loqj.core.security.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG mode implementation that builds snippets with pinned files prioritized first,
 * calls the LLM once, and reuses the same prepared result for citations.
 */
public final class RagMode implements Mode {

    private static final Logger LOG = LoggerFactory.getLogger(RagMode.class);

    @Override public String name() { return "rag"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        String q = rawLine.trim();
        if (q.isEmpty()) return Optional.of(new Result.Info("(empty query)"));

        final Limits lim = ctx.limits();
        final int topK = Math.max(1, Math.min(lim.topKMax(), ctx.session().getK()));

        // Pin files mentioned in the question
        var pinnedSnips = pinFiles(workspace, q, 3, 1600, lim.dirDepthMax());

        // Extract unique base file paths (without #chunk suffix) from pinned snippets
        Set<String> pinnedBaseFiles = new LinkedHashSet<>();
        for (var snip : pinnedSnips) {
            String base = stripChunkId(snip.path());
            pinnedBaseFiles.add(base);
        }

        boolean isTwoFileComparison = pinnedBaseFiles.size() == 2;

        // Prepare RAG context once (BM25F + vectors if enabled)
        RagService.Prepared prepared = ctx.rag().prepare(workspace, q, topK);

        // Pack snippets using unified ContextPacker (pinned-first, budget-aware, deduplicated)
        List<ContextResult.Snippet> pinnedCtx = new ArrayList<>();
        for (var snip : pinnedSnips) {
            pinnedCtx.add(new ContextResult.Snippet(snip.path(), snip.text()));
        }
        List<ContextResult.Snippet> regularCtx = prepared.snippets();

        // Load system prompt (needed for token budget calculation)
        String system = readOrFallback("prompts/rag-system.txt", ctx);

        ContextPacker packer = new ContextPacker(TokenBudget.fromConfig(ctx.cfg()));
        ContextResult packed = packer.pack(system, q, pinnedCtx, regularCtx, isTwoFileComparison);

        // Anchor snippet paths with backticks for model clarity
        List<Map<String,String>> ctxMaps = new ArrayList<>(packed.finalCount());
        for (var s : packed.snippets()) {
            String anchoredPath = "`" + s.path() + "`";
            ctxMaps.add(Map.of("path", anchoredPath, "text", s.text()));
        }


        // Prepend comparison intent if exactly two files are pinned
        String userMessage = q;
        if (isTwoFileComparison) {
            List<String> fileList = new ArrayList<>(pinnedBaseFiles);
            String file1 = fileList.get(0);
            String file2 = fileList.get(1);
            userMessage = "Compare these two files exactly: " + file1 + " vs " + file2 + ". Use only the provided snippets.\n"
                        + "Files in play: " + file1 + " | " + file2 + "\n\n"
                        + q;
        }

        // Call LLM (non-stream), sanitize output (strip preambles & model-added sources), then cap
        String answer = ctx.llm().chat(system, userMessage, ctxMaps);
        answer = sanitizeAnswer(answer);
        answer = Sanitize.sanitizeForOutput(answer);
        if (answer.length() > lim.responseMaxChars()) {
            answer = answer.substring(0, (int) lim.responseMaxChars()) + "\n\n[output truncated]";
        }

        // Build citations section from ContextResult - paths normalized to forward slashes
        StringBuilder out = new StringBuilder();
        out.append(answer);
        if (!packed.citations().isEmpty()) {
            out.append("\n\n[Sources]\n");
            Set<String> shown = new LinkedHashSet<>();
            for (String c : packed.citations()) {
                String normalized = normalizePathSeparators(c);
                if (shown.add(normalized)) {
                    out.append(" - ").append(normalized).append("\n");
                }
            }
        }

        // Update session memory so follow-up turns (even in AskMode) have conversation context
        if (ctx.memory() != null && !answer.isBlank()) {
            ctx.memory().update(q, answer);
        }

        return Optional.of(new Result.Ok(out.toString()));
    }

    /**
     * FILE_TOKEN pattern for matching file references in user queries.
     * Supports:
     * - Case-insensitive extensions
     * - Both path separators (backslash and forward slash)
     * - Quoted paths with spaces
     * - Common script/config/web/build extensions
     * - Dotfiles with no extension (e.g., .editorconfig, .env)
     * - Captures the entire token for secure resolution
     */
    private static final Pattern FILE_TOKEN = Pattern.compile(
        "(?:" +
            // Branch 1: Quoted path (with spaces allowed)
            "\"((?:[A-Za-z]:)?[/\\\\]?[^\"]+)\"" +
            "|" +
            // Branch 2: Unquoted path with extension (case-insensitive)
            "((?:[A-Za-z]:)?[/\\\\]?[A-Za-z0-9_./\\\\-]+\\." +
                "(?i:ps1|psm1|psd1|cmd|bat|sh|bash|zsh|fish|" +
                "ts|tsx|js|jsx|mjs|cjs|css|scss|sass|less|" +
                "csv|tsv|toml|ini|cfg|conf|config|lock|" +
                "gradle|kts|pom|" +
                "md|markdown|mdx|txt|rst|adoc|" +
                "json|json5|yaml|yml|xml|html|htm|" +
                "java|kt|groovy|scala|" +
                "py|rb|go|rs|cpp|c|h|hpp|cs|php|" +
                "properties|env|gitignore|gitattributes|" +
                "sql|dockerfile))" +
            "|" +
            // Branch 3: Common extensionless files (LICENSE, README, etc.)
            "\\b(LICENSE|README|NOTICE|COPYRIGHT|AUTHORS|CHANGELOG|CONTRIBUTING|MAKEFILE|Dockerfile)\\b" +
            "|" +
            // Branch 4: Dotfiles (e.g., .editorconfig, .env, .npmrc)
            "(\\.[A-Za-z0-9_][A-Za-z0-9_.\\-]{1,})" +
        ")",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * Pins files mentioned in the question by extracting file-like tokens and resolving them
     * against the workspace. Files are validated against workspace boundaries for security.
     *
     * @param ws workspace root path
     * @param question user's question text
     * @param maxPins maximum number of files to pin
     * @param maxChars maximum characters per file snippet
     * @param maxDepth maximum directory depth for file search
     * @return list of pinned file snippets
     */
    private static List<SnippetBuilder.Snippet> pinFiles(Path ws, String question, int maxPins, int maxChars, int maxDepth) {
        List<SnippetBuilder.Snippet> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Sandbox sandbox = new Sandbox(ws, Map.of());

        Matcher m = FILE_TOKEN.matcher(question);
        while (m.find() && out.size() < maxPins) {
            // Extract token from whichever group matched
            String token = null;
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) {
                    token = m.group(i);
                    break;
                }
            }

            if (token == null || token.isEmpty()) continue;

            String originalToken = token;

            if (!seen.add(token)) continue;

            // Strip surrounding quotes if present
            if ((token.startsWith("\"") && token.endsWith("\"")) ||
                (token.startsWith("'") && token.endsWith("'"))) {
                token = token.substring(1, token.length() - 1);
            }

            // Normalize: replace backslashes with forward slashes before resolution
            String tokenNormalized = token.replace('\\', '/');

            // Secure resolve: check against workspace boundary
            Path candidate = ws.resolve(tokenNormalized).normalize();

            // Reject anything outside workspace
            if (!sandbox.allowedPath(candidate)) {
                LOG.debug("pinned-miss:{} (outside workspace, normalized:{})", originalToken, tokenNormalized);
                continue;
            }

            // Check if it's a regular file
            if (Files.isRegularFile(candidate)) {
                // Compute relative path and normalize to forward slashes
                String rel = ws.relativize(candidate).toString().replace('\\', '/');
                addSnippet(ws, out, candidate, maxChars, rel);
                LOG.debug("pin-found:{} (from token:{})", rel, originalToken);
            } else {
                // If not found directly, search by filename
                String base = Path.of(tokenNormalized).getFileName().toString();
                try (var walk = Files.walk(ws, maxDepth)) {
                    Optional<Path> hit = walk
                            .filter(Files::isRegularFile)
                            .filter(x -> x.getFileName().toString().equalsIgnoreCase(base))
                            .filter(sandbox::allowedPath)
                            .findFirst();
                    if (hit.isPresent()) {
                        Path hitPath = hit.get();
                        String rel = ws.relativize(hitPath).toString().replace('\\', '/');
                        addSnippet(ws, out, hitPath, maxChars, rel);
                        LOG.debug("pin-found:{} (basename match from:{})", rel, originalToken);
                    } else {
                        LOG.debug("pinned-miss:{} (normalized:{}, not found)", originalToken, tokenNormalized);
                    }
                } catch (Exception e) {
                    LOG.debug("pinned-miss:{} (normalized:{}, walk failed: {})", originalToken, tokenNormalized, e.getMessage());
                }
            }
        }

        return out;
    }

    /**
     * Adds a file snippet to the output list after parsing and truncating if necessary.
     */
    private static void addSnippet(Path ws, List<SnippetBuilder.Snippet> out, Path p, int maxChars, String relPath) {
        try {
            String text = ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(new SnippetBuilder.Snippet(relPath + "#0", text));
        } catch (Exception e) {
            LOG.debug("Failed to read pinned file {}: {}", relPath, e.getMessage());
        }
    }

    /**
     * Sanitizes LLM answer by stripping chatty preambles and model-added Sources/Citations blocks.
     * Expanded patterns are used to catch common model chattiness.
     */
    private static String sanitizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) return "";

        // Strip preambles at the start
        answer = answer.replaceFirst(
            "(?is)^\\s*(" +
            "okay|sure|let me|i (?:will|can)|here['']?s|" +
            "looking at the|now,|starting with|comparing the two|" +
            "the user is asking|first, i need to|" +
            "i couldn't find that here\\. the context|wait," +
            ")\\b[^\\n]*(?:\\n\\n|\\n|$)",
            ""
        );

        // Remove model-added Sources/Citations blocks
        answer = answer.replaceAll("(?is)\\n\\s*\\[?\\s*(?:citations?|sources?)\\s*\\]?\\s*:?\\s*\\n(?:\\s*[-*]\\s+[^\\n]+\\n)*", "");

        return answer.trim();
    }

    /**
     * Normalizes path separators to forward slashes for consistent cross-platform output.
     */
    private static String normalizePathSeparators(String path) {
        if (path == null) return "";
        return path.replace('\\', '/');
    }

    /**
     * Reads a resource from the classpath or falls back to context default.
     */
    private static String readOrFallback(String resource, Context ctx) throws Exception {
        try (var in = RagMode.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) return new String(in.readAllBytes());
        }
        return ctx.rag().readCliSystemPromptOrDefault();
    }

    /**
     * Strips chunk ID suffix from a path (everything after #).
     */
    private static String stripChunkId(String path) {
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }
}
