package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Limits;
import dev.talos.cli.repl.Result;
import dev.talos.core.CfgUtil;
import dev.talos.core.ingest.ParserUtil;
import dev.talos.core.rag.RagService;
import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.core.search.SnippetBuilder;
import dev.talos.core.util.Sanitize;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

        // Limits for timeout
        var limMap = CfgUtil.map(ctx.cfg().data.get("limits"));
        long llmTimeoutMs = CfgUtil.longAt(limMap, "llm_timeout_ms", 300_000L);

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

        // Load system prompt — composed from sections, tool-aware, history-aware
        boolean hasHistory = (ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx.memory() != null && ctx.memory().hasContent());
        String system = SystemPromptBuilder.forRag()
                .withTools(ctx.toolRegistry())
                .withHistory(hasHistory)
                .build();

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

        // Build structured conversation messages for /api/chat
        List<ChatMessage> messages = buildMessages(system, userMessage, ctxMaps, ctx);

        // Call LLM with structured messages (with timeout)
        StringBuilder out = new StringBuilder();
        try {
            CompletableFuture<String> fut = CompletableFuture.supplyAsync(
                    () -> ctx.llm().chat(messages));
            String answer = fut.get(llmTimeoutMs, TimeUnit.MILLISECONDS);

            if (answer != null) {
                // Run tool-call loop if the response contains tool_call blocks
                if (ctx.toolCallLoop() != null && ToolCallParser.containsToolCalls(answer)) {
                    LOG.debug("Tool calls detected in RAG response, entering tool-call loop");
                    ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                            answer, messages, workspace, ctx);
                    answer = loopResult.finalAnswer();
                    LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked",
                            loopResult.iterations(), loopResult.toolsInvoked());
                }

                answer = sanitizeAnswer(answer);
                answer = Sanitize.sanitizeForOutput(answer);
                if (answer.length() > lim.responseMaxChars()) {
                    answer = answer.substring(0, (int) lim.responseMaxChars()) + "\n\n[output truncated]";
                }
                out.append(answer);
            } else {
                out.append("(no answer)");
            }
        } catch (java.util.concurrent.TimeoutException te) {
            out.append("\n[Timeout: LLM response took too long]\n");
        } catch (Exception e) {
            LOG.warn("LLM call failed in RAG mode: {}", e.getMessage());
            out.append("\n[Error during LLM call]\n");
        }

        // Build citations section from ContextResult - paths normalized to forward slashes
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

        // Memory update is now centralized in TurnProcessor via SessionListener

        return Optional.of(new Result.Ok(out.toString()));
    }

    /**
     * Builds a structured list of ChatMessages for the /api/chat endpoint.
     *
     * <p>Includes: system prompt → budget-aware prior conversation turns →
     * RAG context block (snippets) → current user message.
     * Uses {@code ConversationManager.buildHistory()} when available to respect
     * context window limits. Falls back to raw {@code SessionMemory.getTurns()}
     * for backward compatibility.
     *
     * <p>RAG context snippets are injected as a user-role message immediately
     * before the current question, keeping the system prompt stable across turns.
     *
     * @param system      the system prompt text
     * @param userMessage the current user question (possibly with comparison prefix)
     * @param ctxMaps     the packed RAG context snippets (path → text maps)
     * @param ctx         runtime context (provides conversation history)
     * @return mutable list of ChatMessages ready for the LLM
     */
    static List<ChatMessage> buildMessages(String system, String userMessage,
                                           List<Map<String,String>> ctxMaps, Context ctx) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));

        // Add prior conversation turns from ConversationManager (budget-aware) or memory (legacy)
        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistory();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }

        if (!history.isEmpty()) {
            messages.addAll(history);
            LOG.debug("buildMessages: including {} history turns ({} exchanges)",
                    history.size(), history.size() / 2);
        } else {
            LOG.debug("buildMessages: no history turns (first message in session)");
        }

        // Inject RAG context as a user-role message before the actual question.
        // This keeps the system prompt stable across turns while giving the model
        // the retrieved evidence it needs to ground its answer.
        if (ctxMaps != null && !ctxMaps.isEmpty()) {
            StringBuilder contextBlock = new StringBuilder();
            contextBlock.append("Here is the retrieved context from the codebase. ");
            contextBlock.append("Use these snippets to answer the question that follows.\n\n");
            for (var m : ctxMaps) {
                String path = m.getOrDefault("path", "");
                String text = m.getOrDefault("text", "");
                if (!path.isBlank()) contextBlock.append("[").append(path).append("]\n");
                if (!text.isBlank()) contextBlock.append(text).append("\n\n");
            }
            messages.add(ChatMessage.user(contextBlock.toString().stripTrailing()));
        }

        // Add current user message
        messages.add(ChatMessage.user(userMessage));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + {} context + 1 current)",
                messages.size(), history.size(),
                (ctxMaps != null && !ctxMaps.isEmpty()) ? 1 : 0);
        return messages;
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
     * Strips chunk ID suffix from a path (everything after #).
     */
    private static String stripChunkId(String path) {
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }
}
