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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        String system = SystemPromptBuilder.forAsk()
                .withTools(ctx.toolRegistry())
                .withHistory(hasHistory)
                .build();

        // Build structured conversation messages for /api/chat
        List<ChatMessage> messages = buildMessages(system, rawLine, ctx);

        StringBuilder out = new StringBuilder();
        out.append("\n");
        try {
            final List<ChatMessage> msgs = messages;
            CompletableFuture<String> fut = CompletableFuture.supplyAsync(
                    () -> ctx.llm().chat(msgs));
            String answer = fut.get(llmTimeoutMs, TimeUnit.MILLISECONDS);
            if (answer != null) {
                if (answer.length() > responseMaxChars) {
                    out.append(answer, 0, (int) responseMaxChars).append("\n\n[output truncated]\n");
                } else {
                    out.append(answer);
                }
                // Memory update is now centralized in TurnProcessor via SessionListener
            } else {
                out.append("(no answer)");
            }
        } catch (java.util.concurrent.TimeoutException te) {
            out.append("\n[Timeout: LLM response took too long]\n");
        } catch (Exception e) {
            out.append("\n[Error during LLM call]\n");
        }
        out.append("\n\n");

        return Optional.of(new Result.Ok(out.toString()));
    }

    /**
     * Builds a structured list of ChatMessages for the /api/chat endpoint.
     *
     * <p>Includes: system prompt → budget-aware prior conversation turns → current user message.
     * Uses {@code ConversationManager.buildHistory()} when available to respect
     * context window limits. Falls back to raw {@code SessionMemory.getTurns()}
     * for backward compatibility.
     */
    static List<ChatMessage> buildMessages(String system, String rawLine, Context ctx) {
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

        // Add current user message
        messages.add(ChatMessage.user(rawLine));
        LOG.debug("buildMessages: total {} messages (1 system + {} history + 1 current)",
                messages.size(), messages.size() - 2);
        return messages;
    }

    /**
     * Builds a contextual prompt by prepending recent conversation history.
     *
     * <p>If the session has prior turns, the prompt includes them so the LLM
     * can maintain conversational continuity (e.g. remembering a request for
     * ASCII art across follow-up turns).
     *
     * <p>When no history exists, the raw user input is returned unchanged.
     *
     * <p><b>Note:</b> This is the legacy flat-text approach, kept for backward
     * compatibility and testing. The primary LLM call now uses
     * {@link #buildMessages(String, String, Context)} with structured messages.
     */
    static String buildContextualPrompt(String rawLine, Context ctx) {
        if (ctx.memory() == null) return rawLine;
        String history = ctx.memory().get();
        if (history == null || history.isBlank()) return rawLine;
        return "[Conversation so far]\n" + history + "\n\n[Current message]\n" + rawLine;
    }


}
