package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.CfgUtil;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unified assistant mode: single action-capable mode for all natural-language work.
 *
 * <p>This mode replaces the RETRIEVE → RagMode routing in auto-mode. Instead of
 * pre-injecting RAG snippets, the model decides when to retrieve context by
 * calling {@code talos.retrieve} or {@code talos.read_file} as tools.
 *
 * <p>Capabilities available to the model:
 * <ul>
 *   <li>Full tool access (read, write, edit, list, grep, retrieve)</li>
 *   <li>Workspace manifest for project awareness</li>
 *   <li>Conversation history for continuity</li>
 *   <li>Explicit guidance to use tools for file ops and retrieval for code questions</li>
 * </ul>
 *
 * <p>Uses {@link AssistantTurnExecutor} for execution (same pipeline as AskMode
 * and RagMode), avoiding any code duplication.
 *
 * <p>Design notes:
 * <ul>
 *   <li>No pre-injected RAG context — the model pulls context on demand via tools</li>
 *   <li>Uses {@link SystemPromptBuilder#forUnified()} for merged behavior rules</li>
 *   <li>Larger history budget (55%) since no RAG snippets compete for context space</li>
 *   <li>RagMode remains available via explicit {@code /mode rag}</li>
 * </ul>
 */
public final class UnifiedAssistantMode implements Mode {

    private static final Logger LOG = LoggerFactory.getLogger(UnifiedAssistantMode.class);

    @Override public String name() { return "unified"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) {
            return Optional.empty();
        }

        // Limits
        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs     = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);

        // System prompt — unified mode: tools + workspace + retrieval guidance
        boolean hasHistory = (ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx.memory() != null && ctx.memory().hasContent());
        boolean nativeTools = CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
        String system = SystemPromptBuilder.forUnified()
                .withTools(ctx.toolRegistry())
                .withWorkspace(workspace)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .build();

        // Build conversation history — unified mode uses the larger assist budget (55%)
        // since there are no pre-injected RAG snippets competing for context space.
        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistoryForAssist();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }

        // Build structured conversation messages: system + history + user
        List<ChatMessage> messages = buildMessages(system, rawLine, history);

        // Execute LLM turn via shared executor (streaming, tool-call loop, error handling)
        var opts = new AssistantTurnExecutor.Options()
                .llmTimeoutMs(llmTimeoutMs)
                .responseMaxChars(responseMaxChars);

        AssistantTurnExecutor.TurnOutput turnOut =
                AssistantTurnExecutor.execute(messages, workspace, ctx, opts);

        String body = "\n" + turnOut.text() + "\n\n";

        if (turnOut.streamed()) {
            return Optional.of(new Result.Streamed(body, ""));
        }
        return Optional.of(new Result.Ok(body));
    }

    /**
     * Build structured ChatMessages: system → history → current user message.
     *
     * <p>Unlike RagMode, there is no RAG context injection here. The model
     * uses {@code talos.retrieve} and {@code talos.read_file} tools on demand.
     */
    static List<ChatMessage> buildMessages(String system, String rawLine, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(system));

        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
            LOG.debug("buildMessages: including {} history turns ({} exchanges)",
                    history.size(), history.size() / 2);
        } else {
            LOG.debug("buildMessages: no history turns (first message in session)");
        }

        messages.add(ChatMessage.user(rawLine));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + 1 current)",
                messages.size(), (history != null ? history.size() : 0));
        return messages;
    }
}

