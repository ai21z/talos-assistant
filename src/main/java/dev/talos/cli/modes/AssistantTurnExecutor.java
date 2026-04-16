package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Shared LLM turn execution logic for AskMode and RagMode.
 *
 * <p>Handles the streaming/non-streaming dispatch, tool-call loop integration,
 * response truncation, and typed error handling that was previously duplicated
 * (~80 lines) across both modes.
 *
 * <p>Both modes call {@link #execute(List, Path, Context, Options)} with their
 * prepared message list. The executor returns a {@link TurnOutput} containing
 * the response text and whether it was streamed.
 *
 * <p>Mode-specific concerns (RAG answer sanitization, citation suffixes,
 * system prompt composition) remain in the modes themselves. This class
 * only owns the LLM-call → tool-loop → error-handling lifecycle.
 */
final class AssistantTurnExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantTurnExecutor.class);

    private AssistantTurnExecutor() {} // utility class

    /**
     * Returns true if the answer text contains text-format tool calls
     * (JSON code fences, bare JSON, or XML compatibility tags).
     *
     * <p>Code-block file-write detection ({@link dev.talos.runtime.CodeBlockToolExtractor})
     * is intentionally NOT included here. Code-block writes are disabled — they only
     * produce a warning inside {@link ToolCallLoop#run}. Routing them through the
     * tool-loop entry gate would be misleading.
     */
    private static boolean hasAnyTextToolCalls(String answer) {
        return ToolCallParser.containsToolCalls(answer);
    }

    /** Returns true if native tool calls or text-based tool calls are present. */
    private static boolean hasAnyToolCalls(LlmClient.StreamResult result) {
        return result.hasToolCalls() || hasAnyTextToolCalls(result.text());
    }

    /**
     * Output of a turn execution.
     *
     * @param text     the full response text (may include tool summaries)
     * @param streamed true if content was streamed to the terminal during execution
     */
    record TurnOutput(String text, boolean streamed) {}

    /**
     * Execution options that vary between modes.
     */
    static final class Options {
        private long llmTimeoutMs = 300_000L;
        private long responseMaxChars = 10 * 1024 * 1024L;
        private UnaryOperator<String> answerSanitizer = UnaryOperator.identity();

        Options llmTimeoutMs(long ms)         { this.llmTimeoutMs = ms; return this; }
        Options responseMaxChars(long chars)   { this.responseMaxChars = chars; return this; }

        /**
         * Optional post-processing for the raw LLM answer (e.g., RAG preamble stripping).
         * Applied before truncation. AskMode passes identity; RagMode passes sanitizers.
         */
        Options answerSanitizer(UnaryOperator<String> fn) {
            this.answerSanitizer = (fn != null) ? fn : UnaryOperator.identity();
            return this;
        }
    }

    /**
     * Execute an LLM turn: streaming or non-streaming, with optional tool-call loop.
     *
     * @param messages  structured ChatMessage list (system + history + context + user)
     * @param workspace workspace root (for tool execution)
     * @param ctx       runtime context (provides llm, streamSink, toolCallLoop)
     * @param opts      mode-specific execution options
     * @return the turn output (text + streamed flag)
     */
    static TurnOutput execute(List<ChatMessage> messages, Path workspace,
                              Context ctx, Options opts) {
        StringBuilder out = new StringBuilder();
        boolean streamed = false;

        try {
            if (ctx.streamSink() != null) {
                // ── Streaming path ──────────────────────────────────────────
                LlmClient.StreamResult streamResult = ctx.llm().chatStreamFull(messages, ctx.streamSink());
                String answer = streamResult.text();

                // Flush the stream filter so any pending non-tool text is emitted
                if (ctx.streamSink() instanceof ToolCallStreamFilter filter) {
                    filter.flush();
                }

                // Stop the spinner unconditionally after streaming completes.
                // When the response is tool-call-only, the stream filter suppresses
                // all chunks so the rawSink (which normally stops the spinner) never
                // fires. Without this explicit stop, the spinner keeps running while
                // the tool-call loop (and approval gate) execute — making it look
                // like Talos is still "thinking" when it's actually waiting for input.
                if (ctx.onStreamComplete() != null) {
                    try { ctx.onStreamComplete().run(); } catch (Exception ignored) { }
                }

                if (answer != null) {
                    if (ctx.toolCallLoop() != null && hasAnyToolCalls(streamResult)) {
                        LOG.debug("Tool calls detected in streamed response (native: {}), entering tool-call loop",
                                streamResult.hasToolCalls());
                        ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                answer, streamResult.toolCalls(), messages, workspace, ctx);
                        answer = loopResult.finalAnswer();
                        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked",
                                loopResult.iterations(), loopResult.toolsInvoked());
                        appendSummary(out, loopResult);
                        // Post-tool answer acceptance gate: retry synthesis if deflected
                        answer = synthesisRetryIfNeeded(answer, loopResult.toolsInvoked(), messages, ctx);
                        answer = sanitizeAndTruncate(answer, opts);
                        out.append(answer);
                    } else {
                        // No tool calls — content was streamed; record full text for memory
                        streamed = true;
                        out.append(answer);
                    }
                } else {
                    out.append("(no answer)");
                }
            } else {
                // ── Non-streaming fallback (tests, non-interactive) ─────────
                // Use chatFull() so native tool calls are captured too
                // (chat() returns only String, losing native tool calls).
                CompletableFuture<LlmClient.StreamResult> fut = CompletableFuture.supplyAsync(
                        () -> ctx.llm().chatFull(messages));
                LlmClient.StreamResult streamResult = fut.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS);
                String answer = streamResult.text();
                if (answer != null) {
                    if (ctx.toolCallLoop() != null && hasAnyToolCalls(streamResult)) {
                        LOG.debug("Tool calls detected in LLM response (native: {}), entering tool-call loop",
                                streamResult.hasToolCalls());
                        ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                answer, streamResult.toolCalls(), messages, workspace, ctx);
                        answer = loopResult.finalAnswer();
                        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked",
                                loopResult.iterations(), loopResult.toolsInvoked());
                        appendSummary(out, loopResult);
                        // Post-tool answer acceptance gate: retry synthesis if deflected
                        answer = synthesisRetryIfNeeded(answer, loopResult.toolsInvoked(), messages, ctx);
                    }
                    answer = sanitizeAndTruncate(answer, opts);
                    out.append(answer);
                } else {
                    out.append("(no answer)");
                }
            }
        } catch (java.util.concurrent.TimeoutException te) {
            out.append("\n[Timeout: LLM response took too long]\n");
        } catch (EngineException.ConnectionFailed cf) {
            LOG.warn("Ollama not reachable: {}", cf.getMessage());
            out.append("\n[Ollama not reachable — ").append(cf.guidance()).append("]\n");
        } catch (EngineException.ModelNotFound mnf) {
            LOG.warn("Model not found: {}", mnf.model());
            out.append("\n[Model '").append(mnf.model()).append("' not found. ")
               .append(mnf.guidance()).append("]\n");
        } catch (EngineException.Transient tr) {
            LOG.warn("Transient engine error: {}", tr.getMessage());
            out.append("\n[").append(tr.guidance()).append("]\n");
        } catch (EngineException ee) {
            LOG.warn("Engine error: {}", ee.getMessage());
            out.append("\n[Engine error: ").append(ee.getMessage()).append("]\n");
        } catch (Exception e) {
            String detail = e.getMessage();
            LOG.warn("LLM call failed: {}", detail);
            out.append("\n[Error during LLM call")
               .append(detail != null && !detail.isBlank() ? ": " + detail : "")
               .append("]\n");
        }

        return new TurnOutput(out.toString(), streamed);
    }

    /** Apply mode-specific sanitization then truncate if over budget. */
    private static String sanitizeAndTruncate(String answer, Options opts) {
        answer = opts.answerSanitizer.apply(answer);
        if (answer.length() > opts.responseMaxChars) {
            answer = answer.substring(0, (int) opts.responseMaxChars) + "\n\n[output truncated]";
        }
        return answer;
    }

    /** Append tool-use summary if present. */
    private static void appendSummary(StringBuilder out, ToolCallLoop.LoopResult loopResult) {
        String summary = loopResult.summary();
        if (summary != null) {
            out.append(summary).append("\n\n");
        }
    }

    // ── Post-tool answer acceptance gate ─────────────────────────────────

    /** Phrases that indicate the model deflected instead of answering. */
    private static final Set<String> DEFLECTION_MARKERS = Set.of(
            "how can i help",
            "what would you like",
            "what do you want me to",
            "let me know if you",
            "is there anything",
            "would you like me to",
            "what can i do for you",
            "feel free to ask"
    );

    /**
     * Detect if the model's answer is a deflection (generic assistant boilerplate)
     * instead of a substantive response to the user's question.
     *
     * <p>Heuristic: the answer is short (under 500 chars) and contains
     * at least one deflection marker phrase. Longer answers that happen to
     * include a polite closing are not flagged.
     */
    static boolean isDeflection(String answer) {
        if (answer == null || answer.isBlank()) return true;
        if (answer.length() > 500) return false; // substantive answers get a pass
        String lower = answer.toLowerCase();
        for (String marker : DEFLECTION_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Post-tool synthesis retry: if tools were used and the answer is a deflection,
     * re-prompt the LLM exactly once with an instruction to answer using the evidence.
     *
     * @return the improved answer, or the original if retry was not needed or failed
     */
    private static String synthesisRetryIfNeeded(String answer, int toolsInvoked,
                                                  List<ChatMessage> messages, Context ctx) {
        if (toolsInvoked <= 0) return answer;
        if (!isDeflection(answer)) return answer;

        LOG.info("Post-tool deflection detected ({} tools used). Attempting synthesis retry.", toolsInvoked);

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(
                "You already gathered the needed evidence using tools. "
                + "Now answer the original question directly and concretely, "
                + "using the tool results you received. "
                + "Do not ask what I want — answer the question."));

        try {
            LlmClient.StreamResult retry = ctx.llm().chatFull(messages);
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !isDeflection(retryText)) {
                LOG.info("Synthesis retry produced substantive answer ({} chars)", retryText.length());
                return retryText;
            }
            LOG.warn("Synthesis retry still deflected. Returning original answer.");
        } catch (Exception e) {
            LOG.warn("Synthesis retry failed: {}", e.getMessage());
        }
        return answer;
    }
}

