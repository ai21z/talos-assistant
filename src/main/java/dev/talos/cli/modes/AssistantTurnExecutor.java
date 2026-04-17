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
                        // Claim-vs-action truth layer: annotate if the answer claims a mutation
                        // that no mutating tool actually performed this turn.
                        answer = annotateIfFalseMutationClaim(answer, loopResult);
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
                        // Claim-vs-action truth layer: annotate if the answer claims a mutation
                        // that no mutating tool actually performed this turn.
                        answer = annotateIfFalseMutationClaim(answer, loopResult);
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

    /** Short phrases that indicate the model deflected instead of answering. */
    private static final Set<String> DEFLECTION_MARKERS = Set.of(
            "how can i help",
            "how can i assist",
            "what would you like",
            "what do you want me to",
            "let me know if you",
            "is there anything",
            "would you like me to",
            "what can i do for you",
            "feel free to ask"
    );

    /**
     * Phrases that indicate a capability-recitation non-answer (generic assistant
     * meta-talk about what the assistant can do, instead of answering the question).
     */
    private static final Set<String> CAPABILITY_MARKERS = Set.of(
            "here is what i can do",
            "here's what i can do",
            "i can help you with",
            "i am able to",
            "i'm able to",
            "my capabilities include",
            "i have the following capabilities",
            "i can perform the following",
            "i can do the following"
    );

    /**
     * Detect if the model's answer is a deflection (generic assistant boilerplate)
     * instead of a substantive response to the user's question.
     *
     * <p>Two-tier heuristic:
     * <ol>
     *   <li><b>Short deflection</b> (≤ 500 chars): any {@link #DEFLECTION_MARKERS} match.</li>
     *   <li><b>Capability-recitation</b> (≤ 1500 chars): answer contains a
     *       {@link #CAPABILITY_MARKERS} phrase AND ends with a deflection marker.
     *       This catches the longer "here's what I can do… How can I help?" pattern
     *       without flagging genuinely substantive answers that happen to mention a capability.</li>
     * </ol>
     *
     * <p>Answers over 1500 chars always pass — they are long enough to be substantive.
     */
    static boolean isDeflection(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String lower = answer.toLowerCase();

        // Tier 1: short boilerplate deflection
        if (answer.length() <= 500) {
            for (String marker : DEFLECTION_MARKERS) {
                if (lower.contains(marker)) return true;
            }
            return false;
        }

        // Tier 2: medium-length capability-recitation non-answer
        if (answer.length() <= 1500) {
            boolean hasCapability = false;
            for (String cm : CAPABILITY_MARKERS) {
                if (lower.contains(cm)) { hasCapability = true; break; }
            }
            if (hasCapability) {
                // Must also end with a deflection marker (last 200 chars)
                String tail = lower.substring(Math.max(0, lower.length() - 200));
                for (String dm : DEFLECTION_MARKERS) {
                    if (tail.contains(dm)) return true;
                }
            }
        }

        return false; // long enough or no pattern match — substantive
    }

    /**
     * Post-tool synthesis retry: if tools were used and the answer is a deflection,
     * re-prompt the LLM exactly once with an instruction to answer using the evidence.
     *
     * <p>Package-private for testability.
     *
     * @return the improved answer, or the original if retry was not needed or failed
     */
    static String synthesisRetryIfNeeded(String answer, int toolsInvoked,
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

    // ── Claim-vs-action truth layer ──────────────────────────────────────

    /**
     * Phrases that strongly indicate the answer is claiming a file mutation
     * was performed. Kept narrow on purpose: match confident past-tense /
     * perfect-tense claims, not future-tense intent or questions.
     *
     * <p>Design: each phrase is unambiguous about an applied change having
     * happened. We avoid bare verbs (e.g. "updated") because they routinely
     * appear in grounded discussions of file contents ("the label is
     * updated to…"). We only flag phrasings a model uses when asserting
     * <em>it just did something</em>.
     */
    private static final Set<String> MUTATION_CLAIM_MARKERS = Set.of(
            "i have updated",   "i've updated",   "i updated",
            "i have edited",    "i've edited",    "i edited",
            "i have changed",   "i've changed",   "i changed",
            "i have applied",   "i've applied",   "i applied",
            "i have written",   "i've written",   "i wrote",
            "i have created",   "i've created",   "i created",
            "i have modified",  "i've modified",  "i modified",
            "i have saved",     "i've saved",     "i saved",
            "i have replaced",  "i've replaced",  "i replaced",
            "changes have been applied",
            "changes were applied",
            "the file has been updated",
            "the file has been modified",
            "the file has been edited",
            "the file has been saved",
            "the file has been written",
            "the changes have been saved",
            "has been updated to",
            "has been modified to"
    );

    /**
     * Prefix prepended to answers that claim a mutation when no mutating
     * tool succeeded in the turn. Kept short, unambiguous, and separable
     * from the model's own prose so the annotation is visually obvious.
     */
    static final String FALSE_MUTATION_ANNOTATION =
            "⚠ [Truth check: the response below claims a file was changed, "
            + "but no file-mutating tool succeeded in this turn. "
            + "No file on disk was actually modified.]\n\n";

    /**
     * Returns {@code true} if the answer contains language that strongly
     * asserts a file mutation was performed (applied, edited, written,
     * created, etc.).
     *
     * <p>Package-private for direct testing.
     */
    static boolean containsMutationClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase();
        for (String marker : MUTATION_CLAIM_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Claim-vs-action audit (annotate-first). If the answer asserts that a
     * file change was performed but no mutating tool call (write_file /
     * edit_file) succeeded this turn, prepend a short truth-check notice.
     *
     * <p>The invariant this enforces: <em>a mutation claim in the answer
     * must correspond to at least one successful mutating tool call in
     * the same turn.</em>
     *
     * <p>Annotate-first posture (see §9 R2 of the main harness plan):
     * we do not rewrite or strip the model's text. We only add a visible
     * signal so the user can see the mismatch. Preserves transparent
     * transcripts and avoids silent rewrites.
     *
     * <p>Package-private for direct testing.
     *
     * @param answer     the answer text after any synthesis retry
     * @param loopResult the tool-loop result for the current turn
     * @return the (possibly annotated) answer
     */
    static String annotateIfFalseMutationClaim(String answer, ToolCallLoop.LoopResult loopResult) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer; // a real mutation backs the claim
        if (!containsMutationClaim(answer)) return answer;

        LOG.warn("False mutation claim detected: answer asserts a file change, "
                + "but no mutating tool succeeded this turn. Annotating.");
        return FALSE_MUTATION_ANNOTATION + answer;
    }
}

