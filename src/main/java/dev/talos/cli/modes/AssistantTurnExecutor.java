package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.outcome.ProtectedReadAnswerGuard;
import dev.talos.runtime.outcome.RuntimeVerificationStatusAnswer;
import dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.CapabilityAnswerPolicy;
import dev.talos.runtime.policy.ConversationBoundaryPolicy;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.EvidenceGate;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.policy.UnsupportedDocumentMutationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.DirectoryListingEvidence;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.StaticWebImportIntent;
import dev.talos.runtime.verification.WebDiagnosticIntent;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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
@SuppressWarnings("resource") // Context-owned LlmClient is borrowed throughout the turn executor.
public final class AssistantTurnExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantTurnExecutor.class);

    private static final Set<String> CHANGE_SUMMARY_FOLLOW_UP_MARKERS = Set.of(
            "summarize what changed",
            "what changed",
            "what files changed",
            "what files were changed",
            "what files did you change",
            "what files did you modify",
            "what files were modified",
            "which files changed",
            "which files were changed",
            "which files did you change",
            "which files did you modify",
            "which files were modified",
            "changed during this audit",
            "changed during this session",
            "modified during this audit",
            "modified during this session",
            "what did you change",
            "what was changed",
            "what did you do",
            "summary of changes"
    );

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
        return !ToolCallParser.looksLikeMalformedToolProtocol(answer)
                && ToolCallParser.containsToolCalls(answer);
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
        PromptDebugCapture.beginTurn();
        StringBuilder out = new StringBuilder();
        boolean streamed = false;
        WorkspaceBoundaryPreflight workspaceBoundaryPreflight =
                workspaceBoundaryPreflight(messages, workspace, ctx);
        if (workspaceBoundaryPreflight.directAnswer() != null) {
            return directTurnOutput(workspaceBoundaryPreflight.directAnswer(), ctx, opts);
        }
        boolean workspaceBoundaryReplayedRequest = workspaceBoundaryPreflight.effectiveUserRequest() != null;
        if (workspaceBoundaryPreflight.effectiveUserRequest() != null) {
            messages = replaceLatestUserRequest(messages, workspaceBoundaryPreflight.effectiveUserRequest());
        }
        AssistantTurnPreparation.PreparedTurn preparedTurn = AssistantTurnPreparation.prepare(
                messages, workspace, ctx, workspaceBoundaryReplayedRequest);
        ctx = preparedTurn.ctx();
        CurrentTurnPlan currentTurnPlan = preparedTurn.plan();
        Context turnContext = ctx;
        String directAnswer = deterministicDirectAnswerIfNeeded(
                messages, currentTurnPlan.taskContract(), workspace, ctx);
        if (directAnswer != null) {
            return directTurnOutput(directAnswer, ctx, opts);
        }
        ReadEvidenceHandoff.Result unsupportedPreflight = unsupportedCapabilityPreflightIfNeeded(
                messages, currentTurnPlan, workspace, ctx);
        if (unsupportedPreflight.loopResult() != null) {
            appendExtraSummary(out, unsupportedPreflight.extraSummary());
            out.append(shapeAnswerAfterToolLoop(
                    unsupportedPreflight.answer(),
                    messages,
                    currentTurnPlan,
                    unsupportedPreflight.loopResult(),
                    workspace,
                    0,
                    opts));
            return new TurnOutput(out.toString(), false);
        }
        boolean useStreaming = shouldUseStreaming(ctx, currentTurnPlan, workspace);

        TurnSourceEvidenceCapture.begin();
        TurnTaskContractCapture.set(currentTurnPlan.taskContract());
        try {
            if (useStreaming) {
                // ── Streaming path ──────────────────────────────────────────
                LlmClient.StreamResult streamResult =
                        chatStreamFullWithInitialContextFallback(ctx, messages, currentTurnPlan);
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
                        if (blocksToolCallsForContract(currentTurnPlan.taskContract())) {
                            answer = answerForBlockedSmallTalkToolCalls(answer, messages, opts);
                            emitBlockedSmallTalkToolCallAnswer(answer, ctx);
                            out.append(answer);
                        } else {
                            LOG.debug("Tool calls detected in streamed response (native: {}), entering tool-call loop",
                                    streamResult.hasToolCalls());
                            ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                    answer, streamResult.toolCalls(), messages, workspace, ctx);
                            answer = loopResult.finalAnswer();
                            LOG.debug("Streaming tool-call loop complete: {} iterations, {} tools invoked",
                                    loopResult.iterations(), loopResult.toolsInvoked());
                            ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                    answer, messages, currentTurnPlan, loopResult, workspace, ctx, opts);
                            appendExtraSummary(out, resolution.extraSummary());
                            out.append(resolution.answer());
                        }
                    } else {
                        // No tool calls — content was streamed; record full text for memory.
                        // Streaming no-tool branch. We cannot silently retry here
                        // because prose is already on the terminal, so truthfulness
                        // must be enforced by visible annotation of high-risk shapes.
                        streamed = true;
                        String rawAnswer = answer;
                        answer = shapeAnswerWithoutTools(answer, messages, currentTurnPlan, ctx, true, opts);
                        emitStreamingNoToolCorrectionIfNeeded(rawAnswer, answer, ctx);
                        emitMalformedProtocolReplacementIfNeeded(rawAnswer, answer, ctx);
                        out.append(answer);
                    }
                } else {
                    out.append("(no answer)");
                }
            } else {
                // ── Non-streaming fallback (tests, non-interactive) ─────────
                // Use chatFull() so native tool calls are captured too
                // (chat() returns only String, losing native tool calls).
                final List<ChatMessage> llmMessages = messages;
                CompletableFuture<LlmClient.StreamResult> fut = CompletableFuture.supplyAsync(
                        () -> chatFull(turnContext, llmMessages, currentTurnPlan));
                LlmClient.StreamResult streamResult;
                try {
                    streamResult = fut.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (!(cause instanceof EngineException.ContextBudgetExceeded budget)) {
                        throw ex;
                    }
                    Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                            turnContext,
                            currentTurnPlan,
                            AssistantTurnExecutor::chatControlsForTurn);
                    if (fallback.isEmpty()) {
                        throw ex;
                    }
                    ExactWriteContextFallback.record(currentTurnPlan, budget);
                    CompletableFuture<LlmClient.StreamResult> fallbackFuture = CompletableFuture.supplyAsync(
                            () -> chatFullExactWriteContextFallback(turnContext, fallback.get()));
                    streamResult = fallbackFuture.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS);
                }
                if (ctx.streamSink() != null && ctx.onStreamComplete() != null) {
                    try { ctx.onStreamComplete().run(); } catch (Exception ignored) { }
                }
                String answer = streamResult.text();
                if (answer != null) {
                    if (ctx.toolCallLoop() != null && hasAnyToolCalls(streamResult)) {
                        if (blocksToolCallsForContract(currentTurnPlan.taskContract())) {
                            answer = answerForBlockedSmallTalkToolCalls(answer, messages, opts);
                        } else {
                            LOG.debug("Tool calls detected in LLM response (native: {}), entering tool-call loop",
                                    streamResult.hasToolCalls());
                            ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                    answer, streamResult.toolCalls(), messages, workspace, ctx);
                            answer = loopResult.finalAnswer();
                            LOG.debug("Buffered tool-call loop complete: {} iterations, {} tools invoked",
                                    loopResult.iterations(), loopResult.toolsInvoked());
                            ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                    answer, messages, currentTurnPlan, loopResult, workspace, ctx, opts);
                            appendExtraSummary(out, resolution.extraSummary());
                            answer = resolution.answer();
                        }
                    } else {
                        // No-tool-call path. Zero tools were invoked this turn.
                        // Grounding retry gate: if the user explicitly asked for evidence
                        // / reading / inspection and the answer is long-and-confident,
                        // re-prompt once asking the model to answer from workspace evidence.
                        ToolLoopAnswerResolution resolution = resolveNoToolAnswer(
                                answer, messages, currentTurnPlan, workspace, ctx, opts);
                        appendExtraSummary(out, resolution.extraSummary());
                        answer = resolution.answer();
                    }
                    out.append(answer);
                } else {
                    out.append("(no answer)");
                }
            }
        } catch (java.util.concurrent.TimeoutException te) {
            recordBackendFailureOutcome("LLM_TIMEOUT");
            out.append("\n[Timeout: LLM response took too long]\n");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof EngineException engineException) {
                appendEngineException(out, engineException);
            } else {
                appendGenericLlmFailure(out, cause == null ? ex : cause);
            }
        } catch (EngineException.ConnectionFailed cf) {
            appendEngineException(out, cf);
        } catch (EngineException.ModelNotFound mnf) {
            appendEngineException(out, mnf);
        } catch (EngineException.Transient tr) {
            appendEngineException(out, tr);
        } catch (EngineException ee) {
            appendEngineException(out, ee);
        } catch (Exception e) {
            appendGenericLlmFailure(out, e);
        } finally {
            TurnTaskContractCapture.clear();
            TurnSourceEvidenceCapture.clear();
        }

        return new TurnOutput(out.toString(), streamed);
    }

    private static void appendEngineException(StringBuilder out, EngineException ex) {
        if (ex instanceof EngineException.ContextBudgetExceeded budget) {
            recordBackendFailureOutcome("CONTEXT_BUDGET_EXCEEDED");
            LOG.warn("Context budget exceeded: estimatedTokens={}, inputBudgetTokens={}, contextWindowTokens={}, removedMessages={}",
                    budget.estimatedTokens(), budget.inputBudgetTokens(),
                    budget.contextWindowTokens(), budget.removedMessages());
            out.append("\n[Context budget exceeded: Talos could not safely fit this turn into the selected model context. ")
                    .append(budget.guidance()).append("]\n");
            return;
        }
        if (ex instanceof EngineException.ConnectionFailed cf) {
            recordBackendFailureOutcome("BACKEND_CONNECTION_FAILED");
            LOG.warn("Model engine not reachable: {}", SafeLogFormatter.throwableMessage(cf));
            String detail = actionableConnectionFailureDetail(cf);
            out.append("\n[Model engine not reachable - ");
            if (!detail.isBlank()) {
                out.append(detail).append(' ');
            }
            out.append(cf.guidance()).append("]\n");
            return;
        }
        if (ex instanceof EngineException.ModelNotFound mnf) {
            recordBackendFailureOutcome("BACKEND_MODEL_NOT_FOUND");
            LOG.warn("Model not found: {}", SafeLogFormatter.value(mnf.model()));
            out.append("\n").append(UiChrome.MODEL_NOT_FOUND_OPEN).append(mnf.model())
                    .append(UiChrome.MODEL_NOT_FOUND_MARKER).append(". ")
                    .append(mnf.guidance()).append("]\n");
            return;
        }
        if (ex instanceof EngineException.Transient tr) {
            recordBackendFailureOutcome("BACKEND_TRANSIENT_ERROR");
            LOG.warn("Transient engine error: {}", SafeLogFormatter.throwableMessage(tr));
            out.append("\n[").append(tr.guidance()).append("]\n");
            return;
        }
        if (ex instanceof EngineException.MalformedResponse malformed) {
            recordBackendFailureOutcome("BACKEND_MALFORMED_RESPONSE");
            LocalTurnTraceCapture.recordBackendMalformedResponse(
                    malformed.context(),
                    malformed.bodyHash(),
                    malformed.bodyChars());
            LOG.warn("Malformed engine response: context={}, bodyHash={}, bodyChars={}",
                    malformed.context(), malformed.bodyHash(), malformed.bodyChars());
            out.append("\n").append(UiChrome.ENGINE_ERROR_PREFIX).append(": Malformed engine response");
            if (!malformed.context().isBlank()) {
                out.append(" for ").append(malformed.context());
            }
            out.append(". ").append(malformed.guidance()).append("]\n");
            return;
        }
        recordBackendFailureOutcome(engineFailureClassification(ex));
        LOG.warn("Engine error: {}", SafeLogFormatter.throwableMessage(ex));
        out.append("\n").append(UiChrome.ENGINE_ERROR_PREFIX).append(": ").append(ex.getMessage()).append("]\n");
    }

    private static void appendGenericLlmFailure(StringBuilder out, Throwable e) {
        recordBackendFailureOutcome("LLM_CALL_FAILED");
        String detail = e == null ? null : e.getMessage();
        LOG.warn("LLM call failed: {}", SafeLogFormatter.text(detail));
        out.append("\n[Error during LLM call")
                .append(detail != null && !detail.isBlank() ? ": " + detail : "")
                .append("]\n");
    }

    private static void recordBackendFailureOutcome(String classification) {
        LocalTurnTraceCapture.recordOutcome(
                "FAILED",
                "NOT_RUN",
                "UNKNOWN",
                "BACKEND_ERROR",
                classification);
    }

    private static String engineFailureClassification(EngineException ex) {
        if (ex instanceof EngineException.ContextBudgetExceeded) {
            return "CONTEXT_BUDGET_EXCEEDED";
        }
        if (ex instanceof EngineException.ResponseError) {
            if (isContextBudgetFailure(ex)) {
                return "CONTEXT_BUDGET_EXCEEDED";
            }
            return "BACKEND_RESPONSE_ERROR";
        }
        if (ex instanceof EngineException.MalformedResponse) {
            return "BACKEND_MALFORMED_RESPONSE";
        }
        return "BACKEND_ENGINE_ERROR";
    }

    private static boolean isContextBudgetFailure(EngineException ex) {
        if (ex instanceof EngineException.ResponseError responseError
                && responseError.bodyLooksContextBudgetExceeded()) {
            return true;
        }
        String message = ex == null ? "" : Objects.toString(ex.getMessage(), "").toLowerCase(Locale.ROOT);
        return message.contains("exceeds")
                && (message.contains("available context size")
                || message.contains("context size")
                || message.contains("context window")
                || message.contains("context budget"));
    }

    private static String actionableConnectionFailureDetail(EngineException.ConnectionFailed ex) {
        String message = ex == null ? "" : Objects.toString(ex.getMessage(), "");
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.contains("unsupported gguf architecture")
                && !lower.contains("no fallback model was selected")) {
            return "";
        }
        String prefix = "Cannot connect to backend at ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /** Apply mode-specific sanitization then truncate if over budget. */
    private static String sanitizeAndTruncate(String answer, Options opts) {
        answer = opts.answerSanitizer.apply(answer);
        if (answer.length() > opts.responseMaxChars) {
            answer = answer.substring(0, (int) opts.responseMaxChars) + "\n\n[output truncated]";
        }
        return answer;
    }

    private static TurnOutput directTurnOutput(String answer, Context ctx, Options opts) {
        String shaped = sanitizeAndTruncate(answer == null ? "" : answer, opts);
        boolean streamed = ctx != null && ctx.streamSink() != null;
        if (streamed) {
            ctx.streamSink().accept(shaped);
            if (ctx.onStreamComplete() != null) {
                try { ctx.onStreamComplete().run(); } catch (Exception ignored) { }
            }
        }
        return new TurnOutput(shaped, streamed);
    }

    record ToolLoopAnswerResolution(String answer, String extraSummary) {}

    private static ToolLoopAnswerResolution resolveToolLoopAnswer(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        answer = synthesisRetryIfNeeded(answer, loopResult.toolsInvoked(), messages, ctx);

        MissingMutationRetry.Result mrr = mutationRequestRetryIfNeeded(
                answer, messages, plan, loopResult, workspace, ctx);
        answer = mrr.answer();

        InspectCompletenessRetry.Result irr = inspectCompletenessRetryIfNeeded(
                answer, messages, plan, loopResult, workspace, ctx);
        answer = irr.answer();

        ToolCallLoop.LoopResult outcomeLoopResult = mrr.retryLoopResult() != null
                ? MissingMutationRetry.mergeEvidence(loopResult, mrr.retryLoopResult())
                : irr.loopResult() != null ? irr.loopResult() : loopResult;
        ReadEvidenceHandoff.Result evidenceRecovery = readEvidenceRecoveryForPartialTargetsIfNeeded(
                answer, messages, plan, outcomeLoopResult, workspace, ctx);
        if (evidenceRecovery.loopResult() != null) {
            answer = evidenceRecovery.answer();
            outcomeLoopResult = evidenceRecovery.loopResult();
        }
        int outcomeExtraMutationSuccesses = 0;

        moveToVerifyAfterSuccessfulMutation(ctx, outcomeLoopResult, outcomeExtraMutationSuccesses);

        String finalAnswer = shapeAnswerAfterToolLoop(
                answer, messages, plan, outcomeLoopResult, workspace,
                outcomeExtraMutationSuccesses, mrr.actionObligationFailed(), opts);

        return new ToolLoopAnswerResolution(
                finalAnswer,
                joinExtraSummaries(
                        visibleToolLoopSummary(loopResult, mrr, irr),
                        evidenceRecovery.extraSummary())
        );
    }

    private static String visibleToolLoopSummary(
            ToolCallLoop.LoopResult loopResult,
            MissingMutationRetry.Result mutationRetry,
            InspectCompletenessRetry.Result inspectRetry
    ) {
        String baseSummary = loopResult == null ? null : loopResult.summary();
        String mutationRetrySummary = mutationRetry == null ? null : mutationRetry.extraSummary();
        if (inspectRetry != null && inspectRetry.loopResult() != null) {
            return joinExtraSummaries(mutationRetrySummary, inspectRetry.extraSummary());
        }
        String withMutationRetry = joinExtraSummaries(baseSummary, mutationRetrySummary);
        return joinExtraSummaries(withMutationRetry, inspectRetry == null ? null : inspectRetry.extraSummary());
    }

    private static ToolLoopAnswerResolution resolveNoToolAnswer(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        if (ToolCallParser.looksLikeMalformedProtocolArrayDebris(answer)
                || ToolCallParser.looksLikeMalformedToolProtocol(answer)) {
            // T743: on mutation/workspace-obligation turns, malformed protocol
            // debris gets one bounded MissingMutationRetry pass (escalated
            // constraints) before the no-action notice. The r1 bank failure
            // showed the model that ATTEMPTED a tool call got zero retries.
            // If the retry does not produce a successful mutation, the
            // original fail-fast shaping (no-action notice) is preserved.
            CurrentTurnPlan debrisPlan = safePlanFromMessages(plan, messages, ctx);
            boolean retryableObligation = debrisPlan != null
                    && debrisPlan.taskContract() != null
                    && debrisPlan.taskContract().mutationAllowed()
                    && ResponseObligationVerifier.unsatisfiedNoToolResponse(
                            debrisPlan.actionObligation(), answer);
            if (retryableObligation) {
                ToolCallLoop.LoopResult debrisLoop = emptyNoToolLoopResult(answer, messages);
                MissingMutationRetry.Result debrisRetry = mutationRequestRetryIfNeeded(
                        answer, messages, plan, debrisLoop, workspace, ctx);
                boolean retryMutated = debrisRetry.mutationsInRetry() > 0
                        || (debrisRetry.retryLoopResult() != null
                                && debrisRetry.retryLoopResult().mutatingToolSuccesses() > 0);
                if (retryMutated) {
                    ToolCallLoop.LoopResult verificationLoop = debrisRetry.retryLoopResult() == null
                            ? debrisLoop
                            : debrisRetry.retryLoopResult();
                    int extraMutationSuccesses = debrisRetry.retryLoopResult() == null
                            ? debrisRetry.mutationsInRetry()
                            : 0;
                    moveToVerifyAfterSuccessfulMutation(ctx, verificationLoop, extraMutationSuccesses);
                    return new ToolLoopAnswerResolution(
                            shapeAnswerAfterToolLoop(
                                    debrisRetry.answer(), messages, plan, verificationLoop, workspace,
                                    extraMutationSuccesses, debrisRetry.actionObligationFailed(), opts),
                            debrisRetry.extraSummary());
                }
            }
            return new ToolLoopAnswerResolution(
                    shapeAnswerWithoutTools(answer, messages, plan, ctx, false, opts),
                    null);
        }
        ToolCallLoop.LoopResult noToolLoopResult = emptyNoToolLoopResult(answer, messages);
        MissingMutationRetry.Result mrr = mutationRequestRetryIfNeeded(
                answer, messages, plan, noToolLoopResult, workspace, ctx);
        if (mrr.extraSummary() != null || mrr.mutationsInRetry() > 0) {
            ToolCallLoop.LoopResult verificationLoop =
                    mrr.retryLoopResult() == null ? noToolLoopResult : mrr.retryLoopResult();
            int extraMutationSuccesses =
                    mrr.retryLoopResult() == null ? mrr.mutationsInRetry() : 0;
            moveToVerifyAfterSuccessfulMutation(ctx, verificationLoop, extraMutationSuccesses);
            return new ToolLoopAnswerResolution(
                    shapeAnswerAfterToolLoop(
                            mrr.answer(), messages, plan, verificationLoop, workspace,
                            extraMutationSuccesses, mrr.actionObligationFailed(), opts),
                    mrr.extraSummary());
        }
        ReadEvidenceHandoff.Result readEvidenceHandoff = readEvidenceHandoffIfNeeded(
                mrr.answer(), messages, plan, workspace, ctx);
        if (readEvidenceHandoff.loopResult() != null) {
            return new ToolLoopAnswerResolution(
                    shapeAnswerAfterToolLoop(
                            readEvidenceHandoff.answer(), messages, plan,
                            readEvidenceHandoff.loopResult(), workspace, 0, opts),
                    readEvidenceHandoff.extraSummary());
        }
        ReadOnlyInspectionRetry.Result inspectionRetry = readOnlyInspectionRetryIfNeeded(
                mrr.answer(), messages, plan, workspace, ctx);
        if (inspectionRetry.loopResult() != null) {
            return new ToolLoopAnswerResolution(
                    shapeAnswerAfterToolLoop(
                            inspectionRetry.answer(), messages, plan, inspectionRetry.loopResult(),
                            workspace, 0, opts),
                    inspectionRetry.extraSummary());
        }
        return new ToolLoopAnswerResolution(
                shapeAnswerWithoutTools(
                        inspectionRetry.answer(), messages, plan, ctx, false,
                        mrr.actionObligationFailed(), opts),
                null);
    }

    static ReadEvidenceHandoff.Result unsupportedCapabilityPreflightIfNeeded(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return ReadEvidenceHandoff.unsupportedCapabilityPreflightIfNeeded(
                messages, safePlan, workspace, ctx);
    }

    static ReadEvidenceHandoff.Result readEvidenceHandoffIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return ReadEvidenceHandoff.readEvidenceHandoffIfNeeded(
                answer, messages, safePlan, workspace, ctx);
    }

    static ReadEvidenceHandoff.Result readEvidenceRecoveryForPartialTargetsIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(
                answer, messages, safePlan, loopResult, workspace, ctx);
    }

    static ReadOnlyInspectionRetry.Result readOnlyInspectionRetryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            Path workspace,
            Context ctx
    ) {
        return readOnlyInspectionRetryIfNeeded(
                answer,
                messages,
                compatibilityPlanFromMessages(messages, ctx),
                workspace,
                ctx);
    }

    static ReadOnlyInspectionRetry.Result readOnlyInspectionRetryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return ReadOnlyInspectionRetry.retryIfNeeded(
                answer,
                messages,
                safePlan,
                workspace,
                ctx,
                retryMessages -> chatFull(ctx, retryMessages));
    }

    private static ToolCallLoop.LoopResult emptyNoToolLoopResult(
            String answer,
            List<ChatMessage> messages
    ) {
        return new ToolCallLoop.LoopResult(
                answer == null ? "" : answer,
                0,
                0,
                List.of(),
                messages,
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);
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

    private static CurrentTurnPlan compatibilityPlanFromMessages(List<ChatMessage> messages, Context ctx) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = currentExecutionPhase(ctx, contract);
        List<String> nativeTools = ctx == null
                ? defaultVisibleToolNames(contract, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        return CurrentTurnPlan.compatibility(contract, phase, nativeTools, nativeTools, List.of());
    }

    private static CurrentTurnPlan safePlanFromMessages(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx
    ) {
        return plan == null ? compatibilityPlanFromMessages(messages, ctx) : plan;
    }

    private static ExecutionPhase currentExecutionPhase(Context ctx, TaskContract contract) {
        if (ctx != null && ctx.executionPhaseState() != null) {
            return ctx.executionPhaseState().phase();
        }
        return contract != null && contract.mutationAllowed()
                ? ExecutionPhase.APPLY
                : ExecutionPhase.INSPECT;
    }

    private static boolean shouldUseStreaming(Context ctx, CurrentTurnPlan plan, Path workspace) {
        if (ctx == null || ctx.streamSink() == null) return false;
        TaskContract taskContract = plan == null ? null : plan.taskContract();
        if (taskContract != null && taskContract.mutationAllowed()) return false;
        if (EvidenceGate.requiresReadEvidenceHandoff(EvidenceGate.selectObligation(
                plan,
                workspace,
                ctx == null ? null : ctx.cfg()))) return false;
        return !requiresWorkspaceEvidence(taskContract);
    }

    private static boolean blocksToolCallsForContract(TaskContract taskContract) {
        return taskContract != null && taskContract.type() == TaskType.SMALL_TALK;
    }

    private static String answerForBlockedSmallTalkToolCalls(
            String answer,
            List<ChatMessage> messages,
            Options opts
    ) {
        String stripped = ToolCallParser.stripToolCalls(answer == null ? "" : answer).strip();
        if (!stripped.isBlank()) {
            return sanitizeAndTruncate(stripped, opts);
        }
        String userRequest = latestUserRequest(messages);
        if (CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(userRequest)) {
            return sanitizeAndTruncate(CapabilityAnswerPolicy.workspaceSwitchUnsupportedAnswer(), opts);
        }
        if (looksLikeAssistantIdentityTurn(userRequest)) {
            return sanitizeAndTruncate(CapabilityAnswerPolicy.identityAnswer(), opts);
        }
        if (looksLikeAssistantCapabilityTurn(userRequest)) {
            return sanitizeAndTruncate(CapabilityAnswerPolicy.capabilityAnswer(), opts);
        }
        return sanitizeAndTruncate("Hi, I am Talos.", opts);
    }

    private static void emitBlockedSmallTalkToolCallAnswer(String answer, Context ctx) {
        if (ctx == null || ctx.streamSink() == null || answer == null || answer.isBlank()) return;
        ctx.streamSink().accept(answer);
        if (ctx.streamSink() instanceof ToolCallStreamFilter filter) {
            filter.flush();
        }
    }

    private static boolean requiresWorkspaceEvidence(TaskContract taskContract) {
        if (taskContract == null) return false;
        return switch (taskContract.type()) {
            case DIRECTORY_LISTING, WORKSPACE_EXPLAIN, VERIFY_ONLY -> true;
            case DIAGNOSE_ONLY -> looksLikeEvidenceRequest(taskContract.originalUserRequest())
                    || containsWorkspaceEvidenceAnchor(taskContract.originalUserRequest());
            default -> false;
        };
    }

    private static boolean containsWorkspaceEvidenceAnchor(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("workspace")
                || lower.contains("folder")
                || lower.contains("directory")
                || lower.contains("project")
                || lower.contains("repo")
                || lower.contains("repository")
                || lower.contains("here")
                || lower.contains("this")
                || lower.contains("website")
                || lower.contains("web page")
                || lower.contains("webpage")
                || lower.contains("site")
                || lower.contains("html")
                || lower.contains("css")
                || lower.contains("javascript")
                || lower.contains("script");
    }

    private static LlmClient.StreamResult chatStreamFull(Context ctx, List<ChatMessage> messages) {
        return chatStreamFull(ctx, messages, compatibilityPlanFromMessages(messages, ctx));
    }

    private static LlmClient.StreamResult chatStreamFull(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        return ctx.llm().chatStreamFull(
                messages,
                ctx.streamSink(),
                ctx.nativeToolSpecs(),
                chatControlsForTurn(ctx, plan));
    }

    private static LlmClient.StreamResult chatStreamFullWithInitialContextFallback(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        try {
            return chatStreamFull(ctx, messages, plan);
        } catch (EngineException.ContextBudgetExceeded budget) {
            Optional<ExactWriteContextFallback.Request> fallback = ExactWriteContextFallback.prepare(
                    ctx,
                    plan,
                    AssistantTurnExecutor::chatControlsForTurn);
            if (fallback.isEmpty()) {
                throw budget;
            }
            ExactWriteContextFallback.record(plan, budget);
            ExactWriteContextFallback.Request request = fallback.get();
            return ctx.llm().chatStreamFull(
                    request.messages(),
                    ctx.streamSink(),
                    request.toolSpecs(),
                    request.controls());
        }
    }

    private static LlmClient.StreamResult chatFull(Context ctx, List<ChatMessage> messages) {
        return chatFull(ctx, messages, compatibilityPlanFromMessages(messages, ctx));
    }

    private static LlmClient.StreamResult chatFull(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        return chatFull(ctx, messages, plan, ctx.nativeToolSpecs());
    }

    private static LlmClient.StreamResult chatFull(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        return ctx.llm().chatFull(
                messages,
                requestToolSpecs,
                chatControlsForTurn(ctx, plan, requestToolSpecsForControls(ctx, requestToolSpecs)));
    }

    private static ChatRequestControls chatControlsForTurn(Context ctx, CurrentTurnPlan plan) {
        return chatControlsForTurn(
                ctx,
                plan,
                ctx == null ? List.of() : ctx.nativeToolSpecs());
    }

    private static ChatRequestControls chatControlsForTurn(
            Context ctx,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        boolean supportsRequired = ctx != null
                && ctx.llm() != null
                && ctx.llm().supportsRequiredToolChoice();
        boolean supportsNamed = ctx != null
                && ctx.llm() != null
                && ctx.llm().supportsNamedToolChoice();
        return ProviderRequestControlPolicy.forTurn(
                plan,
                requestToolSpecs == null ? List.of() : requestToolSpecs,
                supportsRequired,
                supportsNamed);
    }

    private static LlmClient.StreamResult chatFullExactWriteContextFallback(
            Context ctx,
            ExactWriteContextFallback.Request fallback
    ) {
        return ctx.llm().chatFull(
                fallback.messages(),
                fallback.toolSpecs(),
                fallback.controls());
    }

    private static List<ToolSpec> requestToolSpecsForControls(Context ctx, List<ToolSpec> requestToolSpecs) {
        if (requestToolSpecs != null) return requestToolSpecs;
        if (ctx != null && ctx.nativeToolSpecs() != null) return ctx.nativeToolSpecs();
        if (ctx != null && ctx.llm() != null) return ctx.llm().getToolSpecs();
        return List.of();
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages) {
        AssistantTurnPreparation.injectTaskContractInstruction(messages);
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages, CurrentTurnPlan plan) {
        AssistantTurnPreparation.injectTaskContractInstruction(messages, plan);
    }

    static void injectProjectMemoryInstruction(List<ChatMessage> messages, ProjectMemoryContext projectMemory) {
        AssistantTurnPreparation.injectProjectMemoryInstruction(messages, projectMemory);
    }

    public static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools
    ) {
        AssistantTurnPreparation.injectTaskContractInstruction(messages, contract, phase, visibleTools);
    }

    private static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        return ToolSurfacePlanner.defaultVisibleToolNames(contract, phase);
    }

    static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        AssistantTurnPreparation.injectStaticVerificationRepairInstruction(messages, taskContract);
    }

    static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract,
            Path workspace
    ) {
        AssistantTurnPreparation.injectStaticVerificationRepairInstruction(messages, taskContract, workspace);
    }

    private record WorkspaceBoundaryPreflight(String directAnswer, String effectiveUserRequest) {
        static WorkspaceBoundaryPreflight none() {
            return new WorkspaceBoundaryPreflight(null, null);
        }

        static WorkspaceBoundaryPreflight direct(String answer) {
            return new WorkspaceBoundaryPreflight(answer, null);
        }

        static WorkspaceBoundaryPreflight useRequest(String request) {
            return new WorkspaceBoundaryPreflight(null, request);
        }
    }

    private static WorkspaceBoundaryPreflight workspaceBoundaryPreflight(
            List<ChatMessage> messages,
            Path workspace,
            Context ctx
    ) {
        if (ctx == null || ctx.memory() == null) return WorkspaceBoundaryPreflight.none();
        String userRequest = latestUserRequest(messages);
        if (userRequest == null || userRequest.isBlank()) return WorkspaceBoundaryPreflight.none();

        SessionMemory.PendingWorkspaceMutationConfirmation pending =
                ctx.memory().pendingWorkspaceMutationConfirmation();
        if (pending != null) {
            if (isWorkspaceMutationConfirmation(userRequest)) {
                ctx.memory().clearPendingWorkspaceMutationConfirmation();
                ctx.memory().clearFailedWorkspaceSwitch();
                return WorkspaceBoundaryPreflight.useRequest(pending.userRequest());
            }
            if (isWorkspaceMutationRejection(userRequest)) {
                ctx.memory().clearPendingWorkspaceMutationConfirmation();
                ctx.memory().clearFailedWorkspaceSwitch();
                return WorkspaceBoundaryPreflight.direct(
                        "No workspace change was made. The current workspace is still "
                                + workspaceDisplay(workspace, pending.currentWorkspace()) + ".");
            }
            ctx.memory().clearPendingWorkspaceMutationConfirmation();
            ctx.memory().clearFailedWorkspaceSwitch();
            return WorkspaceBoundaryPreflight.none();
        }

        SessionMemory.FailedWorkspaceSwitch failedSwitch = ctx.memory().failedWorkspaceSwitch();
        if (failedSwitch == null) return WorkspaceBoundaryPreflight.none();
        if (CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(userRequest)) {
            return WorkspaceBoundaryPreflight.none();
        }

        TaskContract contract = TaskContractResolver.fromUserRequest(userRequest);
        if (isRelativeWorkspaceMutation(contract, userRequest)) {
            String currentWorkspace = workspaceDisplay(workspace, failedSwitch.currentWorkspace());
            ctx.memory().recordPendingWorkspaceMutationConfirmation(userRequest, currentWorkspace);
            return WorkspaceBoundaryPreflight.direct(
                    "The current workspace is still " + currentWorkspace
                            + ". Talos did not switch workspace after the previous request. "
                            + "Confirm if you want this change applied in the current workspace: "
                            + userRequest);
        }

        ctx.memory().clearFailedWorkspaceSwitch();
        return WorkspaceBoundaryPreflight.none();
    }

    private static List<ChatMessage> replaceLatestUserRequest(List<ChatMessage> messages, String effectiveUserRequest) {
        if (messages == null || messages.isEmpty()) return messages;
        ArrayList<ChatMessage> copy = new ArrayList<>(messages);
        for (int i = copy.size() - 1; i >= 0; i--) {
            ChatMessage message = copy.get(i);
            if (message != null && "user".equals(message.role())) {
                copy.set(i, ChatMessage.user(effectiveUserRequest));
                return copy;
            }
        }
        return messages;
    }

    private static boolean isRelativeWorkspaceMutation(TaskContract contract, String userRequest) {
        return contract != null
                && contract.mutationAllowed()
                && !containsAbsolutePath(userRequest);
    }

    private static boolean containsAbsolutePath(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String value = userRequest.strip();
        return Pattern.compile("(?i)(?:^|\\s|[`'\"(])(?:[a-z]:[\\\\/]|\\\\\\\\|/)").matcher(value).find();
    }

    private static boolean isWorkspaceMutationConfirmation(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).strip();
        if (isWorkspaceMutationRejection(lower)) return false;
        return lower.equals("yes")
                || lower.equals("y")
                || lower.equals("ok")
                || lower.equals("okay")
                || lower.contains("yes,")
                || lower.contains("yes ")
                || lower.contains("go ahead")
                || lower.contains("do it")
                || lower.contains("apply it")
                || lower.contains("create it")
                || lower.contains("make it")
                || lower.contains("current workspace")
                || lower.contains("this workspace")
                || lower.equals("here");
    }

    private static boolean isWorkspaceMutationRejection(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).strip();
        return lower.equals("no")
                || lower.equals("n")
                || lower.startsWith("no,")
                || lower.startsWith("no ")
                || lower.contains("do not")
                || lower.contains("don't")
                || lower.contains("dont")
                || lower.contains("cancel");
    }

    private static String workspaceDisplay(Path workspace, String fallback) {
        if (workspace != null) {
            try {
                return workspace.toAbsolutePath().normalize().toString();
            } catch (RuntimeException ignored) {
                // fall through to fallback
            }
        }
        return fallback == null || fallback.isBlank() ? "the original workspace" : fallback;
    }

    private static void recordFailedWorkspaceSwitch(String userRequest, Path workspace, Context ctx) {
        if (ctx == null || ctx.memory() == null) return;
        ctx.memory().recordFailedWorkspaceSwitch(userRequest, workspaceDisplay(workspace, ""));
    }

    private static String deterministicDirectAnswerIfNeeded(
            List<ChatMessage> messages,
            TaskContract contract,
            Path workspace,
            Context ctx
    ) {
        String userRequest = latestUserRequest(messages);
        if (contract != null && contract.type() == TaskType.SMALL_TALK) {
            String conversationBoundaryAnswer = ConversationBoundaryPolicy.deterministicAnswer(userRequest);
            if (conversationBoundaryAnswer != null) {
                return conversationBoundaryAnswer;
            }
            if (CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(userRequest)) {
                recordFailedWorkspaceSwitch(userRequest, workspace, ctx);
                return CapabilityAnswerPolicy.workspaceSwitchUnsupportedAnswer();
            }
            if (CapabilityAnswerPolicy.looksLikeToolAliasCapabilityTurn(userRequest)) {
                return CapabilityAnswerPolicy.toolAliasCapabilityAnswer(userRequest);
            }
        }
        if (contract != null
                && contract.type() == TaskType.SMALL_TALK
                && looksLikeAssistantIdentityTurn(userRequest)) {
            return CapabilityAnswerPolicy.identityAnswer();
        }
        if (contract != null
                && contract.type() == TaskType.SMALL_TALK
                && looksLikeAssistantCapabilityTurn(userRequest)) {
            return CapabilityAnswerPolicy.capabilityAnswer();
        }
        Optional<String> unsupportedDocumentMutation =
                UnsupportedDocumentMutationPolicy.answerIfUnsupportedMutation(contract);
        if (unsupportedDocumentMutation.isPresent()) {
            return unsupportedDocumentMutation.get();
        }
        if (contract == null || !contract.mutationRequested()) {
            Optional<String> unsupportedDocumentCapability =
                    UnsupportedDocumentMutationPolicy.answerIfUnsupportedCapabilityQuestion(userRequest);
            if (unsupportedDocumentCapability.isPresent()) {
                return unsupportedDocumentCapability.get();
            }
        }
        String unsupportedCommand = unsupportedCommandAnswerIfNeeded(contract);
        if (unsupportedCommand != null) {
            return unsupportedCommand;
        }
        String checkpointRestore = checkpointRestoreAnswerIfNeeded(contract);
        if (checkpointRestore != null) {
            return checkpointRestore;
        }
        String sessionUncertainty = sessionUncertaintyAnswerIfNeeded(ctx, contract);
        if (sessionUncertainty != null) {
            return sessionUncertainty;
        }
        ChangeSummaryContext changeSummaryContext = ctx == null || ctx.memory() == null
                ? null
                : ctx.memory().changeSummaryContext();
        if (contract == null || !contract.mutationAllowed()) {
            String runtimeVerificationStatus = RuntimeVerificationStatusAnswer.renderIfNeeded(
                    userRequest,
                    changeSummaryContext);
            if (runtimeVerificationStatus != null) {
                return runtimeVerificationStatus;
            }
        }
        String runtimeMetaEvidence = runtimeMetaEvidenceAnswerIfNeeded(ctx, userRequest, contract);
        if (runtimeMetaEvidence != null) {
            return runtimeMetaEvidence;
        }
        String staticWebDiagnosticFollowUp =
                previousRuntimeOwnedStaticWebDiagnosticFollowUpIfNeeded(messages, userRequest);
        if (staticWebDiagnosticFollowUp != null) {
            return staticWebDiagnosticFollowUp;
        }
        String runtimeChangeSummary = runtimeChangeSummaryIfNeeded(ctx, userRequest);
        if (runtimeChangeSummary != null) {
            return runtimeChangeSummary;
        }
        String documentCreationStatus = documentCreationStatusIfNeeded(ctx, messages, userRequest);
        if (documentCreationStatus != null) {
            return documentCreationStatus;
        }
        return verifiedFollowUpSummaryIfNeeded(messages, userRequest);
    }

    private static String unsupportedCommandAnswerIfNeeded(TaskContract contract) {
        if (contract == null
                || !"unsupported-command-verification-request".equals(contract.classificationReason())) {
            return null;
        }
        return "I can't run that command check because no approved command profile was specified. "
                + "Talos can only run bounded approved command profiles, such as Gradle test/check/build profiles, "
                + "when the request names a supported profile.";
    }

    private static String checkpointRestoreAnswerIfNeeded(TaskContract contract) {
        if (contract == null || contract.type() != TaskType.CHECKPOINT_RESTORE) {
            return null;
        }
        return """
                Checkpoint restore is available through Talos's local checkpoint command.
                I did not restore files from this natural-language turn.
                Run `/checkpoint list` to see available checkpoint IDs, then run `/checkpoint restore <id>` to restore one. Checkpoint restore remains approval-gated.""";
    }

    private static String sessionUncertaintyAnswerIfNeeded(Context ctx, TaskContract contract) {
        if (contract == null
                || !"session-uncertainty-question".equals(contract.classificationReason())) {
            return null;
        }
        ChangeSummaryContext context = ctx == null || ctx.memory() == null
                ? null
                : ctx.memory().changeSummaryContext();
        if (context == null || !hasSessionUncertaintyEvidence(context)) {
            return """
                    Uncertainty:
                    - No unresolved Talos runtime evidence is recorded for this session/audit.
                    - This only covers Talos's runtime mutation history; it does not cover external edits or protected file contents.""";
        }

        StringBuilder out = new StringBuilder("Uncertainty:\n");
        boolean added = false;
        if (latestRecordedWorkNotVerifiedComplete(context)) {
            out.append("- Latest recorded mutation evidence is not verified complete");
            String status = sessionUncertaintyStatus(context);
            if (!status.isBlank()) out.append(" (").append(status).append(')');
            out.append(".\n");
            added = true;
        }
        if (!context.unresolvedTargets().isEmpty()) {
            out.append("- Unresolved target(s): ")
                    .append(String.join(", ", context.unresolvedTargets()))
                    .append(".\n");
            added = true;
        }
        if (!context.verifierFindings().isEmpty()) {
            out.append("- Verifier finding(s): ")
                    .append(String.join("; ", context.verifierFindings().stream().limit(3).toList()))
                    .append(".\n");
            added = true;
        }
        if (!context.unresolvedVerificationFailures().isEmpty()) {
            List<String> failures = context.unresolvedVerificationFailures().stream()
                    .limit(3)
                    .map(AssistantTurnExecutor::renderSessionUncertaintyFailure)
                    .filter(text -> !text.isBlank())
                    .toList();
            if (!failures.isEmpty()) {
                out.append("- Unresolved verification failure(s): ")
                        .append(String.join("; ", failures))
                        .append(".\n");
                added = true;
            }
        }
        if (!added) {
            out.append("- No unresolved runtime verifier failures are recorded; confidence is limited to Talos-recorded tool outcomes.\n");
        }
        out.append("- Scope: runtime mutation history only; external edits and protected file contents are outside this answer.");
        return out.toString();
    }

    private static boolean hasSessionUncertaintyEvidence(ChangeSummaryContext context) {
        if (context == null) return false;
        return context.hasRecordedChanges()
                || !context.unresolvedTargets().isEmpty()
                || !context.verifierFindings().isEmpty()
                || !context.unresolvedVerificationFailures().isEmpty()
                || !context.verificationStatus().isBlank()
                || !context.completionStatus().isBlank();
    }

    private static boolean latestRecordedWorkNotVerifiedComplete(ChangeSummaryContext context) {
        if (context == null) return false;
        if (!context.unresolvedTargets().isEmpty()
                || !context.unresolvedVerificationFailures().isEmpty()) {
            return true;
        }
        if ("FAILED".equalsIgnoreCase(context.verificationStatus())
                || "TASK_INCOMPLETE".equalsIgnoreCase(context.completionStatus())
                || "COMPLETED_UNVERIFIED".equalsIgnoreCase(context.completionStatus())) {
            return true;
        }
        for (ChangeSummaryContext.FileChange change : context.changedFiles()) {
            if (change == null) continue;
            boolean hasState = !change.verificationStatus().isBlank()
                    || !change.completionStatus().isBlank();
            boolean verified = "PASSED".equalsIgnoreCase(change.verificationStatus())
                    || "COMPLETED_VERIFIED".equalsIgnoreCase(change.completionStatus());
            if (hasState && !verified) return true;
        }
        return false;
    }

    private static String sessionUncertaintyStatus(ChangeSummaryContext context) {
        if (context == null) return "";
        List<String> parts = new ArrayList<>();
        if (!context.verificationStatus().isBlank()) {
            parts.add("verifier=" + context.verificationStatus());
        }
        if (!context.completionStatus().isBlank()) {
            parts.add("completion=" + context.completionStatus());
        }
        return String.join("; ", parts);
    }

    private static String renderSessionUncertaintyFailure(ChangeSummaryContext.VerificationFailure failure) {
        if (failure == null) return "";
        StringBuilder out = new StringBuilder();
        if (!failure.paths().isEmpty()) {
            out.append(String.join(", ", failure.paths()));
        }
        if (failure.turnNumber() > 0) {
            if (!out.isEmpty()) out.append(' ');
            out.append("(turn ").append(failure.turnNumber()).append(')');
        }
        if (!failure.findings().isEmpty()) {
            if (!out.isEmpty()) out.append(": ");
            out.append(String.join("; ", failure.findings().stream().limit(2).toList()));
        }
        return out.toString();
    }

    private static String runtimeMetaEvidenceAnswerIfNeeded(
            Context ctx,
            String userRequest,
            TaskContract contract
    ) {
        if (contract == null || !"session-meta-evidence-question".equals(contract.classificationReason())) {
            return null;
        }
        if (contract.expectedTargets().isEmpty()) return null;
        SessionEvidenceKind kind = sessionEvidenceKind(userRequest);
        if (kind == SessionEvidenceKind.UNKNOWN) return null;

        List<SessionMemory.ToolEvidence> evidence = ctx == null || ctx.memory() == null
                ? List.of()
                : ctx.memory().toolEvidence();
        List<String> targets = contract.expectedTargets().stream()
                .filter(target -> target != null && !target.isBlank())
                .sorted()
                .toList();
        if (targets.isEmpty()) return null;

        List<String> matched = targets.stream()
                .filter(target -> hasMatchingRuntimeEvidence(evidence, target, kind))
                .toList();
        String targetText = String.join(", ", targets);
        String action = sessionEvidenceActionText(kind);
        if (matched.size() == targets.size()) {
            return "Yes. Talos has runtime evidence that it " + action + " " + targetText
                    + " earlier in this session.";
        }
        return "No. Talos has no runtime evidence that it " + action + " " + targetText
                + " earlier in this session.";
    }

    private enum SessionEvidenceKind {
        READ,
        MUTATE,
        UNKNOWN
    }

    private static SessionEvidenceKind sessionEvidenceKind(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return SessionEvidenceKind.UNKNOWN;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (lower.contains("did you read")
                || lower.contains("have you read")
                || lower.contains("has talos read")
                || lower.contains("did talos read")
                || lower.contains("did you open")
                || lower.contains("did you inspect")
                || lower.contains("has talos opened")
                || lower.contains("has talos inspected")) {
            return SessionEvidenceKind.READ;
        }
        if (lower.contains("write")
                || lower.contains("edit")
                || lower.contains("change")
                || lower.contains("modify")
                || lower.contains("update")) {
            return SessionEvidenceKind.MUTATE;
        }
        return SessionEvidenceKind.UNKNOWN;
    }

    private static boolean hasMatchingRuntimeEvidence(
            List<SessionMemory.ToolEvidence> evidence,
            String target,
            SessionEvidenceKind kind
    ) {
        if (evidence == null || evidence.isEmpty() || target == null || target.isBlank()) return false;
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        for (SessionMemory.ToolEvidence item : evidence) {
            if (item == null || !item.success()) continue;
            if (!normalizedTarget.equals(ToolCallSupport.normalizePath(item.pathHint()))) continue;
            String toolName = canonicalToolName(item.toolName());
            if (kind == SessionEvidenceKind.READ && "talos.read_file".equals(toolName)) return true;
            if (kind == SessionEvidenceKind.MUTATE && ToolCallSupport.isMutatingTool(toolName)) return true;
        }
        return false;
    }

    private static String sessionEvidenceActionText(SessionEvidenceKind kind) {
        return switch (kind) {
            case READ -> "read";
            case MUTATE -> "mutated";
            case UNKNOWN -> "used";
        };
    }

    private static String previousRuntimeOwnedStaticWebDiagnosticFollowUpIfNeeded(
            List<ChatMessage> messages,
            String userRequest
    ) {
        if (!looksLikePreviousStaticWebDiagnosticFollowUp(userRequest)) return null;
        String previousAssistantText = previousAssistantBeforeLatestUser(messages);
        if (!looksLikeRuntimeOwnedStaticWebDiagnostics(previousAssistantText)) return null;
        List<String> blockers = staticWebDiagnosticProblemLines(previousAssistantText);
        if (blockers.isEmpty()) {
            return "Based on the previous runtime-owned static web diagnostics, Talos did not find "
                    + "obvious HTML/CSS/JavaScript linkage blockers in that diagnostic.";
        }
        return "Based on the previous runtime-owned static web diagnostics, the blockers are:\n"
                + String.join("\n", blockers);
    }

    private static boolean looksLikePreviousStaticWebDiagnosticFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean previousEvidence = lower.contains("previous answer")
                || lower.contains("previous response")
                || lower.contains("previous evidence")
                || lower.contains("verified file evidence")
                || lower.contains("verified evidence")
                || lower.contains("based only on verified");
        if (!previousEvidence) return false;
        return lower.contains("blocker")
                || lower.contains("prevent")
                || lower.contains("issue")
                || lower.contains("problem")
                || lower.contains("finding")
                || lower.contains("diagnos")
                || lower.contains("why")
                || lower.contains("what");
    }

    private static String previousAssistantBeforeLatestUser(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedLatestUser = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null) continue;
            if ("user".equals(message.role()) && !skippedLatestUser) {
                skippedLatestUser = true;
                continue;
            }
            if (!skippedLatestUser) continue;
            if ("assistant".equals(message.role())) {
                return message.content();
            }
            if ("user".equals(message.role())) {
                return null;
            }
        }
        return null;
    }

    private static boolean looksLikeRuntimeOwnedStaticWebDiagnostics(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("i inspected the primary web files:")
                && (lower.contains("static web diagnostics found:")
                || lower.contains("static web diagnostics did not find obvious"))
                && lower.contains("no files were changed.");
    }

    private static List<String> staticWebDiagnosticProblemLines(String answer) {
        if (answer == null || answer.isBlank()) return List.of();
        List<String> problems = new ArrayList<>();
        boolean inProblems = false;
        for (String rawLine : answer.lines().toList()) {
            String line = rawLine.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("static web diagnostics found:")) {
                inProblems = true;
                continue;
            }
            if (!inProblems) continue;
            if (line.isBlank() || lower.equals("no files were changed.")) {
                break;
            }
            if (line.startsWith("- ")) {
                problems.add(line);
            } else if (!problems.isEmpty()) {
                int last = problems.size() - 1;
                problems.set(last, problems.get(last) + " " + line);
            }
        }
        return List.copyOf(problems);
    }

    private static String runtimeChangeSummaryIfNeeded(Context ctx, String userRequest) {
        if (!looksLikeChangeSummaryFollowUp(userRequest)) return null;
        ChangeSummaryContext context = ctx == null || ctx.memory() == null
                ? null
                : ctx.memory().changeSummaryContext();
        boolean includeUncertainty = looksLikeChangeSummaryUncertaintyQuestion(userRequest);
        if (context == null || !context.hasRecordedChanges()) {
            return looksLikeDirectChangedFilesQuestion(userRequest)
                    ? noRuntimeChangedFilesAnswer(includeUncertainty)
                    : null;
        }
        return context.renderForChangeSummaryQuestion(includeUncertainty);
    }

    private static String documentCreationStatusIfNeeded(
            Context ctx,
            List<ChatMessage> messages,
            String userRequest
    ) {
        Set<String> formats = requestedDocumentCreationStatusFormats(userRequest);
        if (formats.isEmpty()) return null;

        ChangeSummaryContext context = ctx == null || ctx.memory() == null
                ? null
                : ctx.memory().changeSummaryContext();
        List<String> recordedDocumentPaths = context == null
                ? List.of()
                : context.changedFiles().stream()
                .map(ChangeSummaryContext.FileChange::path)
                .filter(path -> hasRequestedDocumentExtension(path, formats))
                .sorted()
                .toList();

        String formatText = renderDocumentFormats(formats);
        StringBuilder out = new StringBuilder();
        out.append("No. Talos has no runtime evidence that it created a valid ")
                .append(formatText)
                .append(" in this session/audit.");
        if (!recordedDocumentPaths.isEmpty()) {
            out.append("\n\nRuntime-recorded document-path changes exist, but Talos did not verify them as valid binary documents: ")
                    .append(String.join(", ", recordedDocumentPaths))
                    .append('.');
        }
        if (hasPriorUnsupportedDocumentRefusal(messages, formats)) {
            out.append("\n\nRelevant prior outcome: Talos recorded unsupported-document capability refusals for the requested binary document format(s), not valid ")
                    .append(formatText)
                    .append(" creation.");
        }
        return out.toString();
    }

    private static Set<String> requestedDocumentCreationStatusFormats(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return Set.of();
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean statusQuestion = lower.contains("did you create")
                || lower.contains("have you created")
                || lower.contains("did talos create")
                || lower.contains("has talos created")
                || lower.contains("create any")
                || lower.contains("created any");
        if (!statusQuestion || !lower.contains("valid")) return Set.of();
        LinkedHashSet<String> formats = new LinkedHashSet<>();
        if (lower.contains("pdf")) formats.add("pdf");
        if (lower.contains("docx") || lower.contains("word document") || lower.contains("word file")) {
            formats.add("docx");
        }
        return Set.copyOf(formats);
    }

    private static boolean hasPriorUnsupportedDocumentRefusal(List<ChatMessage> messages, Set<String> formats) {
        if (messages == null || messages.isEmpty() || formats == null || formats.isEmpty()) return false;
        for (ChatMessage message : messages) {
            if (message == null || !"assistant".equals(message.role())) continue;
            String lower = message.content() == null ? "" : message.content().toLowerCase(Locale.ROOT);
            if (!lower.contains("unsupported") && !lower.contains("cannot create valid")) continue;
            if (formats.contains("pdf") && lower.contains("pdf")) return true;
            if (formats.contains("docx") && (lower.contains("docx") || lower.contains("word"))) return true;
        }
        return false;
    }

    private static boolean hasRequestedDocumentExtension(String path, Set<String> formats) {
        if (path == null || formats == null || formats.isEmpty()) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return formats.stream().anyMatch(format -> lower.endsWith("." + format));
    }

    private static String renderDocumentFormats(Set<String> formats) {
        boolean pdf = formats.contains("pdf");
        boolean docx = formats.contains("docx");
        if (pdf && docx) return "PDF or DOCX";
        if (pdf) return "PDF";
        if (docx) return "DOCX";
        return "binary document";
    }

    static boolean looksLikeAssistantIdentityTurn(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return CapabilityAnswerPolicy.looksLikeIdentityTurn(lower);
    }

    static boolean looksLikeAssistantCapabilityTurn(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return CapabilityAnswerPolicy.looksLikeCapabilityTurn(lower);
    }

    private static String verifiedFollowUpSummaryIfNeeded(
            List<ChatMessage> messages,
            String userRequest
    ) {
        if (!looksLikeChangeSummaryFollowUp(userRequest)
                && !MutationIntent.looksPriorChangeStatusQuestion(userRequest)) {
            return null;
        }
        if (messages == null || messages.isEmpty()) return null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"assistant".equals(message.role())) continue;
            String content = message.content();
            if (!looksLikeVerifiedMutationOutcome(content)) continue;
            return renderVerifiedFollowUpSummary(content);
        }
        return null;
    }

    static boolean looksLikeChangeSummaryFollowUp(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        for (String marker : CHANGE_SUMMARY_FOLLOW_UP_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static boolean looksLikeChangeSummaryUncertaintyQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("uncertainty")
                || lower.contains("uncertain")
                || lower.contains("not sure")
                || lower.contains("unknown")
                || lower.contains("confidence");
    }

    private static boolean looksLikeDirectChangedFilesQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean fileScoped = lower.contains("file") || lower.contains("files");
        boolean mutationScoped = lower.contains("changed")
                || lower.contains("change")
                || lower.contains("modified")
                || lower.contains("modify")
                || lower.contains("mutated")
                || lower.contains("mutation");
        boolean sessionScoped = lower.contains("audit")
                || lower.contains("session")
                || lower.contains("turn")
                || lower.contains("workspace");
        return fileScoped && (mutationScoped || sessionScoped);
    }

    private static String noRuntimeChangedFilesAnswer(boolean includeUncertainty) {
        String answer = "No files were changed by Talos in the current session/audit according to Talos's runtime mutation history.\n\n"
                + "Talos has no runtime-recorded write/edit mutations for this session, so there are no runtime-owned changed files to list.";
        if (!includeUncertainty) return answer;
        return answer + "\n\n" + ChangeSummaryContext.runtimeUncertaintyClause();
    }

    private static boolean looksLikeVerifiedMutationOutcome(String content) {
        if (content == null || content.isBlank()) return false;
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("static verification")
                || lower.contains("partial verification")
                || lower.contains("remaining static verification problems")
                || lower.contains("task incomplete");
    }

    private static String renderVerifiedFollowUpSummary(String previousAssistantText) {
        String excerpt = verifiedOutcomeExcerpt(previousAssistantText);
        String lower = excerpt.toLowerCase(Locale.ROOT);
        String status;
        if (lower.contains("partial verification") || lower.contains("the turn remains partial")) {
            status = "Partially. The task remains partial: some files changed, but the previous verified outcome says it is not complete (not verified complete).";
        } else if (lower.contains("task incomplete") || lower.contains("static verification failed")) {
            status = "No. The previous verified outcome says the task is not complete.";
        } else if (lower.contains("static verification: passed")) {
            status = "Yes. Static verification passed in the previous outcome.";
        } else {
            status = "The previous turn included a verified outcome.";
        }
        String details = verifiedOutcomeDetails(excerpt);
        return details.isBlank() ? status : status + "\n\n" + details;
    }

    private static String verifiedOutcomeExcerpt(String previousAssistantText) {
        if (previousAssistantText == null || previousAssistantText.isBlank()) return "";
        List<String> lines = new ArrayList<>();
        for (String rawLine : previousAssistantText.strip().lines().toList()) {
            String line = rawLine.strip();
            if (line.isBlank() || isPriorVerifiedSummaryLine(line)) continue;
            lines.add(rawLine);
        }
        String excerpt = String.join("\n", lines).strip();
        if (excerpt.length() > 1500) {
            return excerpt.substring(0, 1500) + "\n\n[summary truncated]";
        }
        return excerpt;
    }

    private static boolean isPriorVerifiedSummaryLine(String line) {
        if (line == null || line.isBlank()) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("the previous verified result says")
                || lower.startsWith("partially. some files changed")
                || lower.startsWith("no. the previous verified outcome says")
                || lower.startsWith("yes. static verification passed")
                || lower.equals("verified details:");
    }

    private static String verifiedOutcomeDetails(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) return "";
        List<String> details = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rawLine : excerpt.lines().toList()) {
            String line = rawLine.strip();
            if (line.isBlank() || isPriorVerifiedSummaryLine(line)) continue;
            if (!isVerifiedDetailLine(line)) continue;
            if (seen.add(line)) details.add(line);
            if (details.size() >= 12) break;
        }
        if (details.isEmpty()) return "";
        return "Verified details:\n" + String.join("\n", details);
    }

    private static boolean isVerifiedDetailLine(String line) {
        if (line == null || line.isBlank()) return false;
        return line.equals("Succeeded:")
                || line.equals("Failed:")
                || line.equals("Remaining static verification problems:")
                || line.startsWith("- ");
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
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses,
            Options opts
    ) {
        return shapeAnswerAfterToolLoop(
                answer, messages, plan, loopResult, workspace, extraMutationSuccesses, false, opts);
    }

    private static String shapeAnswerAfterToolLoop(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses,
            boolean failedActionObligation,
            Options opts
    ) {
        String directoryListingAnswer = directoryListingAnswerIfApplicable(messages, plan, loopResult);
        if (!directoryListingAnswer.isBlank()) {
            return sanitizeAndTruncate(directoryListingAnswer, opts);
        }
        String verifyOnlyPathAnswer = verifyOnlyPathCheckAnswerIfApplicable(messages, plan, loopResult);
        if (!verifyOnlyPathAnswer.isBlank()) {
            return sanitizeAndTruncate(verifyOnlyPathAnswer, opts);
        }
        String readTargetAnswer = readTargetAnswerIfApplicable(answer, messages, plan, loopResult);
        if (!readTargetAnswer.isBlank()) {
            return sanitizeAndTruncate(readTargetAnswer, opts);
        }
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, plan, messages, loopResult, workspace,
                extraMutationSuccesses, failedActionObligation);
        String finalAnswer = groundedReadOnlyProposalAnswerIfNeeded(
                outcome.finalAnswer(), messages, plan, loopResult);
        return sanitizeAndTruncate(finalAnswer, opts);
    }

    static String groundedReadOnlyProposalAnswerIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        // T762: the grounding postcondition lives in
        // runtime.outcome.ReadOnlyProposalGroundingGuard (policy ownership
        // doctrine - the executor orchestrates, it does not own marker sets).
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, null);
        return dev.talos.runtime.outcome.ReadOnlyProposalGroundingGuard.apply(
                answer,
                safePlan.taskContract(),
                latestUserRequest(safePlan, messages),
                loopResult);
    }
    private static String directoryListingAnswerIfApplicable(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        TaskContract contract = safePlanFromMessages(plan, messages, null).taskContract();
        if (contract.type() != TaskType.DIRECTORY_LISTING || loopResult == null) return "";
        if (loopResult.toolNames().stream().anyMatch(AssistantTurnExecutor::isContentInspectionTool)) {
            return "";
        }
        String body = DirectoryListingEvidence.selectedBody(
                loopResult.messages(),
                loopResult.toolOutcomes(),
                contract.originalUserRequest());
        if (body.isBlank() || body.contains("[error]")) return "";
        List<String> entries = body.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("[verification_status:"))
                .filter(line -> !line.startsWith("[/tool_result]"))
                .limit(200)
                .toList();
        if (entries.isEmpty()) return "";
        return "Directory entries:\n- " + String.join("\n- ", entries);
    }

    private static String verifyOnlyPathCheckAnswerIfApplicable(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        TaskContract contract = safePlanFromMessages(plan, messages, null).taskContract();
        if (contract.type() != TaskType.VERIFY_ONLY || loopResult == null) return "";
        if (!looksLikeVerifyOnlyPathCheckRequest(contract.originalUserRequest())) return "";
        if (loopResult.toolOutcomes() == null || loopResult.toolOutcomes().isEmpty()) return "";
        if (loopResult.toolOutcomes().stream().anyMatch(ToolCallLoop.ToolOutcome::mutating)) return "";
        boolean hasDirectoryEvidence = loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome != null
                        && outcome.success()
                        && "talos.list_dir".equals(canonicalToolName(outcome.toolName())));
        if (!hasDirectoryEvidence) return "";

        String requestLower = contract.originalUserRequest().replace('\\', '/').toLowerCase(Locale.ROOT);
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            String line = verifyOnlyPathStatusLine(outcome, requestLower);
            if (!line.isBlank()) lines.add(line);
        }
        if (lines.isEmpty()) return "";
        return "Verified paths:\n- " + String.join("\n- ", lines);
    }

    private static boolean looksLikeVerifyOnlyPathCheckRequest(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("path")
                || lower.contains("exists")
                || lower.contains("exist")
                || lower.contains("present")
                || lower.contains("/")
                || lower.contains("\\");
    }

    private static String verifyOnlyPathStatusLine(
            ToolCallLoop.ToolOutcome outcome,
            String requestLower
    ) {
        if (outcome == null || !outcome.success()) return "";
        String tool = canonicalToolName(outcome.toolName());
        String path = ToolCallSupport.normalizePath(outcome.pathHint());
        if (path.isBlank() || !requestMentionsExactPath(requestLower, path)) return "";
        if ("talos.read_file".equals(tool)) {
            return path + ": file exists and was read.";
        }
        if ("talos.list_dir".equals(tool)) {
            String summary = outcome.summary() == null ? "" : outcome.summary().strip();
            if ("(empty directory)".equalsIgnoreCase(summary)) {
                return path + ": directory exists and is empty.";
            }
            return path + ": directory exists.";
        }
        return "";
    }

    private static boolean requestMentionsExactPath(String requestLower, String path) {
        if (requestLower == null || requestLower.isBlank() || path == null || path.isBlank()) return false;
        String needle = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int index = requestLower.indexOf(needle);
        while (index >= 0) {
            int before = index - 1;
            int after = index + needle.length();
            boolean beforeBoundary = before < 0 || !isPathTokenChar(requestLower.charAt(before));
            boolean afterBoundary = after >= requestLower.length()
                    || !isPathTokenChar(requestLower.charAt(after))
                    || isSentenceEndingDot(requestLower, after);
            if (beforeBoundary && afterBoundary) return true;
            index = requestLower.indexOf(needle, index + 1);
        }
        return false;
    }

    private static boolean isSentenceEndingDot(String value, int index) {
        if (value == null || index < 0 || index >= value.length() || value.charAt(index) != '.') {
            return false;
        }
        int next = index + 1;
        return next >= value.length() || Character.isWhitespace(value.charAt(next));
    }

    private static boolean isPathTokenChar(char c) {
        return Character.isLetterOrDigit(c)
                || c == '_'
                || c == '-'
                || c == '.'
                || c == '/'
                || c == '\\';
    }

    private static String readTargetAnswerIfApplicable(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        TaskContract contract = safePlanFromMessages(plan, messages, null).taskContract();
        if (contract.type() != TaskType.READ_ONLY_QA || contract.expectedTargets().size() != 1) return "";
        if (loopResult == null || loopResult.toolOutcomes() == null) return "";
        String target = contract.expectedTargets().iterator().next();
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        boolean targetRead = loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> "talos.read_file".equals(canonicalToolName(outcome.toolName()))
                        && outcome.success()
                        && normalizedTarget.equals(ToolCallSupport.normalizePath(outcome.pathHint())));
        if (!targetRead) return "";
        String body = latestToolResultBodyByCanonical(loopResult.messages(), "talos.read_file");
        if (body.isBlank()) return "";
        String userRequest = latestUserRequest(safePlanFromMessages(plan, messages, null), messages);
        boolean fallbackNeeded = needsReadTargetFallback(answer, userRequest);
        String directAnswer = deterministicDirectReadTargetAnswer(userRequest, target, body);
        if (!directAnswer.isBlank()) {
            Boolean modelConclusion = yesNoConclusion(answer);
            Boolean literalConclusion = directAnswer.startsWith("Yes.");
            if (fallbackNeeded || (modelConclusion != null && !modelConclusion.equals(literalConclusion))) {
                return directAnswer;
            }
        }
        if (!fallbackNeeded) return "";
        return directAnswer.isBlank() ? "Read " + target + ":\n" + body : directAnswer;
    }

    private static boolean needsReadTargetFallback(String answer, String userRequest) {
        if (answer == null || answer.isBlank()) return true;
        String lower = answer.toLowerCase(Locale.ROOT);
        return answer.contains("<function-name>")
                || answer.contains("<args-json-object>")
                || answer.contains(UiChrome.TOOL_CALL_LIMIT_PREFIX + ".")
                || answer.contains("You already gathered this information")
                || lower.contains("i cannot answer")
                || obviousReadOnlyNonAnswer(lower)
                || (isDirectYesNoEvidenceQuestion(userRequest) && !answerContainsYesNoConclusion(lower))
                || ToolCallParser.looksLikeMalformedProtocolArrayDebris(answer)
                || ToolCallParser.looksLikeMalformedToolProtocol(answer);
    }

    private static boolean obviousReadOnlyNonAnswer(String lowerAnswer) {
        if (lowerAnswer == null || lowerAnswer.isBlank()) return true;
        boolean apology = lowerAnswer.contains("i apologize")
                || lowerAnswer.contains("sorry for the confusion")
                || lowerAnswer.contains("apologies");
        boolean taskRestatement = lowerAnswer.contains("let's proceed")
                || lowerAnswer.contains("as originally requested")
                || lowerAnswer.contains("proceed with the task")
                || lowerAnswer.contains("how can i assist")
                || lowerAnswer.contains("what would you like me to do");
        return apology && taskRestatement;
    }

    private static boolean isDirectYesNoEvidenceQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).strip();
        boolean yesNoLead = lower.startsWith("does ")
                || lower.startsWith("do ")
                || lower.startsWith("did ")
                || lower.startsWith("is ")
                || lower.startsWith("are ")
                || lower.startsWith("was ")
                || lower.startsWith("were ")
                || lower.startsWith("can ")
                || lower.startsWith("could ")
                || lower.contains(" tell me if ")
                || lower.startsWith("tell me if ");
        boolean evidenceVerb = lower.contains(" mention")
                || lower.contains(" mentions")
                || lower.contains(" contain")
                || lower.contains(" contains")
                || lower.contains(" include")
                || lower.contains(" includes")
                || lower.contains(" reference")
                || lower.contains(" references");
        return yesNoLead && evidenceVerb;
    }

    private static boolean answerContainsYesNoConclusion(String lowerAnswer) {
        if (lowerAnswer == null || lowerAnswer.isBlank()) return false;
        String lower = lowerAnswer.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("yes")
                || lower.startsWith("no")
                || lower.contains("\nyes")
                || lower.contains("\nno")
                || lower.contains(" does not ")
                || lower.contains(" doesn't ")
                || lower.contains(" do not ")
                || lower.contains(" don't ")
                || lower.contains(" is not ")
                || lower.contains(" isn't ")
                || lower.contains(" are not ")
                || lower.contains(" aren't ");
    }

    private static Boolean yesNoConclusion(String answer) {
        if (answer == null || answer.isBlank()) return null;
        String lower = answer.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("yes")) return true;
        if (lower.startsWith("no")) return false;
        if (lower.contains(" does not ")
                || lower.contains(" doesn't ")
                || lower.contains(" do not ")
                || lower.contains(" don't ")
                || lower.contains(" is not ")
                || lower.contains(" isn't ")
                || lower.contains(" are not ")
                || lower.contains(" aren't ")) {
            return false;
        }
        return null;
    }

    private static String deterministicDirectReadTargetAnswer(
            String userRequest,
            String target,
            String body
    ) {
        if (!isDirectYesNoEvidenceQuestion(userRequest) || body == null || body.isBlank()) return "";
        String term = directEvidenceSearchTerm(userRequest);
        if (term.isBlank()) return "";
        boolean present = normalizedEvidenceText(body).contains(normalizedEvidenceText(term));
        String quotedTerm = "\"" + term + "\"";
        return (present ? "Yes. " : "No. ")
                + target
                + (present ? " mentions " : " does not mention ")
                + quotedTerm
                + " in the inspected content.";
    }

    private static String directEvidenceSearchTerm(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return "";
        var matcher = Pattern.compile(
                "(?i)\\b(?:mention|mentions|contain|contains|include|includes|reference|references)\\s+"
                        + "(?:the\\s+|a\\s+|an\\s+)?(.+?)(?:[?.!]|$)")
                .matcher(userRequest.strip());
        if (!matcher.find()) return "";
        String term = matcher.group(1) == null ? "" : matcher.group(1).strip();
        term = term.replaceAll("(?i)\\s+(?:in|inside|from)\\s+`?[A-Za-z0-9_.\\\\/-]+`?$", "").strip();
        return term;
    }

    private static String normalizedEvidenceText(String value) {
        if (value == null || value.isBlank()) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean isContentInspectionTool(String toolName) {
        return "talos.read_file".equals(toolName)
                || "talos.grep".equals(toolName)
                || "talos.retrieve".equals(toolName);
    }

    private static String latestToolResultBody(List<ChatMessage> messages, String toolName) {
        if (messages == null || messages.isEmpty()) return "";
        String prefix = "[tool_result: " + toolName + "]";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            if (!content.startsWith(prefix)) continue;
            int start = content.indexOf('\n');
            if (start < 0) return "";
            int end = content.lastIndexOf("\n[/tool_result]");
            if (end < 0) end = content.length();
            String body = content.substring(start + 1, end).strip();
            if (body.contains("[error]")
                    || body.startsWith("You already gathered this information")) {
                continue;
            }
            return body;
        }
        return "";
    }

    private static String latestToolResultBodyByCanonical(List<ChatMessage> messages, String canonicalToolName) {
        if (messages == null || messages.isEmpty() || canonicalToolName == null || canonicalToolName.isBlank()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            int prefixStart = content.indexOf("[tool_result:");
            if (prefixStart < 0) continue;
            int prefixEnd = content.indexOf(']', prefixStart);
            if (prefixEnd < 0) continue;
            String rawToolName = content.substring(prefixStart + "[tool_result:".length(), prefixEnd).strip();
            if (!canonicalToolName.equals(canonicalToolName(rawToolName))) continue;
            String body = content.substring(prefixEnd + 1).strip();
            int end = body.indexOf("[/tool_result]");
            if (end >= 0) {
                body = body.substring(0, end).strip();
            }
            if (body.contains("[error]")
                    || body.contains("You already gathered this information")) {
                continue;
            }
            return body;
        }
        return "";
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static void emitMalformedProtocolReplacementIfNeeded(
            String rawAnswer,
            String shapedAnswer,
            Context ctx
    ) {
        if (!ToolCallParser.looksLikeMalformedProtocolArrayDebris(rawAnswer)
                && !ToolCallParser.looksLikeMalformedToolProtocol(rawAnswer)) return;
        if (ctx == null) return;
        if (!(ctx.streamSink() instanceof ToolCallStreamFilter filter)) return;
        if (shapedAnswer == null || shapedAnswer.isBlank()) return;
        filter.accept(shapedAnswer);
        filter.flush();
    }

    private static void emitStreamingNoToolCorrectionIfNeeded(
            String rawAnswer,
            String shapedAnswer,
            Context ctx
    ) {
        String correction = visibleStreamingNoToolCorrection(rawAnswer, shapedAnswer);
        if (correction.isBlank()) return;
        if (ctx == null || ctx.streamSink() == null) return;
        ctx.streamSink().accept("\n\n" + correction);
        if (ctx.streamSink() instanceof ToolCallStreamFilter filter) {
            filter.flush();
        }
    }

    static String visibleStreamingNoToolCorrection(
            String rawAnswer,
            String shapedAnswer
    ) {
        if (rawAnswer == null || shapedAnswer == null || shapedAnswer.isBlank()) return "";
        if (shapedAnswer.equals(rawAnswer)) return "";
        if (shapedAnswer.equals(LOCAL_ACCESS_CAPABILITY_CORRECTION)) {
            return LOCAL_ACCESS_CAPABILITY_CORRECTION;
        }
        return "";
    }

    private static String shapeAnswerWithoutTools(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Context ctx,
            boolean streamed,
            Options opts
    ) {
        return shapeAnswerWithoutTools(answer, messages, plan, ctx, streamed, false, opts);
    }

    private static String shapeAnswerWithoutTools(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Context ctx,
            boolean streamed,
            boolean failedActionObligation,
            Options opts
    ) {
        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                answer, plan, messages, ctx, streamed, failedActionObligation);
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

    // ── Post-tool answer acceptance gate ─────────────────────────────────

    /**
     * Detect if the model's answer is a deflection (generic assistant boilerplate)
     * instead of a substantive response to the user's question.
     *
     * <p>Two-tier heuristic:
     * <ol>
     *   <li><b>Short deflection</b> (≤ 500 chars): any post-tool deflection marker match.</li>
     *   <li><b>Capability-recitation</b> (≤ 1500 chars): answer contains a
     *       post-tool capability marker phrase AND ends with a deflection marker.
     *       This catches the longer "here's what I can do… How can I help?" pattern
     *       without flagging genuinely substantive answers that happen to mention a capability.</li>
     * </ol>
     *
     * <p>Answers over 1500 chars always pass — they are long enough to be substantive.
     */
    static boolean isDeflection(String answer) {
        return PostToolSynthesisRetry.isDeflection(answer);
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
        return PostToolSynthesisRetry.synthesizeIfNeeded(
                answer,
                toolsInvoked,
                messages,
                retryMessages -> chatFull(ctx, retryMessages));
    }

    // ── Claim-vs-action truth layer ──────────────────────────────────────

    public static final String FALSE_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.FALSE_MUTATION_ANNOTATION;
    public static final String PARTIAL_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.PARTIAL_MUTATION_ANNOTATION;
    public static final String DENIED_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.DENIED_MUTATION_ANNOTATION;
    public static final String POLICY_DENIED_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.POLICY_DENIED_MUTATION_ANNOTATION;
    public static final String MIXED_DENIED_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.MIXED_DENIED_MUTATION_ANNOTATION;
    public static final String INVALID_MUTATION_ANNOTATION =
            MutationFailureAnswerRenderer.INVALID_MUTATION_ANNOTATION;

    static boolean containsMutationClaim(String answer) {
        return MutationFailureAnswerRenderer.containsMutationClaim(answer);
    }

    static String annotateIfFalseMutationClaim(String answer, ToolCallLoop.LoopResult loopResult) {
        return MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(answer, loopResult);
    }

    static String annotateIfFalseMutationClaim(String answer,
                                               ToolCallLoop.LoopResult loopResult,
                                               int extraMutationSuccesses) {
        return MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(
                answer, loopResult, extraMutationSuccesses);
    }

    static String summarizePartialMutationOutcomesIfNeeded(String answer,
                                                            ToolCallLoop.LoopResult loopResult,
                                                            int extraMutationSuccesses) {
        return MutationFailureAnswerRenderer.summarizePartialMutationOutcomesIfNeeded(
                answer, loopResult, extraMutationSuccesses);
    }

    static String summarizeDeniedMutationOutcomesIfNeeded(String answer,
                                                          List<ChatMessage> messages,
                                                          ToolCallLoop.LoopResult loopResult,
                                                          int extraMutationSuccesses) {
        return summarizeDeniedMutationOutcomesIfNeeded(
                answer, safePlanFromMessages(null, messages, null), messages, loopResult, extraMutationSuccesses);
    }

    static String summarizeDeniedMutationOutcomesIfNeeded(String answer,
                                                          CurrentTurnPlan plan,
                                                          List<ChatMessage> messages,
                                                          ToolCallLoop.LoopResult loopResult,
                                                          int extraMutationSuccesses) {
        return MutationFailureAnswerRenderer.summarizeDeniedMutationOutcomesIfNeeded(
                answer, plan, messages, loopResult, extraMutationSuccesses);
    }

    static String summarizeDeniedProtectedReadOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        return ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(answer, loopResult);
    }

    static String summarizeReadOnlyDeniedMutationOutcomesIfNeeded(String answer,
                                                                  List<ChatMessage> messages,
                                                                  ToolCallLoop.LoopResult loopResult,
                                                                  int extraMutationSuccesses) {
        return summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                answer, safePlanFromMessages(null, messages, null), messages, loopResult, extraMutationSuccesses);
    }

    static String summarizeReadOnlyDeniedMutationOutcomesIfNeeded(String answer,
                                                                  CurrentTurnPlan plan,
                                                                  List<ChatMessage> messages,
                                                                  ToolCallLoop.LoopResult loopResult,
                                                                  int extraMutationSuccesses) {
        return MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                answer, plan, messages, loopResult, extraMutationSuccesses);
    }

    static String summarizeInvalidMutationOutcomesIfNeeded(String answer,
                                                           List<ChatMessage> messages,
                                                           ToolCallLoop.LoopResult loopResult,
                                                           int extraMutationSuccesses) {
        return summarizeInvalidMutationOutcomesIfNeeded(
                answer, safePlanFromMessages(null, messages, null), messages, loopResult, extraMutationSuccesses);
    }

    static String summarizeInvalidMutationOutcomesIfNeeded(String answer,
                                                           CurrentTurnPlan plan,
                                                           List<ChatMessage> messages,
                                                           ToolCallLoop.LoopResult loopResult,
                                                           int extraMutationSuccesses) {
        return MutationFailureAnswerRenderer.summarizeInvalidMutationOutcomesIfNeeded(
                answer, plan, messages, loopResult, extraMutationSuccesses);
    }

    // ── Point 3 — Missing-mutation retry ─────────────────────────────────

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
     * {@link MissingMutationRetry.Result#mutationsInRetry()}.
     *
     * <p>This is the symmetric counterpart to
     * {@link #annotateIfFalseMutationClaim}: that gate catches "claimed
     * but didn't do it"; this gate catches "was told to do it, never
     * tried". Together they enforce the invariant that mutation intent
     * and mutation action stay in sync.
     */
    static MissingMutationRetry.Result mutationRequestRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        return mutationRequestRetryIfNeeded(
                answer,
                messages,
                compatibilityPlanFromMessages(messages, ctx),
                loopResult,
                workspace,
                ctx);
    }

    static MissingMutationRetry.Result mutationRequestRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return MissingMutationRetry.retryIfNeeded(
                answer,
                messages,
                safePlan,
                loopResult,
                workspace,
                ctx,
                (retryMessages, retryPlan, retryToolSpecs) ->
                        chatFullEscalatedRetry(ctx, retryMessages, retryPlan, retryToolSpecs));
    }

    /**
     * T743: retries escalate instead of re-rolling - the obligation constraint
     * envelope (tool choice) stays, and temperature is pinned to zero so an
     * identical re-ask cannot diverge by sampling alone.
     */
    private static LlmClient.StreamResult chatFullEscalatedRetry(
            Context ctx,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            List<ToolSpec> requestToolSpecs
    ) {
        ChatRequestControls controls = chatControlsForTurn(
                ctx, plan, requestToolSpecsForControls(ctx, requestToolSpecs));
        SamplingControls escalated = new SamplingControls(0.0, null, null, null)
                .mergedWithFallback(controls.sampling());
        return ctx.llm().chatFull(messages, requestToolSpecs, controls.withSampling(escalated));
    }

    static ChatMessage compactStaticVerificationRepairInstructionForRetry(ChatMessage message) {
        return MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(message);
    }

    private static final Set<String> SELECTOR_MISMATCH_MARKERS = Set.of(
            "mismatches between html classes/ids and the selectors used in css or javascript",
            "mismatches between html classes/ids",
            "selectors used in css or javascript",
            "html classes/ids",
            "selector mismatch",
            "selectors used in css",
            "selectors used in javascript"
    );
    private static final Pattern STATIC_SELECTOR_SEARCH_LITERAL = Pattern.compile(
            "(?<![A-Za-z0-9_-])([.#][A-Za-z_][A-Za-z0-9_-]*)(?![A-Za-z0-9_-])");

    // ── Inspect under-completion truth layer (N3 / P4) ───────────────────

    static final int INSPECT_MIN_CHARS = InspectUnderCompletionAnswerGuard.INSPECT_MIN_CHARS;

    public static final String UNDER_INSPECTION_ANNOTATION =
            InspectUnderCompletionAnswerGuard.UNDER_INSPECTION_ANNOTATION;

    static boolean looksLikeInspectFirstRequest(String userRequest) {
        return InspectUnderCompletionAnswerGuard.looksLikeInspectFirstRequest(userRequest);
    }

    static int readOnlyToolCount(ToolCallLoop.LoopResult loopResult) {
        return InspectUnderCompletionAnswerGuard.readOnlyToolCount(loopResult);
    }

    static List<String> obviousPrimaryFiles(Path workspace) {
        return StaticTaskVerifier.obviousPrimaryFiles(workspace);
    }

    static List<String> missingPrimaryReads(Path workspace, ToolCallLoop.LoopResult loopResult) {
        return loopResult == null
                ? List.of()
                : StaticTaskVerifier.missingPrimaryReads(workspace, loopResult.readPaths());
    }

    static List<String> missingInspectReads(Path workspace, ToolCallLoop.LoopResult loopResult) {
        return InspectCompletenessRetry.missingReads(workspace, loopResult);
    }

    static InspectCompletenessRetry.Result inspectCompletenessRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        return inspectCompletenessRetryIfNeeded(
                answer,
                messages,
                compatibilityPlanFromMessages(messages, ctx),
                loopResult,
                workspace,
                ctx);
    }

    static InspectCompletenessRetry.Result inspectCompletenessRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return InspectCompletenessRetry.retryIfNeeded(
                answer,
                messages,
                safePlan,
                loopResult,
                workspace,
                ctx,
                retryMessages -> chatFull(ctx, retryMessages));
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

    static String overrideStaticSelectorSearchAnswerIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        if (answer == null) return null;
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (!loopUsedCanonicalTool(loopResult, "talos.grep")) return answer;
        String userRequest = latestUserRequest(plan, messages);
        if (!looksLikeStaticSelectorSearchRequest(userRequest)) return answer;

        String grounded = StaticTaskVerifier.renderStaticSelectorSearch(workspace, userRequest);
        return grounded == null || grounded.isBlank() ? answer : grounded;
    }

    static String overrideUnsupportedDocumentClaimsIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult) {
        return UnsupportedDocumentAnswerGuard.overrideUnsupportedDocumentClaimsIfNeeded(answer, loopResult);
    }

    static String overrideReadOnlyWebDiagnosticsIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (declaresTaskType(messages, TaskType.WORKSPACE_EXPLAIN)) return answer;
        String latestUserRequest = latestUserRequest(messages);
        if ("WORKSPACE_EXPLAIN".equals(ToolCallSupport.embeddedRetryTaskType(latestUserRequest))) return answer;
        String userRequest = ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt(latestUserRequest);
        TaskContract requestContract = TaskContractResolver.fromUserRequest(userRequest);
        if (requestContract.type() == TaskType.WORKSPACE_EXPLAIN) return answer;
        if (StaticWebImportIntent.matches(userRequest)) return answer;
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(userRequest)) return answer;
        if (!readStaticWebDiagnosticSurface(loopResult, workspace)) return answer;

        String grounded = StaticTaskVerifier.renderWebDiagnostics(workspace, loopResult.readPaths());
        return grounded == null || grounded.isBlank() ? answer : grounded;
    }

    private static boolean readStaticWebDiagnosticSurface(ToolCallLoop.LoopResult loopResult, Path workspace) {
        if (loopResult == null || loopResult.readPaths() == null || loopResult.readPaths().isEmpty()) return false;
        boolean readHtml = false;
        boolean readScript = false;
        for (String path : loopResult.readPaths()) {
            String lower = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                readHtml = true;
            }
            if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                readScript = true;
            }
        }
        if (readHtml && readScript) return true;
        if (!readHtml && !readScript) return false;
        if (!EvidenceObligationVerifier.missingLinkedScriptReadTargets(
                workspace, linkedScriptEvidenceOutcomes(loopResult)).isEmpty()) {
            return false;
        }
        return true;
    }

    private static List<ToolCallLoop.ToolOutcome> linkedScriptEvidenceOutcomes(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null) return List.of();
        if (loopResult.toolOutcomes() != null && !loopResult.toolOutcomes().isEmpty()) {
            return loopResult.toolOutcomes();
        }
        if (loopResult.readPaths() == null || loopResult.readPaths().isEmpty()) return List.of();
        List<ToolCallLoop.ToolOutcome> outcomes = new ArrayList<>();
        for (String path : loopResult.readPaths()) {
            String normalized = ToolCallSupport.normalizePath(path);
            if (normalized.isBlank()) continue;
            outcomes.add(new ToolCallLoop.ToolOutcome(
                    "talos.read_file", normalized, true, false, false, "", ""));
        }
        return List.copyOf(outcomes);
    }

    static String overrideStaticWebImportAnswerIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        return overrideStaticWebImportAnswerIfNeeded(answer, null, messages, loopResult, workspace);
    }

    static String overrideStaticWebImportAnswerIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        String userRequest = latestUserRequest(plan, messages);
        if (!StaticWebImportIntent.matches(userRequest)) return answer;

        String grounded = StaticTaskVerifier.renderScriptImportInspection(workspace, userRequest);
        return grounded == null || grounded.isBlank() ? answer : grounded;
    }

    static boolean looksLikeReadOnlyWebDiagnosticRequest(String userRequest) {
        return WebDiagnosticIntent.matchesReadOnlyRequest(userRequest);
    }

    static boolean looksLikeSelectorMismatchRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase();
        for (String marker : SELECTOR_MISMATCH_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return lower.contains("mismatch") && lower.contains("selector");
    }

    static boolean looksLikeStaticSelectorSearchRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        if (looksLikeSelectorMismatchRequest(userRequest)) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (!lower.contains("search") || !lower.contains("selector")) return false;
        return STATIC_SELECTOR_SEARCH_LITERAL.matcher(userRequest).find();
    }

    private static boolean loopUsedCanonicalTool(ToolCallLoop.LoopResult loopResult, String canonicalToolName) {
        if (loopResult == null || loopResult.toolNames() == null) return false;
        for (String toolName : loopResult.toolNames()) {
            if (canonicalToolName.equals(canonicalToolName(toolName))) return true;
        }
        return false;
    }

    private static boolean declaresTaskType(List<ChatMessage> messages, TaskType taskType) {
        if (messages == null || taskType == null) return false;
        String marker = "Task type: " + taskType.name();
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null) continue;
            if (message.content().contains(marker)) return true;
        }
        return false;
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
     *       owned by {@link InspectUnderCompletionAnswerGuard}.</li>
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
        return InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                answer, messages, loopResult);
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
    static final int UNGROUNDED_MIN_CHARS = NoToolAnswerTruthfulnessGuard.UNGROUNDED_MIN_CHARS;

    /**
     * Phrases in the <em>user request</em> that indicate the user wants the
     * answer grounded in inspected workspace contents. Kept conservative and
     * anchored to real transcript prompt wording — we explicitly do not want
     * a bag-of-words net that sweeps up generic conversation.
     *
     * <p>Matched case-insensitively against the latest user message only.
     */
    /**
     * Annotation prepended to the original answer if the grounding retry
     * fires but the retry itself does not produce a better result. Keeps the
     * user informed without silently rewriting.
     */
    public static final String UNGROUNDED_ANNOTATION =
            NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION;

    public static final String STREAMING_NO_TOOL_MUTATION_ANNOTATION =
            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_ANNOTATION;

    public static final String STREAMING_NO_TOOL_MUTATION_REPLACEMENT =
            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT;

    public static final String MALFORMED_TOOL_PROTOCOL_REPLACEMENT =
            NoToolAnswerTruthfulnessGuard.MALFORMED_TOOL_PROTOCOL_REPLACEMENT;

    public static final String READ_ONLY_DENIED_MUTATION_REPLACEMENT =
            MutationFailureAnswerRenderer.READ_ONLY_DENIED_MUTATION_REPLACEMENT;

    public static final String LOCAL_ACCESS_CAPABILITY_CORRECTION =
            NoToolAnswerTruthfulnessGuard.LOCAL_ACCESS_CAPABILITY_CORRECTION;

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

    private static String latestUserRequest(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null
                && plan.originalUserRequest() != null
                && !plan.originalUserRequest().isBlank()) {
            return plan.originalUserRequest();
        }
        return latestUserRequest(messages);
    }

    /**
     * True iff the given user request contains at least one evidence-request
     * phrase. Conservative: matches the latest user message only; never
     * inspects the assistant's own prior output. Package-private for testing.
     */
    static boolean looksLikeEvidenceRequest(String userRequest) {
        return NoToolAnswerTruthfulnessGuard.looksLikeEvidenceRequest(userRequest);
    }

    static String correctNegativeLocalAccessClaimIfNeeded(
            String answer,
            List<ChatMessage> messages
    ) {
        return correctNegativeLocalAccessClaimIfNeeded(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static String correctNegativeLocalAccessClaimIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.correctNegativeLocalAccessClaimIfNeeded(answer, plan, messages);
    }

    static boolean shouldCorrectNegativeLocalAccessClaim(
            String answer,
            List<ChatMessage> messages
    ) {
        return shouldCorrectNegativeLocalAccessClaim(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static boolean shouldCorrectNegativeLocalAccessClaim(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.shouldCorrectNegativeLocalAccessClaim(answer, plan, messages);
    }

    static boolean containsNegativeLocalAccessClaim(String answer) {
        return NoToolAnswerTruthfulnessGuard.containsNegativeLocalAccessClaim(answer);
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
        return shouldAppendStreamingGroundingAnnotation(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static boolean shouldAppendStreamingGroundingAnnotation(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.shouldAppendStreamingGroundingAnnotation(answer, plan, messages);
    }

    static String annotateStreamingNoToolMutationClaim(String answer, List<ChatMessage> messages) {
        return annotateStreamingNoToolMutationClaim(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static String annotateStreamingNoToolMutationClaim(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.annotateStreamingNoToolMutationClaim(answer, plan, messages);
    }

    static boolean containsStreamingMutationNarrative(String answer) {
        return NoToolAnswerTruthfulnessGuard.containsStreamingMutationNarrative(answer);
    }

    static String enforceStreamingNoToolTruthfulness(String answer, List<ChatMessage> messages) {
        return enforceStreamingNoToolTruthfulness(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static String enforceStreamingNoToolTruthfulness(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(answer, plan, messages);
    }

    static boolean shouldReplaceStreamingNoToolMutationNarrative(
            String answer, List<ChatMessage> messages) {
        return shouldReplaceStreamingNoToolMutationNarrative(
                answer, safePlanFromMessages(null, messages, null), messages);
    }

    static boolean shouldReplaceStreamingNoToolMutationNarrative(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        return NoToolAnswerTruthfulnessGuard.shouldReplaceStreamingNoToolMutationNarrative(answer, plan, messages);
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
     *       evidence-request marker.</li>
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
    static String groundingRetryIfNeeded(String answer, List<ChatMessage> messages, Context ctx) {
        return groundingRetryIfNeeded(answer, safePlanFromMessages(null, messages, ctx), messages, ctx);
    }

    static String groundingRetryIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx
    ) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        return NoToolGroundingRetry.retryIfNeeded(
                answer,
                safePlan,
                messages,
                ctx,
                retryMessages -> chatFull(ctx, retryMessages));
    }
}

