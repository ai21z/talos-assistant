package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Limits;
import dev.talos.cli.repl.Result;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.core.CfgUtil;
import dev.talos.core.ingest.ParserUtil;
import dev.talos.core.rag.RagService;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.SystemPromptBuilder;

import dev.talos.core.util.Sanitize;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
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

    /** Local record for pinned file snippets — replaces legacy PinnedSnippet. */
    record PinnedSnippet(String path, String text) {
        PinnedSnippet {
            path = java.util.Objects.requireNonNullElse(path, "");
            text = java.util.Objects.requireNonNullElse(text, "");
        }
    }

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

        // Capture trace for runtime visibility (TurnProcessor reads this after dispatch)
        TurnTraceCapture.capture(prepared.trace());

        // Surface retrieval warnings when empty due to error (vs. genuinely no matches)
        if (prepared.hasError() && prepared.snippets().isEmpty()) {
            LOG.warn("Retrieval returned empty due to error: {}", prepared.errorReason());
        }

        // Pack snippets using unified ContextPacker (pinned-first, budget-aware, deduplicated)
        List<ContextResult.Snippet> pinnedCtx = new ArrayList<>();
        for (var snip : pinnedSnips) {
            pinnedCtx.add(new ContextResult.Snippet(snip.path(), snip.text()));
        }
        List<ContextResult.Snippet> regularCtx = prepared.snippets();

        // Load system prompt — composed from sections, tool-aware, history-aware
        boolean hasHistory = (ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx.memory() != null && ctx.memory().hasContent());
        boolean nativeTools = CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
        String system = SystemPromptBuilder.forRag()
                .withTools(ctx.toolRegistry())
                .withWorkspace(workspace)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .build();

        // Build conversation history BEFORE packing so we can account for its
        // token cost in the snippet budget (P0 budget coordination fix).
        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistory();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }

        TokenBudget tokenBudget = TokenBudget.fromConfig(ctx.cfg());
        int historyTokens = ConversationManager.estimateTokens(history, tokenBudget);

        ContextPacker packer = new ContextPacker(tokenBudget);
        ContextResult packed = packer.pack(system, q, historyTokens, pinnedCtx, regularCtx, isTwoFileComparison);

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
        List<ChatMessage> messages = buildMessages(system, userMessage, ctxMaps, history);
        LastPromptCapture.record(PromptInspector.fromMessages(
                "rag",
                "rag",
                workspace,
                ctx,
                nativeTools,
                history.size(),
                messages));

        // Execute LLM turn via shared executor (streaming, tool-call loop, error handling)
        var opts = new AssistantTurnExecutor.Options()
                .llmTimeoutMs(llmTimeoutMs)
                .responseMaxChars(lim.responseMaxChars())
                .answerSanitizer(a -> Sanitize.sanitizeForOutput(sanitizeAnswer(a)));

        AssistantTurnExecutor.TurnOutput turnOut =
                AssistantTurnExecutor.execute(messages, workspace, ctx, opts);

        // Build citations section from ContextResult - paths normalized to forward slashes
        String citationsSuffix = "";
        if (!packed.citations().isEmpty()) {
            StringBuilder citBuf = new StringBuilder();
            citBuf.append("\n\n[Sources]\n");
            Set<String> shown = new LinkedHashSet<>();
            for (String c : packed.citations()) {
                String normalized = normalizePathSeparators(c);
                if (shown.add(normalized)) {
                    citBuf.append(" - ").append(normalized).append("\n");
                }
            }
            citationsSuffix = citBuf.toString();
        }

        // Memory update is now centralized in TurnProcessor via SessionListener

        String fullText = turnOut.text() + citationsSuffix;
        if (turnOut.streamed()) {
            return Optional.of(new Result.Streamed(fullText, citationsSuffix));
        }
        return Optional.of(new Result.Ok(fullText));
    }

    /**
     * Builds ChatMessages for /api/chat: system → history → RAG context → user message.
     * History must be built before packing so its token cost is accounted for.
     */
    static List<ChatMessage> buildMessages(String system, String userMessage,
                                           List<Map<String,String>> ctxMaps,
                                           List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));

        // Add pre-built conversation history (already budget-trimmed by caller)
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
            LOG.debug("buildMessages: including {} history turns ({} exchanges)",
                    history.size(), history.size() / 2);
        } else {
            LOG.debug("buildMessages: no history turns (first message in session)");
        }

        // Inject RAG context as a user-role message before the question
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
        } else {
            // Empty retrieval: guide the model to use tools instead of saying "I can't see"
            messages.add(ChatMessage.user(
                "No context snippets were retrieved for this query. " +
                "The workspace may not be indexed yet, or the query didn't match any indexed content. " +
                "Use your tools (talos.list_dir, talos.read_file, talos.grep) to explore the workspace " +
                "and answer the user's question directly. Do NOT say 'I can't see your files' — you have tools."
            ));
        }

        // Add current user message
        messages.add(ChatMessage.user(userMessage));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + {} context + 1 current)",
                messages.size(), history.size(),
                (ctxMaps != null && !ctxMaps.isEmpty()) ? 1 : 0);
        return messages;
    }

    /** Matches file references in user queries (quoted paths, extensions, dotfiles, extensionless names). */
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

    /** Pins files mentioned in the question, resolving against workspace with sandbox validation. */
    private static List<PinnedSnippet> pinFiles(Path ws, String question, int maxPins, int maxChars, int maxDepth) {
        List<PinnedSnippet> out = new ArrayList<>();
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
    private static void addSnippet(Path ws, List<PinnedSnippet> out, Path p, int maxChars, String relPath) {
        try {
            String text = ParserUtil.smartParse(p);
            if (text.length() > maxChars) text = text.substring(0, maxChars);
            out.add(new PinnedSnippet(relPath + "#0", text));
        } catch (Exception e) {
            LOG.debug("Failed to read pinned file {}: {}", relPath, e.getMessage());
        }
    }

    /** Strips chatty preambles, leaked tool-call XML, and model-added Sources/Citations blocks. */
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

        // Defensive: strip any leaked tool-call blocks (tagged or code-fenced)
        answer = ToolCallParser.stripToolCalls(answer);

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
