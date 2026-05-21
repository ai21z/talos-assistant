package dev.talos.core.context;

import dev.talos.core.llm.LlmClient;
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
 * the compactor returns the existing sketch unchanged — never loses context.
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
     * Compact old conversation turns into a sketch.
     *
     * @param existingSketch previous sketch (may be null or empty)
     * @param oldTurns       turns to summarize (user/assistant pairs)
     * @param llm            the LLM client to use for summarization
     * @return the new sketch, or {@code existingSketch} if compaction fails
     */
    public static String compact(String existingSketch, List<ChatMessage> oldTurns, LlmClient llm) {
        Objects.requireNonNull(llm, "llm must not be null");

        if (oldTurns == null || oldTurns.isEmpty()) {
            return existingSketch; // nothing to compact
        }

        String userPrompt = buildCompactionPrompt(existingSketch, oldTurns);

        try {
            String sketch = llm.chatPlain(COMPACTION_SYSTEM_PROMPT, userPrompt);
            if (sketch == null || sketch.isBlank()) {
                LOG.warn("Compaction returned empty sketch, keeping existing");
                return existingSketch;
            }
            sketch = sketch.strip();
            if (sketch.length() > MAX_SKETCH_CHARS) {
                sketch = sketch.substring(0, MAX_SKETCH_CHARS);
            }
            LOG.info("Conversation compacted: {} turns → {} char sketch", oldTurns.size(), sketch.length());
            return sketch;
        } catch (Exception e) {
            LOG.warn("Compaction LLM call failed, keeping existing sketch (exception={})",
                    e.getClass().getSimpleName());
            return existingSketch;
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
            sb.append("Prior summary:\n").append(existingSketch.strip()).append("\n\n");
        }

        sb.append("Recent conversation turns to incorporate:\n\n");

        for (ChatMessage msg : oldTurns) {
            String role = switch (msg.role()) {
                case "user" -> "User";
                case "assistant" -> "Assistant";
                default -> msg.role();
            };
            String content = msg.content();
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
}

