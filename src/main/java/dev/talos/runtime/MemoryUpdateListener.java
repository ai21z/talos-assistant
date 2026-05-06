package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.trace.TraceRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SessionListener that centralizes memory updates after each turn.
 *
 * <p>Replaces the ad-hoc {@code ctx.memory().update()} calls that were
 * scattered across AskMode and RagMode. Now TurnProcessor fires this
 * listener after every successful turn, and it records the user input
 * and the assistant's response in the ConversationManager.
 *
 * <p>After recording the turn, checks whether compaction is needed.
 * If the conversation history has grown beyond the token budget threshold,
 * older turns are summarized into a compact sketch via the LLM.
 *
 * <p>The assistant response is extracted from the {@link TurnResult}
 * using {@link #extractText(Result)}, which handles all text-carrying
 * result types — including {@link Result.Streamed} (the primary streaming
 * path) and {@link Result.Ok} (non-streaming / tool-call fallback).
 */
public final class MemoryUpdateListener implements SessionListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryUpdateListener.class);

    private final ConversationManager conversationManager;
    private final LlmClient llm;
    private volatile boolean assistMode;

    /**
     * @param conversationManager the conversation manager to record turns into
     * @param llm                 the LLM client for compaction calls (may be null to disable compaction)
     */
    public MemoryUpdateListener(ConversationManager conversationManager, LlmClient llm) {
        this.conversationManager = conversationManager;
        this.llm = llm;
    }

    /** Constructor without LLM — compaction is disabled. */
    public MemoryUpdateListener(ConversationManager conversationManager) {
        this(conversationManager, null);
    }

    /**
     * Enable assist/unified mode compaction.
     * When true, uses the larger 55% budget and higher pair threshold
     * ({@link ConversationManager#maybeCompactForAssist}) instead of
     * the default 25% RAG-mode budget.
     */
    public void setAssistMode(boolean assistMode) {
        this.assistMode = assistMode;
    }

    @Override
    public void onTurnComplete(TurnResult result, String userInput) {
        if (result == null || userInput == null || userInput.isBlank()) return;

        String answer = extractText(result.result());
        if (answer != null && !answer.isBlank()) {
            // BUG #1 fix — strip Talos's UI status chrome before persisting
            // to history. Otherwise the model sees its own previous turn
            // decorated with "[Used N tool(s)…]" and "✓ Edited X…" status
            // lines, learns to imitate the format, and starts emitting them
            // as PROSE on later turns without actually calling any tool —
            // a confidence-trick failure mode (4 fabricated turns observed
            // in a real qwen2.5-coder transcript). Render-side chrome must
            // never be part of the model's training surface.
            String forHistory = assistantTextForPersistence(answer, userInput);
            if (!isMemorizableAssistantReply(result.result(), forHistory)) return;
            if (forHistory.isBlank()) return;
            conversationManager.addTurn(userInput, forHistory);

            // Trigger compaction check (non-blocking — if LLM is null, this is a no-op)
            if (llm != null) {
                try {
                    boolean compacted = assistMode
                            ? conversationManager.maybeCompactForAssist(llm)
                            : conversationManager.maybeCompact(llm);
                    if (compacted) {
                        LOG.debug("Conversation compacted after turn");
                    }
                } catch (Exception e) {
                    LOG.warn("Compaction check failed (non-fatal): {}", e.getMessage());
                }
            }
        }
    }

    /**
     * BUG #1 fix — strip Talos's own UI status chrome from assistant text
     * before persisting to conversation history.
     *
     * <p><b>Why:</b> {@code AssistantTurnExecutor.appendSummary} appends
     * {@code "[Used N tool(s): … | M iteration(s)]"} and the tool-call
     * loop prepends {@code "✓ Edited X: replaced N line(s)…"} lines into
     * the streamed text that becomes {@code Result.Streamed.fullText}.
     * Without this filter, that decorated string lands verbatim in the
     * conversation history and the next-turn model sees it as if the
     * assistant had spoken those words. Code-tuned local models (observed:
     * qwen2.5-coder:14b, real transcript Apr 2026) memorize the format
     * after one exposure and start emitting fake {@code [Used 2 tool(s)…]}
     * / {@code ✓ Edited X…} blocks as plain prose on subsequent turns
     * without calling any tool — a confidence-trick failure mode where
     * the assistant convincingly claims work it never did. Render-side
     * chrome must never be part of the model's training surface.
     *
     * <p>The stripped patterns are intentionally narrow — only whole-line
     * matches against known Talos-emitted prefixes are removed; actual
     * model prose containing brackets is preserved.
     */
    public static String stripUiChromeForHistory(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\\R", -1)) {
            String t = line.trim();
            if (t.startsWith("[Used ") && t.contains("tool(s)")) continue;
            if (t.startsWith("[Tool-call limit reached")) continue;
            if (t.startsWith("[turn aborted")) continue;
            if (t.startsWith("[iteration limit")) continue;
            if (t.startsWith("[Engine error")) continue;
            if (t.startsWith("[Model '") && t.contains("' not found")) continue;
            if (t.startsWith("✓ Edited ")) continue;
            if (t.startsWith("✓ Wrote ")) continue;
            if (t.startsWith("✓ Created ")) continue;
            if (t.startsWith("Suggestion: edit_file has failed")) continue;
            out.append(line).append('\n');
        }
        String stripped = out.toString().replaceAll("\\n{3,}", "\n\n").strip();
        return TraceRedactor.redactSecretLikeAssignments(stripped);
    }

    public static String assistantTextForPersistence(String text, String userInput) {
        String stripped = stripUiChromeForHistory(text);
        return TraceRedactor.redactProtectedReadAnswerForPersistence(userInput, stripped);
    }

    /**
     * Keep only genuinely conversational assistant replies in memory.
     * Streamed answers that are just error wrappers or generic capability
     * refusals are not useful context for later turns.
     */
    static boolean isMemorizableAssistantReply(Result result, String stripped) {
        if (!(result instanceof Result.Ok || result instanceof Result.Streamed)) return false;
        if (stripped == null || stripped.isBlank()) return false;
        String lower = stripped.stripLeading().toLowerCase();
        if (lower.startsWith("[engine error")) return false;
        if (lower.startsWith("[model '") && lower.contains("' not found")) return false;
        if (looksLikeToolRefusal(lower)) return false;
        return true;
    }

    private static boolean looksLikeToolRefusal(String lower) {
        if (lower == null || lower.isBlank()) return false;
        boolean aiTextAssistant = lower.contains("i am an ai text-based assistant")
                || lower.contains("i'm an ai text-based assistant")
                || lower.contains("as an ai text-based assistant");
        boolean cannotDirectly = lower.contains("cannot directly edit files on your system")
                || lower.contains("can't directly edit files on your system")
                || lower.contains("unable to directly edit files on your system")
                || lower.contains("cannot directly read files from your system")
                || lower.contains("don't have the capability to directly read files from your system");
        return aiTextAssistant || cannotDirectly;
    }

    /**
     * Extracts memorizable text from a Result.
     *
     * <p>Only LLM response types are memorized:
     * <ul>
     *   <li>{@link Result.Ok}       — non-streamed LLM answers (tool-call fallback, non-interactive)</li>
     *   <li>{@link Result.Streamed}  — streamed LLM answers (primary path; uses fullText, excludes suffix)</li>
     * </ul>
     *
     * <p>System messages (Info, TrustedInfo), errors, tables, and streaming lifecycle
     * markers are NOT memorized — they are not conversational exchanges.
     *
     * @param r the result to extract text from
     * @return the text content, or null if the result type is not memorizable
     */
    static String extractText(Result r) {
        if (r == null) return null;
        return switch (r) {
            case Result.Ok ok           -> ok.text;
            case Result.Streamed s      -> s.fullText;
            case Result.Info ignored     -> null;
            case Result.TrustedInfo ignored -> null;
            case Result.Error ignored   -> null;
            case Result.Table ignored   -> null;
            case Result.StreamStart ignored  -> null;
            case Result.StreamChunk ignored  -> null;
            case Result.StreamEnd ignored    -> null;
            case Result.ToolProgress ignored -> null;
        };
    }
}
