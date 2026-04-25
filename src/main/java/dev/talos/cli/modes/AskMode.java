package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.core.CfgUtil;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ask mode: plain LLM chat (no RAG context). */
public final class AskMode implements Mode {
    private static final Logger LOG = LoggerFactory.getLogger(AskMode.class);
    @Override public String name() { return "ask"; }

    @Override public boolean canHandle(String rawLine) {
        return rawLine != null && !rawLine.isBlank();
    }

    // Helpers to catch exact-echo style prompts
    private static final Pattern EXACT_P =
            Pattern.compile("^\\s*Respond\\s+with\\s+exactly:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_STRIP_P =
            Pattern.compile("^\\s*Print\\s+this\\s+without\\s+the\\s+think\\s+tags:\\s*<think>(.*?)</think>\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public Optional<Result> handle(String rawLine, Path workspace, Context ctx) throws Exception {
        if (rawLine == null || rawLine.isBlank() || ctx == null || ctx.llm() == null) return Optional.empty();

        // Fast-path: exact echo
        Matcher m1 = EXACT_P.matcher(rawLine);
        if (m1.find()) {
            String out = m1.group(1);
            return Optional.of(new Result.Ok(out));
        }
        // Fast-path: <think>…</think> stripping + trailing text preserve
        Matcher m2 = THINK_STRIP_P.matcher(rawLine);
        if (m2.find()) {
            String inner = m2.group(1);
            String tail  = m2.group(2) == null ? "" : m2.group(2);
            String out = (inner + (tail.isBlank() ? "" : " " + tail)).trim();
            return Optional.of(new Result.Ok(out));
        }

        // Limits
        var lim = CfgUtil.map(ctx.cfg().data.get("limits"));
        long responseMaxChars = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        long llmTimeoutMs     = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);

        // System prompt — composed from sections, tool-aware, history-aware
        boolean hasHistory = (ctx.conversationManager() != null && ctx.conversationManager().hasHistory())
                || (ctx.memory() != null && ctx.memory().hasContent());
        boolean nativeTools = CfgUtil.boolAt(CfgUtil.map(ctx.cfg().data.get("tools")), "native_calling", true);
        String system = SystemPromptBuilder.forAsk()
                .withTools(ctx.toolRegistry())
                .withWorkspace(workspace)
                .withNativeTools(nativeTools)
                .withHistory(hasHistory)
                .build();

        // Build conversation history — AskMode uses a larger budget (55% vs 25%)
        // because there are no RAG snippets competing for context space.
        // This is critical for multi-turn creative tasks.
        List<ChatMessage> history = List.of();
        if (ctx.conversationManager() != null) {
            history = ctx.conversationManager().buildHistoryForAssist();
        } else if (ctx.memory() != null) {
            history = ctx.memory().getTurns();
        }

        // Build structured conversation messages for /api/chat
        List<ChatMessage> messages = buildMessages(system, rawLine, history);
        LastPromptCapture.record(PromptInspector.fromMessages(
                "ask",
                "ask",
                workspace,
                ctx,
                nativeTools,
                history.size(),
                messages));

        // Execute LLM turn via shared executor
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
     * Builds a structured list of ChatMessages for the /api/chat endpoint.
     *
     * <p>Includes: system prompt → pre-built conversation history → current user message.
     * The caller is responsible for building history (and measuring its token cost)
     * before invoking this method.
     *
     * @param system   the system prompt text
     * @param rawLine  the current user message
     * @param history  pre-built conversation history messages (may be empty)
     * @return mutable list of ChatMessages ready for the LLM
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

        // Add current user message
        messages.add(ChatMessage.user(rawLine));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + 1 current)",
                messages.size(), (history != null ? history.size() : 0));
        return messages;
    }


}
