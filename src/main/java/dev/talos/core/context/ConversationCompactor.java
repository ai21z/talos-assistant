package dev.talos.core.context;

import dev.talos.core.llm.LlmClient;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Summarizes older conversation turns into a compact sketch so that
 * the context window isn't wasted on verbatim history from 20 turns ago.
 *
 * <p>The compactor is stateless — it receives a list of turns and produces
 * a plain-text sketch. The caller ({@link ConversationManager}) decides
 * <em>when</em> to compact and stores the result.
 *
 * <p>Compaction flow:
 * <ol>
 *   <li>Caller identifies "old" turns (those that would be dropped by
 *       {@code buildHistory()} due to token budget overflow).</li>
 *   <li>Caller passes those turns + any existing sketch to
 *       {@link #compact(String, List, LlmClient)}.</li>
 *   <li>Compactor asks the LLM to produce a 2–4 sentence summary.</li>
 *   <li>Caller stores the returned sketch and discards the old turns.</li>
 * </ol>
 *
 * <p>If the LLM call fails (timeout, connection error, malformed output),
 * the compactor reports failure with the existing sketch unchanged — never loses context.
 *
 * @see ConversationManager
 */
public final class ConversationCompactor {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationCompactor.class);

    private ConversationCompactor() {} // utility class

    /**
     * System prompt for the compaction LLM call.
     * Kept intentionally short to minimize token overhead.
     */
    static final String COMPACTION_SYSTEM_PROMPT = """
            You are a conversation summarizer for a developer CLI tool.
            Given a prior sketch (if any) and recent conversation turns,
            produce a concise summary of 4-8 sentences capturing:
            - The user's current goal or task
            - Key decisions or facts established so far
            - Important file names, symbols, or technical details mentioned
            - Any specific creative output the user was iterating on (code, ASCII art, prose, diagrams) — preserve enough detail to continue refinement
            - The direction of iteration: what the user liked, what they wanted changed
            
            Return ONLY the summary text. No JSON, no markdown, no bullet points.
            Be factual and compact — every word should carry information.
            When the user was refining a specific artifact, include a brief description of its current state so the next turn can build on it.""";

    /**
     * Maximum characters for the user prompt sent to the compaction LLM.
     * Prevents sending enormous histories that would themselves overflow
     * the context window of the summarization call.
     */
    static final int MAX_INPUT_CHARS = 12_000;

    /**
     * Maximum characters for the returned sketch.
     * Summaries longer than this are truncated.
     */
    static final int MAX_SKETCH_CHARS = 2_000;

    /**
     * Result for a compaction attempt. Callers that may destructively prune
     * history must check {@link #succeeded()} before discarding old turns.
     */
    public record CompactionResult(String sketch, boolean succeeded, String reason, Category category) {
        public enum Category {
            SUCCESS,
            SKIPPED,
            LLM_FAILURE,
            BLANK_OUTPUT,
            INTEGRITY_REJECT
        }

        public CompactionResult {
            reason = reason == null || reason.isBlank() ? "not-specified" : reason;
            category = category == null ? (succeeded ? Category.SUCCESS : Category.LLM_FAILURE) : category;
        }

        public static CompactionResult succeeded(String sketch) {
            return new CompactionResult(sketch, true, "success", Category.SUCCESS);
        }

        public static CompactionResult succeeded(String sketch, String reason) {
            return new CompactionResult(sketch, true, reason == null || reason.isBlank() ? "success" : reason,
                    Category.SUCCESS);
        }

        public static CompactionResult skipped(String existingSketch, String reason) {
            return new CompactionResult(existingSketch, false, reason, Category.SKIPPED);
        }

        public static CompactionResult failed(String existingSketch, String reason) {
            return new CompactionResult(existingSketch, false, reason, Category.LLM_FAILURE);
        }

        public static CompactionResult blankOutput(String existingSketch) {
            return new CompactionResult(existingSketch, false, "empty-output", Category.BLANK_OUTPUT);
        }

        public static CompactionResult integrityRejected(String existingSketch, String reason) {
            return new CompactionResult(existingSketch, false, reason, Category.INTEGRITY_REJECT);
        }

        public boolean countsTowardFailureBreaker() {
            return category == Category.LLM_FAILURE || category == Category.BLANK_OUTPUT;
        }
    }

    /**
     * Compact old conversation turns into a sketch.
     *
     * @param existingSketch previous sketch (may be null or empty)
     * @param oldTurns       turns to summarize (user/assistant pairs)
     * @param llm            the LLM client to use for summarization
     * @return the new sketch, or {@code existingSketch} if compaction fails
     */
    public static String compact(String existingSketch, List<ChatMessage> oldTurns, LlmClient llm) {
        return tryCompact(existingSketch, oldTurns, llm).sketch();
    }

    /**
     * Attempt to compact old conversation turns into a sketch with explicit
     * success/failure state for callers that gate destructive pruning.
     *
     * @param existingSketch previous sketch (may be null or empty)
     * @param oldTurns       turns to summarize (user/assistant pairs)
     * @param llm            the LLM client to use for summarization
     * @return compaction result carrying the sketch and success state
     */
    public static CompactionResult tryCompact(String existingSketch, List<ChatMessage> oldTurns, LlmClient llm) {
        Objects.requireNonNull(llm, "llm must not be null");

        if (oldTurns == null || oldTurns.isEmpty()) {
            return CompactionResult.skipped(existingSketch, "no-old-turns");
        }

        String userPrompt = buildCompactionPrompt(existingSketch, oldTurns);

        try {
            String sketch = llm.chatPlain(COMPACTION_SYSTEM_PROMPT, userPrompt);
            if (sketch == null || sketch.isBlank()) {
                LOG.warn("Compaction returned empty sketch, keeping existing");
                return CompactionResult.blankOutput(existingSketch);
            }
            sketch = sketch.strip();
            if (sketch.length() > MAX_SKETCH_CHARS) {
                sketch = sketch.substring(0, MAX_SKETCH_CHARS);
            }
            CompactionIntegrityPolicy.Result integrity =
                    CompactionIntegrityPolicy.validate(existingSketch, oldTurns, sketch);
            if (!integrity.succeeded()) {
                LOG.warn("Compaction sketch rejected by integrity policy: reason={}", integrity.reason());
                return CompactionResult.integrityRejected(existingSketch, integrity.reason());
            }
            LOG.info("Conversation compacted: {} turns → {} char sketch", oldTurns.size(), integrity.sketch().length());
            return CompactionResult.succeeded(integrity.sketch(), integrity.reason());
        } catch (Exception e) {
            LOG.warn("Compaction LLM call failed, keeping existing sketch (exception={})",
                    e.getClass().getSimpleName());
            return CompactionResult.failed(existingSketch, "exception:" + e.getClass().getSimpleName());
        }
    }

    /**
     * Build the user-role prompt for the compaction call.
     * Includes the existing sketch (if any) and the old turns formatted
     * as a simple transcript.
     */
    static String buildCompactionPrompt(String existingSketch, List<ChatMessage> oldTurns) {
        StringBuilder sb = new StringBuilder();

        if (existingSketch != null && !existingSketch.isBlank()) {
            sb.append("Prior summary:\n").append(safePromptText(existingSketch.strip())).append("\n\n");
        }

        sb.append("Recent conversation turns to incorporate:\n\n");

        for (ChatMessage msg : oldTurns) {
            String role = switch (msg.role()) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                default -> msg.role();
            };
            String content = safePromptText(msg.content());
            // Truncate very long individual messages
            if (content != null && content.length() > 2000) {
                content = content.substring(0, 2000) + "…";
            }
            sb.append(role).append(": ").append(content != null ? content : "").append("\n\n");
        }

        // Cap total input
        String prompt = sb.toString();
        if (prompt.length() > MAX_INPUT_CHARS) {
            prompt = prompt.substring(prompt.length() - MAX_INPUT_CHARS);
        }
        return prompt;
    }

    private static String safePromptText(String text) {
        String sanitized = ProtectedContentSanitizer.sanitizeText(text);
        return sanitized == null ? "" : sanitized;
    }
}

