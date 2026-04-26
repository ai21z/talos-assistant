package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
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
 *
 * <p><b>Public API scope (since N4):</b> the class, {@link TurnOutput},
 * {@link Options}, and {@link #execute} are public so the harness
 * ({@code ExecutorScenarioRunner}) can drive a full turn end-to-end with
 * a scripted {@link dev.talos.core.llm.LlmClient}. The package-private
 * helpers (gate predicates, annotators) remain test-only.
 */
public final class AssistantTurnExecutor {

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
    public record TurnOutput(String text, boolean streamed) {}

    /**
     * Execution options that vary between modes.
     */
    public static final class Options {
        private long llmTimeoutMs = 300_000L;
        private long responseMaxChars = 10 * 1024 * 1024L;
        private UnaryOperator<String> answerSanitizer = UnaryOperator.identity();

        public Options llmTimeoutMs(long ms)         { this.llmTimeoutMs = ms; return this; }
        public Options responseMaxChars(long chars)   { this.responseMaxChars = chars; return this; }

        /**
         * Optional post-processing for the raw LLM answer (e.g., RAG preamble stripping).
         * Applied before truncation. AskMode passes identity; RagMode passes sanitizers.
         */
        public Options answerSanitizer(UnaryOperator<String> fn) {
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
    public static TurnOutput execute(List<ChatMessage> messages, Path workspace,
                              Context ctx, Options opts) {
        StringBuilder out = new StringBuilder();
        boolean streamed = false;
        TaskContract taskContract = TaskContractResolver.fromMessages(messages);
        initializeExecutionPhaseForTurn(taskContract, ctx);
        ctx = withNativeToolSurface(ctx, taskContract);
        injectTaskContractInstruction(messages);
        Context turnContext = ctx;

        try {
            if (ctx.streamSink() != null) {
                // ── Streaming path ──────────────────────────────────────────
                LlmClient.StreamResult streamResult = chatStreamFull(ctx, messages);
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
                        ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                answer, messages, loopResult, workspace, ctx, opts);
                        appendExtraSummary(out, resolution.extraSummary());
                        out.append(resolution.answer());
                    } else {
                        // No tool calls — content was streamed; record full text for memory.
                        // Streaming no-tool branch. We cannot silently retry here
                        // because prose is already on the terminal, so truthfulness
                        // must be enforced by visible annotation of high-risk shapes.
                        streamed = true;
                        answer = shapeAnswerWithoutTools(answer, messages, ctx, true, opts);
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
                        () -> chatFull(turnContext, messages));
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
                        ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                answer, messages, loopResult, workspace, ctx, opts);
                        appendExtraSummary(out, resolution.extraSummary());
                        answer = resolution.answer();
                    } else {
                        // No-tool-call path. Zero tools were invoked this turn.
                        // Grounding retry gate: if the user explicitly asked for evidence
                        // / reading / inspection and the answer is long-and-confident,
                        // re-prompt once asking the model to answer from workspace evidence.
                        answer = shapeAnswerWithoutTools(answer, messages, ctx, false, opts);
                    }
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

    record ToolLoopAnswerResolution(String answer, String extraSummary) {}

    private static ToolLoopAnswerResolution resolveToolLoopAnswer(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        answer = synthesisRetryIfNeeded(answer, loopResult.toolsInvoked(), messages, ctx);

        MutationRetryResult mrr = mutationRequestRetryIfNeeded(
                answer, messages, loopResult, workspace, ctx);
        answer = mrr.answer();

        InspectRetryResult irr = inspectCompletenessRetryIfNeeded(
                answer, messages, loopResult, workspace, ctx);
        answer = irr.answer();

        moveToVerifyAfterSuccessfulMutation(ctx, loopResult, mrr.mutationsInRetry());

        String finalAnswer = shapeAnswerAfterToolLoop(
                answer, messages, loopResult, workspace, mrr.mutationsInRetry(), opts);

        return new ToolLoopAnswerResolution(
                finalAnswer,
                joinExtraSummaries(mrr.extraSummary(), irr.extraSummary())
        );
    }

    private static void appendExtraSummary(StringBuilder out, String extraSummary) {
        if (extraSummary != null) out.append(extraSummary).append("\n\n");
    }

    private static String joinExtraSummaries(String first, String second) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) return null;
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private static void initializeExecutionPhaseForTurn(TaskContract contract, Context ctx) {
        if (ctx == null || ctx.executionPhaseState() == null) return;
        ExecutionPhase initial = contract != null && contract.mutationAllowed()
                ? ExecutionPhase.APPLY
                : ExecutionPhase.INSPECT;
        ctx.executionPhaseState().moveTo(initial);
    }

    private static Context withNativeToolSurface(Context ctx, TaskContract contract) {
        if (ctx == null || ctx.hasNativeToolSpecOverride()) return ctx;
        ExecutionPhase phase = ctx.executionPhaseState() == null
                ? ExecutionPhase.APPLY
                : ctx.executionPhaseState().phase();
        return ctx.withNativeToolSpecs(
                NativeToolSpecPolicy.select(contract, phase, ctx.toolRegistry()));
    }

    private static LlmClient.StreamResult chatStreamFull(Context ctx, List<ChatMessage> messages) {
        return ctx.llm().chatStreamFull(messages, ctx.streamSink(), ctx.nativeToolSpecs());
    }

    private static LlmClient.StreamResult chatFull(Context ctx, List<ChatMessage> messages) {
        return ctx.llm().chatFull(messages, ctx.nativeToolSpecs());
    }

    static void injectTaskContractInstruction(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        if (messages.stream().anyMatch(AssistantTurnExecutor::isTaskContractInstruction)) return;

        TaskContract contract = TaskContractResolver.fromMessages(messages);
        if (contract.mutationAllowed()) return;

        String instruction = """
                [TaskContract]
                type: %s
                mutationAllowed: false
                This turn is read-only or diagnostic. Do not call talos.write_file or talos.edit_file.
                Use talos.list_dir, talos.read_file, talos.grep, or talos.retrieve as needed to inspect.
                If you identify a possible fix, describe it and wait for an explicit change request before editing.""".formatted(contract.type());

        int insertAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).role())) {
                insertAt = i + 1;
                break;
            }
        }
        messages.add(insertAt, ChatMessage.system(instruction));
    }

    private static boolean isTaskContractInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[TaskContract]");
    }

    private static void moveToVerifyAfterSuccessfulMutation(
            Context ctx, ToolCallLoop.LoopResult loopResult, int extraMutationSuccesses) {
        if (ctx == null || ctx.executionPhaseState() == null || loopResult == null) return;
        int totalMutations = loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses);
        if (totalMutations > 0) {
            ctx.executionPhaseState().moveTo(ExecutionPhase.VERIFY);
        }
    }

    private static String shapeAnswerAfterToolLoop(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses,
            Options opts
    ) {
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, messages, loopResult, workspace, extraMutationSuccesses);
        return sanitizeAndTruncate(outcome.finalAnswer(), opts);
    }

    private static String shapeAnswerWithoutTools(
            String answer,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed,
            Options opts
    ) {
        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(answer, messages, ctx, streamed);
        if (streamed && outcome.groundingStatus() == ExecutionOutcome.GroundingStatus.UNGROUNDED) {
            LOG.info("Streaming grounding annotation appended: answer={} chars, "
                    + "zero tools, user asked for evidence.", answer == null ? 0 : answer.length());
        }
        if (streamed && outcome.noToolMutationReplaced()) {
            LOG.info("Streaming no-tool mutation narrative replaced: explicit mutation request, "
                    + "zero file tools, no file changed.");
        }
        return sanitizeAndTruncate(outcome.finalAnswer(), opts);
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

        // Anchor the retry to the verbatim original user request.
        //
        // Rationale (real transcript, Turn 2 / Turn 6 failure shape): the
        // previous generic retry prompt ("answer the original question
        // directly") caused the local 8B model to respond "the original
        // question is not visible in our current conversation history"
        // because, after tool_call + tool_result messages are appended,
        // the user's request is several turns back and the model fails
        // to re-anchor on it. On the native tool-call path, tool results
        // are role="tool" so {@link #latestUserRequest} correctly returns
        // the original request, not a tool-result message.
        String originalRequest = latestUserRequest(messages);

        String retryPrompt;
        if (originalRequest != null && !originalRequest.isBlank()) {
            // Trim if very long so the retry prompt itself doesn't balloon context.
            String pinned = originalRequest.length() <= 2000
                    ? originalRequest
                    : originalRequest.substring(0, 2000) + "…";
            retryPrompt = "The user's original request was:\n\n«" + pinned + "»\n\n"
                    + "You already gathered the needed evidence using tools. "
                    + "Now answer that exact request directly and concretely, "
                    + "using the tool results you received. "
                    + "Do not say the question is missing. "
                    + "Do not ask what I want — answer the question above.";
        } else {
            // Fallback (should be rare): no user-role message found. Keep the
            // previous wording so pre-anchor tests and callers still hit the
            // "already gathered the needed evidence" sentinel phrase.
            retryPrompt = "You already gathered the needed evidence using tools. "
                    + "Now answer the original question directly and concretely, "
                    + "using the tool results you received. "
                    + "Do not ask what I want — answer the question.";
        }

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(retryPrompt));

        try {
            LlmClient.StreamResult retry = chatFull(ctx, messages);
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
    public static final String FALSE_MUTATION_ANNOTATION =
            "[Truth check: the response below claims a file was changed, "
            + "but no file-mutating tool succeeded in this turn. "
            + "No file on disk was actually modified.]\n\n";

    public static final String PARTIAL_MUTATION_ANNOTATION =
            "[Truth check: some requested file changes succeeded and some failed. "
            + "Verified outcomes for this turn are listed below.]\n\n";

    public static final String DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because the requested "
            + "write was not approved.]\n\n";

    public static final String INVALID_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because the requested "
            + "write tool call was invalid.]\n\n";

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
        return annotateIfFalseMutationClaim(answer, loopResult, 0);
    }

    /**
     * Variant that also accounts for mutations performed during a Point-3
     * missing-mutation retry (which executes its own tool loop).
     */
    static String annotateIfFalseMutationClaim(String answer,
                                               ToolCallLoop.LoopResult loopResult,
                                               int extraMutationSuccesses) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null) return answer;
        int totalMutations = loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses);
        if (totalMutations > 0) return answer; // a real mutation backs the claim
        if (hasDeniedMutation(loopResult)) return answer;
        if (!containsMutationClaim(answer)) return answer;

        LOG.warn("False mutation claim detected: answer asserts a file change, "
                + "but no mutating tool succeeded this turn. Annotating.");
        return FALSE_MUTATION_ANNOTATION + answer;
    }

    static String summarizePartialMutationOutcomesIfNeeded(String answer,
                                                           ToolCallLoop.LoopResult loopResult,
                                                           int extraMutationSuccesses) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;

        List<ToolCallLoop.ToolOutcome> mutating = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .toList();
        if (mutating.isEmpty()) return answer;

        List<ToolCallLoop.ToolOutcome> successes = mutating.stream()
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        List<ToolCallLoop.ToolOutcome> failures = mutating.stream()
                .filter(o -> !o.success())
                .toList();
        if (successes.isEmpty() || failures.isEmpty()) return answer;

        StringBuilder out = new StringBuilder(PARTIAL_MUTATION_ANNOTATION);
        out.append("Succeeded:\n");
        for (ToolCallLoop.ToolOutcome outcome : successes) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(outcome.summary().isBlank() ? "mutation applied" : outcome.summary())
                    .append('\n');
        }
        out.append("Failed:\n");
        for (ToolCallLoop.ToolOutcome outcome : failures) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(trimFailureMessage(outcome.errorMessage()))
                    .append('\n');
        }
        out.append("\nThe assistant summary was replaced with this verified mutation outcome because the turn had partial success.");
        return out.toString().stripTrailing();
    }

    private static String trimFailureMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "mutation failed";
        String msg = errorMessage.strip();
        int newline = msg.indexOf('\n');
        if (newline > 0) msg = msg.substring(0, newline).strip();
        if (msg.length() > 180) msg = msg.substring(0, 177) + "…";
        return msg;
    }

    static String summarizeDeniedMutationOutcomesIfNeeded(String answer,
                                                          List<ChatMessage> messages,
                                                          ToolCallLoop.LoopResult loopResult,
                                                          int extraMutationSuccesses) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (!looksLikeMutationRequest(latestUserRequest(messages))) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;
        List<ToolCallLoop.ToolOutcome> deniedMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::denied)
                .toList();
        if (deniedMutations.isEmpty()) return answer;

        StringBuilder out = new StringBuilder(DENIED_MUTATION_ANNOTATION);
        out.append("No file changes were applied because approval was denied for:\n");
        for (ToolCallLoop.ToolOutcome outcome : deniedMutations) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": approval denied\n");
        }
        List<ToolCallLoop.ToolOutcome> invalidMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .filter(outcome -> !outcome.denied())
                .filter(outcome -> ToolError.INVALID_PARAMS.equals(outcome.errorCode()))
                .toList();
        if (!invalidMutations.isEmpty()) {
            out.append("\nEarlier invalid mutation attempts in this turn were also rejected before approval:\n");
            for (ToolCallLoop.ToolOutcome outcome : invalidMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(trimFailureMessage(outcome.errorMessage()))
                        .append('\n');
            }
        }
        out.append("\nTalos can still help in a later turn if you want to retry the edit or take a read-only approach.");
        return out.toString().stripTrailing();
    }

    static String summarizeInvalidMutationOutcomesIfNeeded(String answer,
                                                           List<ChatMessage> messages,
                                                           ToolCallLoop.LoopResult loopResult,
                                                           int extraMutationSuccesses) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (!looksLikeMutationRequest(latestUserRequest(messages))) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;
        if (hasDeniedMutation(loopResult)) return answer;
        List<ToolCallLoop.ToolOutcome> invalidMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .filter(outcome -> !outcome.denied())
                .filter(outcome -> ToolError.INVALID_PARAMS.equals(outcome.errorCode()))
                .toList();
        if (invalidMutations.isEmpty()) return answer;

        StringBuilder out = new StringBuilder(INVALID_MUTATION_ANNOTATION);
        out.append("No file changes were applied because Talos proposed invalid mutation arguments:\n");
        for (ToolCallLoop.ToolOutcome outcome : invalidMutations) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(trimFailureMessage(outcome.errorMessage()))
                    .append('\n');
        }
        out.append("\nTalos needs to inspect the current file content and retry with exact, valid tool arguments before any edit can be applied.");
        return out.toString().stripTrailing();
    }

    // ── Point 3 — Missing-mutation retry ─────────────────────────────────

    /**
     * Phrases in the <em>user request</em> that indicate an explicit file
     * mutation intent. Matched case-insensitively against the latest user
     * message. Deliberately narrow: we only want to fire this retry when
     * the user's language is unambiguous about wanting a change applied.
     */
    /** Result of the missing-mutation retry gate. */
    record MutationRetryResult(String answer, int mutationsInRetry, String extraSummary) {}

    /**
     * True iff the latest user request contains an unambiguous mutation
     * verb. Package-private for direct testing.
     */
    static boolean looksLikeMutationRequest(String userRequest) {
        return TaskContractResolver.fromUserRequest(userRequest).mutationRequested();
    }

    /**
     * Missing-mutation retry (Point 3).
     *
     * <p>Fires when <b>all</b> hold:
     * <ol>
     *   <li>The tool loop already ran and performed zero mutating tool
     *       successes this turn.</li>
     *   <li>The latest user request contains a mutation verb (see
     *       {@link #MUTATION_REQUEST_MARKERS}).</li>
     *   <li>A tool loop is configured (so the retry's follow-up tool
     *       calls can actually execute).</li>
     * </ol>
     *
     * <p>On fire, appends a short, unambiguous instruction to the
     * messages telling the model to call {@code talos.write_file} or
     * {@code talos.edit_file} now, or explicitly state why it cannot.
     * If the retry response carries tool calls, the tool loop is
     * re-invoked so those calls actually run. Any mutations performed
     * during the retry are surfaced to the caller via
     * {@link MutationRetryResult#mutationsInRetry()}.
     *
     * <p>This is the symmetric counterpart to
     * {@link #annotateIfFalseMutationClaim}: that gate catches "claimed
     * but didn't do it"; this gate catches "was told to do it, never
     * tried". Together they enforce the invariant that mutation intent
     * and mutation action stay in sync.
     */
    static MutationRetryResult mutationRequestRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        if (answer == null) answer = "";
        if (loopResult == null) return new MutationRetryResult(answer, 0, null);
        if (loopResult.mutatingToolSuccesses() > 0) return new MutationRetryResult(answer, 0, null);
        if (ctx == null || ctx.llm() == null) return new MutationRetryResult(answer, 0, null);
        if (ctx.toolCallLoop() == null) return new MutationRetryResult(answer, 0, null);
        if (hasDeniedMutation(loopResult)) return new MutationRetryResult(answer, 0, null);
        if (loopResult.failureDecision().shouldStop()) return new MutationRetryResult(answer, 0, null);
        if (hasInvalidMutatingFailure(loopResult)) return new MutationRetryResult(answer, 0, null);

        String userRequest = latestUserRequest(messages);
        if (!looksLikeMutationRequest(userRequest)) return new MutationRetryResult(answer, 0, null);

        LOG.info("Missing-mutation retry fired: user asked for a change but 0 mutating "
                + "tool calls succeeded. Re-prompting with an explicit write nudge.");

        messages.add(ChatMessage.assistant(answer.isBlank() ? "(no answer)" : answer));
        messages.add(ChatMessage.user(
                "You were asked to modify a file but you did not call talos.write_file "
                + "or talos.edit_file in this turn. The user's request was:\n\n«"
                + (userRequest == null ? "" :
                        (userRequest.length() <= 1000 ? userRequest
                                : userRequest.substring(0, 1000) + "…"))
                + "»\n\n"
                + "Call the appropriate write/edit tool NOW to perform the change. "
                + "If you truly cannot (e.g., you do not know which file, or the "
                + "content is impossible to produce), state exactly which file and why "
                + "in one sentence. Do not ask further questions — act."));

        try {
            LlmClient.StreamResult retry = chatFull(ctx, messages);
            String retryText = retry.text() == null ? "" : retry.text();

            if (retry.hasToolCalls() || ToolCallParser.containsToolCalls(retryText)) {
                // Re-enter the tool loop so the mutating call actually executes.
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), messages, workspace, ctx);
                String mergedAnswer = retryLoop.finalAnswer();
                String summary = retryLoop.summary();
                if (hasDeniedMutation(retryLoop)) {
                    mergedAnswer = summarizeDeniedMutationOutcomesIfNeeded(
                            mergedAnswer, messages, retryLoop, 0);
                }
                if (retryLoop.mutatingToolSuccesses() > 0) {
                    LOG.info("Missing-mutation retry succeeded: {} mutation(s) performed.",
                            retryLoop.mutatingToolSuccesses());
                }
                return new MutationRetryResult(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop.mutatingToolSuccesses(),
                        summary);
            }

            // No tool calls on the retry — the model declined. Keep the retry
            // text if it's non-blank (model explained why it can't), otherwise
            // fall back to the original answer.
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                String stripped = ToolCallParser.stripToolCalls(retryText);
                return new MutationRetryResult(stripped.isBlank() ? answer : stripped, 0, null);
            }
        } catch (Exception e) {
            LOG.warn("Missing-mutation retry failed: {}", e.getMessage());
        }
        return new MutationRetryResult(answer, 0, null);
    }

    private static boolean hasInvalidMutatingFailure(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating()
                        && !outcome.success()
                        && !outcome.denied()
                        && ToolError.INVALID_PARAMS.equals(outcome.errorCode()));
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }

    record InspectRetryResult(String answer, String extraSummary) {}

    private static final Set<String> SELECTOR_MISMATCH_MARKERS = Set.of(
            "mismatches between html classes/ids and the selectors used in css or javascript",
            "mismatches between html classes/ids",
            "selectors used in css or javascript",
            "html classes/ids",
            "selector mismatch",
            "selectors used in css",
            "selectors used in javascript"
    );

    // ── Inspect under-completion truth layer (N3 / P4) ───────────────────

    /**
     * Minimum answer length at which the inspect under-completion gate
     * becomes eligible.
     *
     * <p>Lower than {@link #UNGROUNDED_MIN_CHARS} because N3 fires on the
     * with-tools branch, where the answer has already passed through the
     * deflection / synthesis-retry tiers. A substantive answer after ≤ 1
     * read is the exact Turn-1 failure shape regardless of length above
     * this threshold.
     */
    static final int INSPECT_MIN_CHARS = 500;

    /**
     * Phrases in the <em>user request</em> that strongly imply the user
     * asked for multi-file inspection before answering — i.e., explicitly
     * more than one file should be read. Deliberately narrower than
     * {@link #EVIDENCE_REQUEST_MARKERS}: an evidence request is a
     * superset; an inspect-first request is the subset that names or
     * implies plurality.
     *
     * <p>Matched case-insensitively against the latest user message only.
     * Anchored to real transcript Turn-1 wording ("Read the relevant
     * files first", "identify the main HTML entry file, the main
     * stylesheet file, and the main JavaScript file").
     */
    private static final Set<String> INSPECT_REQUEST_MARKERS = Set.of(
            "entry file",
            "entry files",
            "read the relevant",
            "read the main",
            "read the files",
            "read all the",
            "read all ",
            "read each",
            "read them all",
            "read both",
            "read these",
            "all three",
            "look at each",
            "look at all",
            "inspect each",
            "inspect all",
            "open each",
            "start by reading",
            "first read",
            "first, read"
    );

    /**
     * Annotation prepended to the answer when the turn completed with
     * a substantive answer but only one read-only tool call, despite the
     * user asking for multi-file inspection.
     */
    public static final String UNDER_INSPECTION_ANNOTATION =
            "[Inspect check: the user asked for multiple files to be read "
            + "before answering, but only one read-only tool call was made "
            + "this turn. The response below may not reflect the full "
            + "workspace contents.]\n\n";

    /**
     * True iff the latest user request contains an inspect-first marker
     * indicating plural-file inspection (see
     * {@link #INSPECT_REQUEST_MARKERS}). Package-private for direct
     * testing.
     */
    static boolean looksLikeInspectFirstRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase();
        for (String marker : INSPECT_REQUEST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Counts successful-or-attempted read-only tool invocations in
     * {@code loopResult.toolNames()}. Read-only tools are {@code read_file},
     * {@code list_dir}, and {@code grep}; the {@code talos.} namespace
     * prefix is stripped before comparison. Package-private for direct
     * testing.
     *
     * <p>Using {@code toolNames()} (the total invocation list) rather
     * than filtering for success is intentional: the gate fires on
     * <em>under-inspection intent</em>, and even a failed read is a
     * sign the model did try to inspect. The residual false-positive
     * risk (counting a failed read as "one read done") is acceptable
     * because the gate is annotate-only.
     */
    static int readOnlyToolCount(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolNames() == null) return 0;
        int n = 0;
        for (String t : loopResult.toolNames()) {
            if (t == null) continue;
            String name = t.toLowerCase();
            if (name.startsWith("talos.")) name = name.substring("talos.".length());
            if (name.equals("read_file") || name.equals("list_dir") || name.equals("grep")) {
                n++;
            }
        }
        return n;
    }

    static List<String> obviousPrimaryFiles(Path workspace) {
        return StaticTaskVerifier.obviousPrimaryFiles(workspace);
    }

    static List<String> missingPrimaryReads(Path workspace, ToolCallLoop.LoopResult loopResult) {
        return loopResult == null
                ? List.of()
                : StaticTaskVerifier.missingPrimaryReads(workspace, loopResult.readPaths());
    }

    static InspectRetryResult inspectCompletenessRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        if (answer == null) answer = "";
        if (loopResult == null || ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null) {
            return new InspectRetryResult(answer, null);
        }
        if (!looksLikeInspectFirstRequest(latestUserRequest(messages))) {
            return new InspectRetryResult(answer, null);
        }
        List<String> missing = missingPrimaryReads(workspace, loopResult);
        if (missing.isEmpty()) return new InspectRetryResult(answer, null);
        if (loopResult.mutatingToolSuccesses() > 0) return new InspectRetryResult(answer, null);
        if (answer.isBlank()) return new InspectRetryResult(answer, null);

        LOG.info("Inspect-completeness retry fired: tiny workspace, inspect-first request, "
                + "missing reads for {}", missing);

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(
                "You started diagnosing the workspace before reading all of the obvious primary files. "
                        + "Read these files now before answering: "
                        + String.join(", ", missing)
                        + ". After reading them, answer concretely from the file contents. "
                        + "Do not speculate about files that do not exist."));
        try {
            LlmClient.StreamResult retry = chatFull(ctx, messages);
            String retryText = retry.text() == null ? "" : retry.text();
            if (retry.hasToolCalls()) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), messages, workspace, ctx);
                String mergedAnswer = retryLoop.finalAnswer();
                return new InspectRetryResult(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop.summary());
            }
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                return new InspectRetryResult(retryText, null);
            }
        } catch (Exception e) {
            LOG.warn("Inspect-completeness retry failed: {}", e.getMessage());
        }
        return new InspectRetryResult(answer, null);
    }

    static String overrideSelectorMismatchAnalysisIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        String userRequest = latestUserRequest(messages);
        if (!looksLikeSelectorMismatchRequest(userRequest)) return answer;

        String grounded = StaticTaskVerifier.renderSelectorInspection(workspace);
        return grounded == null || grounded.isBlank() ? answer : grounded;
    }

    static boolean looksLikeSelectorMismatchRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase();
        for (String marker : SELECTOR_MISMATCH_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return lower.contains("mismatch") && lower.contains("selector");
    }

    /**
     * Inspect under-completion truth layer (annotate-first).
     *
     * <p>Fires when <b>all</b> of the following hold:
     * <ol>
     *   <li>The tool loop ran and invoked at least one tool — if the turn
     *       invoked zero tools, {@link #groundingRetryIfNeeded} /
     *       {@link #shouldAppendStreamingGroundingAnnotation} (R6 / N2)
     *       is the correct gate, not this one.</li>
     *   <li>Zero mutating tool successes — a successful mutation means the
     *       model did substantive work and the under-inspection signal is
     *       noise.</li>
     *   <li>The answer is at least {@link #INSPECT_MIN_CHARS} characters —
     *       substantive enough to carry fabricated claims.</li>
     *   <li>{@link #readOnlyToolCount(ToolCallLoop.LoopResult)} ≤ 1 —
     *       the Turn-1 failure shape: one read, then a confident
     *       multi-file summary.</li>
     *   <li>The latest user request contains an inspect-first marker
     *       (see {@link #INSPECT_REQUEST_MARKERS}).</li>
     * </ol>
     *
     * <p><b>Posture: annotate, do not retry.</b> A retry here would
     * require re-running the tool loop (another LLM + tool cycle) which
     * is substantially more invasive than R6's single no-tool retry.
     * Annotation preserves the user-visible work the turn already did
     * (the successful read, the loop summary) and adds a visible truth
     * signal without rewriting the model's prose. This mirrors R2's
     * claim-vs-action annotate-first decision.
     *
     * <p><b>Streaming visibility limitation (inherited from R2):</b> on
     * the streaming-with-tools branch the final answer may already be
     * on the terminal by the time this gate runs, so the prepended
     * annotation enters {@code out} (history / memory) but may not
     * appear on the user's terminal. This matches the pre-existing
     * behavior of {@link #annotateIfFalseMutationClaim} and is a
     * deliberate single-shape decision — when real transcript evidence
     * justifies a separate streaming-visible variant, it can be added
     * symmetrically (mirroring the R6 → N2 split).
     *
     * <p>Package-private for direct testing.
     *
     * @param answer     the answer text after any synthesis retry / R2 annotation
     * @param messages   the full turn messages (latest user message inspected)
     * @param loopResult the tool-loop result for the current turn
     * @return the (possibly annotated) answer
     */
    static String annotateIfInspectUnderCompletion(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null) return answer;
        if (loopResult.toolsInvoked() == 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (answer.length() < INSPECT_MIN_CHARS) return answer;
        if (readOnlyToolCount(loopResult) > 1) return answer;
        if (!looksLikeInspectFirstRequest(latestUserRequest(messages))) return answer;

        LOG.warn("Inspect under-completion detected: answer={} chars, "
                + "read-only tool calls={}, tools invoked={}, "
                + "user asked for multi-file inspection. Annotating.",
                answer.length(), readOnlyToolCount(loopResult),
                loopResult.toolsInvoked());
        return UNDER_INSPECTION_ANNOTATION + answer;
    }

    // ── No-tool grounding retry (R6, scoped) ─────────────────────────────

    /**
     * Minimum answer length at which the grounding retry becomes eligible.
     *
     * <p>Chosen so that short simple answers are never second-guessed, while
     * the transcript's long-fabrication shapes (1600+ chars in Turns 2–4) are
     * comfortably inside the window. Values below 600 risk fighting the
     * short-deflection tier (≤ 500 chars) already handled elsewhere.
     */
    static final int UNGROUNDED_MIN_CHARS = 600;

    /**
     * Phrases in the <em>user request</em> that indicate the user wants the
     * answer grounded in inspected workspace contents. Kept conservative and
     * anchored to real transcript prompt wording — we explicitly do not want
     * a bag-of-words net that sweeps up generic conversation.
     *
     * <p>Matched case-insensitively against the latest user message only.
     */
    private static final Set<String> EVIDENCE_REQUEST_MARKERS = Set.of(
            "read the",
            "read first",
            "inspect",
            "check whether",
            "check if",
            "check that",
            "verify",
            "evidence",
            "actual file",
            "based on the file",
            "from the file",
            "wired together",
            "wiring",
            "mismatch",
            "suspicious reference",
            "broken reference",
            "identify the"
    );

    /**
     * Annotation prepended to the original answer if the grounding retry
     * fires but the retry itself does not produce a better result. Keeps the
     * user informed without silently rewriting.
     */
    public static final String UNGROUNDED_ANNOTATION =
            "[Grounding check: the user asked for an answer based on workspace "
            + "contents, but no files were read this turn. The response below was "
            + "produced without reading any files.]\n\n";

    public static final String STREAMING_NO_TOOL_MUTATION_ANNOTATION =
            "[Truth check: the response below narrates completed file changes, "
            + "but no file tool was called in this turn. Treat it as unverified.]\n\n";

    public static final String STREAMING_NO_TOOL_MUTATION_REPLACEMENT =
            "[Truth check: no file was changed in this turn. The user asked for a "
            + "modification, but the assistant did not call any file-editing tool, so "
            + "the prior \"updated file\" narrative was discarded.]\n\n"
            + "No file changes were applied. Please retry with actual tool-backed edits.";

    /**
     * Returns the content of the latest user-role message in {@code messages},
     * or {@code null} if none. Package-private for testability.
     */
    static String latestUserRequest(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role())) {
                String content = m.content();
                if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
                return (content == null || content.isBlank()) ? null : content;
            }
        }
        return null;
    }

    /**
     * True iff the given user request contains at least one evidence-request
     * phrase. Conservative: matches the latest user message only; never
     * inspects the assistant's own prior output. Package-private for testing.
     */
    static boolean looksLikeEvidenceRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase();
        for (String marker : EVIDENCE_REQUEST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /**
     * N2 — streaming-path grounding annotation predicate.
     *
     * <p>Pure detection helper, no side effects. Returns {@code true} iff the
     * streamed turn exhibits the R6 failure shape:
     * <ol>
     *   <li>the answer is non-blank and at least {@link #UNGROUNDED_MIN_CHARS}
     *       characters long;</li>
     *   <li>the latest user request contains an evidence-request marker;</li>
     *   <li>the caller invoked this helper on the no-tool-call streaming
     *       branch — zero-tools is a structural invariant of the call site,
     *       not re-checked here.</li>
     * </ol>
     *
     * <p>Streaming mode deliberately does <b>not</b> retry silently: the prose
     * is already on the terminal, and a retry would either double-render or
     * require ambitious buffering. Instead, callers append a trailing
     * grounding notice ({@link #UNGROUNDED_ANNOTATION}) to both the stream
     * sink (so the user sees it) and the turn output (so history records
     * it). This mirrors the R2 annotate-first posture: transparent
     * transcripts over invisible rewriting.
     *
     * <p>Package-private for direct testing.
     */
    static boolean shouldAppendStreamingGroundingAnnotation(
            String answer, List<ChatMessage> messages) {
        if (answer == null || answer.isBlank()) return false;
        if (answer.length() < UNGROUNDED_MIN_CHARS) return false;
        return looksLikeEvidenceRequest(latestUserRequest(messages));
    }

    static String annotateStreamingNoToolMutationClaim(String answer, List<ChatMessage> messages) {
        if (answer == null || answer.isBlank()) return answer;
        if (!looksLikeMutationRequest(latestUserRequest(messages))) return answer;
        if (!containsMutationClaim(answer) && !containsStreamingMutationNarrative(answer)) return answer;
        return STREAMING_NO_TOOL_MUTATION_ANNOTATION + answer;
    }

    private static final Set<String> STREAMING_MUTATION_NARRATIVE_MARKERS = Set.of(
            "updated `index.html`",
            "updated index.html",
            "updated `style.css`",
            "updated style.css",
            "updated `script.js`",
            "updated script.js",
            "here is the updated",
            "summary of changes",
            "summary of changes and verifications",
            "### updated `index.html`",
            "### updated `style.css`",
            "### updated `script.js`",
            "these changes should ensure",
            "these changes should align"
    );

    static boolean containsStreamingMutationNarrative(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase();
        for (String marker : STREAMING_MUTATION_NARRATIVE_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    static String enforceStreamingNoToolTruthfulness(String answer, List<ChatMessage> messages) {
        String out = answer;
        if (shouldReplaceStreamingNoToolMutationNarrative(answer, messages)) {
            return STREAMING_NO_TOOL_MUTATION_REPLACEMENT;
        }
        if (shouldAppendStreamingGroundingAnnotation(answer, messages)) {
            out = UNGROUNDED_ANNOTATION + answer;
        }
        out = annotateStreamingNoToolMutationClaim(out, messages);
        return out;
    }

    static boolean shouldReplaceStreamingNoToolMutationNarrative(
            String answer, List<ChatMessage> messages) {
        if (answer == null || answer.isBlank()) return false;
        if (!looksLikeMutationRequest(latestUserRequest(messages))) return false;
        return containsMutationClaim(answer) || containsStreamingMutationNarrative(answer);
    }

    /**
     * No-tool grounding retry (R6, scoped).
     *
     * <p>Fires when <b>all</b> of the following are true:
     * <ol>
     *   <li>The turn invoked zero tool calls (the caller only invokes this
     *       helper on the no-tool-call branch, so this is a structural
     *       invariant of the call site, not a runtime re-check).</li>
     *   <li>The answer is at least {@link #UNGROUNDED_MIN_CHARS} characters
     *       long — substantive enough that the existing deflection gate is
     *       not going to catch it.</li>
     *   <li>The latest user request in {@code messages} contains an
     *       evidence-request marker (see {@link #EVIDENCE_REQUEST_MARKERS}).</li>
     * </ol>
     *
     * <p>On fire, performs <b>exactly one</b> retry via
     * {@code ctx.llm().chatFull(...)} with a short corrective instruction
     * telling the model to answer from inspected workspace contents. If the
     * retry produces a non-blank, non-identical, longer-or-similar answer,
     * that answer is returned. Otherwise the original is annotated with
     * {@link #UNGROUNDED_ANNOTATION} and returned so the user at least sees a
     * visible grounding signal. Annotate-on-failure mirrors the R2
     * claim-vs-action posture.
     *
     * <p><b>Scope note (N1 — non-streaming only):</b> this helper performs a
     * silent retry, which is only safe on the non-streaming branch — the
     * streaming branch has already emitted prose to the terminal by the time
     * this helper could fire, so a retry would double-render. The streaming
     * counterpart is {@link #shouldAppendStreamingGroundingAnnotation}, which
     * is detect-only and never retries.
     *
     * <p>Package-private for direct testing.
     */
    static String groundingRetryIfNeeded(String answer, List<ChatMessage> messages, Context ctx) {        if (answer == null || answer.isBlank()) return answer;
        if (answer.length() < UNGROUNDED_MIN_CHARS) return answer;
        if (ctx == null || ctx.llm() == null) return answer;

        String userRequest = latestUserRequest(messages);
        if (!looksLikeEvidenceRequest(userRequest)) return answer;

        LOG.info("No-tool grounding retry fired: answer={} chars, zero tools, "
                + "user asked for evidence. Re-prompting once.", answer.length());

        messages.add(ChatMessage.assistant(answer));
        messages.add(ChatMessage.user(
                "Your previous answer was produced without reading any files. "
                + "The user asked for an answer grounded in the actual workspace. "
                + "Use the available file tools to read the relevant files, then "
                + "answer concretely from what you read. Do not guess about file "
                + "contents. Do not describe files you have not read."));

        try {
            LlmClient.StreamResult retry = chatFull(ctx, messages);
            String retryText = retry.text();
            if (retryText != null && !retryText.isBlank() && !retryText.equals(answer)) {
                LOG.info("Grounding retry produced a different answer ({} → {} chars)",
                        answer.length(), retryText.length());
                return retryText;
            }
            LOG.warn("Grounding retry did not produce a substantive new answer. "
                    + "Annotating original.");
        } catch (Exception e) {
            LOG.warn("Grounding retry failed: {}. Annotating original.", e.getMessage());
        }
        return UNGROUNDED_ANNOTATION + answer;
    }
}

