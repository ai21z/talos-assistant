package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.DebugLevel;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.CapabilityAnswerPolicy;
import dev.talos.runtime.policy.CurrentTurnCapabilityFrame;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.PromptAuditSnapshot;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.WebDiagnosticIntent;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
@SuppressWarnings("resource") // Context-owned LlmClient is borrowed throughout the turn executor.
public final class AssistantTurnExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantTurnExecutor.class);

    private static final Set<String> CHANGE_SUMMARY_FOLLOW_UP_MARKERS = Set.of(
            "summarize what changed",
            "what changed",
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
        StringBuilder out = new StringBuilder();
        boolean streamed = false;
        TaskContract taskContract = TaskContractResolver.fromMessages(messages);
        initializeExecutionPhaseForTurn(taskContract, ctx);
        ctx = withNativeToolSurface(ctx, taskContract);
        CurrentTurnPlan currentTurnPlan = buildCurrentTurnPlan(taskContract, ctx);
        recordPolicyTrace(currentTurnPlan, ctx);
        injectTaskContractInstruction(messages, currentTurnPlan);
        injectStaticVerificationRepairInstruction(messages, currentTurnPlan.taskContract());
        PromptAuditSnapshot promptAudit = recordPromptAudit(currentTurnPlan, messages);
        emitPromptAuditIfEnabled(promptAudit, ctx);
        Context turnContext = ctx;
        String directAnswer = deterministicDirectAnswerIfNeeded(messages, currentTurnPlan.taskContract());
        if (directAnswer != null) {
            return directTurnOutput(directAnswer, ctx, opts);
        }
        boolean useStreaming = shouldUseStreaming(ctx, currentTurnPlan.taskContract());

        TurnTaskContractCapture.set(currentTurnPlan.taskContract());
        try {
            if (useStreaming) {
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
                            appendSummary(out, loopResult);
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
                CompletableFuture<LlmClient.StreamResult> fut = CompletableFuture.supplyAsync(
                        () -> chatFull(turnContext, messages));
                LlmClient.StreamResult streamResult = fut.get(opts.llmTimeoutMs, TimeUnit.MILLISECONDS);
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
                            appendSummary(out, loopResult);
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
        } finally {
            TurnTaskContractCapture.clear();
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

        MutationRetryResult mrr = mutationRequestRetryIfNeeded(
                answer, messages, plan, loopResult, workspace, ctx);
        answer = mrr.answer();

        InspectRetryResult irr = inspectCompletenessRetryIfNeeded(
                answer, messages, plan, loopResult, workspace, ctx);
        answer = irr.answer();

        moveToVerifyAfterSuccessfulMutation(ctx, loopResult, mrr.mutationsInRetry());

        String finalAnswer = shapeAnswerAfterToolLoop(
                answer, messages, plan, loopResult, workspace, mrr.mutationsInRetry(), opts);

        return new ToolLoopAnswerResolution(
                finalAnswer,
                joinExtraSummaries(mrr.extraSummary(), irr.extraSummary())
        );
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
            return new ToolLoopAnswerResolution(
                    shapeAnswerWithoutTools(answer, messages, plan, ctx, false, opts),
                    null);
        }
        ToolCallLoop.LoopResult noToolLoopResult = emptyNoToolLoopResult(answer, messages);
        MutationRetryResult mrr = mutationRequestRetryIfNeeded(
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
                            extraMutationSuccesses, opts),
                    mrr.extraSummary());
        }
        ReadOnlyInspectionRetryResult inspectionRetry = readOnlyInspectionRetryIfNeeded(
                mrr.answer(), messages, plan, workspace, ctx);
        if (inspectionRetry.loopResult() != null) {
            return new ToolLoopAnswerResolution(
                    shapeAnswerAfterToolLoop(
                            inspectionRetry.answer(), messages, plan, inspectionRetry.loopResult(),
                            workspace, 0, opts),
                    inspectionRetry.extraSummary());
        }
        return new ToolLoopAnswerResolution(
                shapeAnswerWithoutTools(inspectionRetry.answer(), messages, plan, ctx, false, opts),
                null);
    }

    record ReadOnlyInspectionRetryResult(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            String extraSummary
    ) {}

    static ReadOnlyInspectionRetryResult readOnlyInspectionRetryIfNeeded(
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

    static ReadOnlyInspectionRetryResult readOnlyInspectionRetryIfNeeded(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx
    ) {
        if (answer == null) answer = "";
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        TaskContract contract = safePlan.taskContract();
        if (!requiresWorkspaceEvidence(contract)) {
            return new ReadOnlyInspectionRetryResult(answer, null, null);
        }
        if (contract.mutationRequested()) {
            return new ReadOnlyInspectionRetryResult(answer, null, null);
        }
        if (ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null || workspace == null) {
            return new ReadOnlyInspectionRetryResult(answer, null, null);
        }

        String userRequest = safePlan.originalUserRequest();
        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        retryMessages.add(ChatMessage.assistant(answer.isBlank() ? "(no answer)" : answer));
        retryMessages.add(ChatMessage.user(readOnlyInspectionRetryPrompt(contract, userRequest, workspace)));

        try {
            LlmClient.StreamResult retry = chatFull(ctx, retryMessages);
            String retryText = retry.text() == null ? "" : retry.text();
            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), retryMessages, workspace, ctx);
                String mergedAnswer = retryLoop.finalAnswer();
                return new ReadOnlyInspectionRetryResult(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop,
                        retryLoop.summary());
            }
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                return new ReadOnlyInspectionRetryResult(
                        ToolCallParser.stripToolCalls(retryText), null, null);
            }
        } catch (Exception e) {
            LOG.warn("Read-only inspection retry failed: {}", e.getMessage());
        }
        return new ReadOnlyInspectionRetryResult(answer, null, null);
    }

    private static String readOnlyInspectionRetryPrompt(
            TaskContract contract,
            String userRequest,
            Path workspace
    ) {
        String type = contract == null ? "READ_ONLY_QA" : contract.type().name();
        String request = userRequest == null ? "" : userRequest.strip();
        if (request.length() > 1000) {
            request = request.substring(0, 1000) + "...";
        }
        String primaryFiles = String.join(", ", obviousPrimaryFiles(workspace));
        if (primaryFiles.isBlank()) {
            primaryFiles = "any obvious primary text files";
        }
        if (contract != null && contract.type() == TaskType.DIRECTORY_LISTING) {
            return """
                The previous answer did not inspect the local workspace, but the current task asks only for directory entries.

                Task type: DIRECTORY_LISTING
                User request: "%s"

                Use talos.list_dir on "." unless the user named another in-workspace directory. Do not inspect, search, retrieve, summarize, infer, write, or edit file contents. Answer with file and directory names only.""".formatted(request);
        }
        return """
                The previous answer did not inspect the local workspace, but the current task contract requires evidence.

                Task type: %s
                User request: "%s"

                Use read-only tools now. Start with talos.list_dir on "." for "this folder", "here", or "this workspace". Then read the obvious primary files if present: %s. Answer from observed file evidence only. If there are no readable relevant files, say that directly. Do not call write_file or edit_file.""".formatted(type, request, primaryFiles);
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

    private static CurrentTurnPlan buildCurrentTurnPlan(TaskContract taskContract, Context ctx) {
        ExecutionPhase phase = currentExecutionPhase(ctx, taskContract);
        List<String> nativeTools = ctx == null
                ? defaultVisibleToolNames(taskContract, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        return CurrentTurnPlan.create(taskContract, phase, nativeTools, nativeTools, List.of());
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

    private static boolean shouldUseStreaming(Context ctx, TaskContract taskContract) {
        if (ctx == null || ctx.streamSink() == null) return false;
        if (taskContract != null && taskContract.mutationAllowed()) return false;
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

    private static void recordPolicyTrace(TaskContract contract, Context ctx) {
        ExecutionPhase phase = currentExecutionPhase(ctx, contract);
        List<String> nativeTools = ctx == null
                ? defaultVisibleToolNames(contract, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        recordPolicyTrace(CurrentTurnPlan.compatibility(
                contract, phase, nativeTools, nativeTools, List.of()), ctx);
    }

    private static void recordPolicyTrace(CurrentTurnPlan plan, Context ctx) {
        if (ctx == null || !TurnAuditCapture.isActive()) return;
        CurrentTurnPlan safePlan = plan == null
                ? buildCurrentTurnPlan(null, ctx)
                : plan;
        TurnAuditCapture.recordPolicyTrace(TurnPolicyTrace.from(
                safePlan.taskContract(),
                safePlan.phaseInitial().name(),
                safePlan.nativeTools(),
                safePlan.promptTools()));
        LocalTurnTraceCapture.recordActionObligation(
                safePlan.actionObligation().name(),
                "SELECTED",
                "derived from task contract and execution phase");
    }

    private static PromptAuditSnapshot recordPromptAudit(
            TaskContract contract,
            Context ctx,
            List<ChatMessage> messages
    ) {
        ExecutionPhase phase = currentExecutionPhase(ctx, contract);
        List<String> nativeTools = ctx == null
                ? defaultVisibleToolNames(contract, phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        return recordPromptAudit(CurrentTurnPlan.compatibility(
                contract, phase, nativeTools, nativeTools, List.of()), messages);
    }

    private static PromptAuditSnapshot recordPromptAudit(
            CurrentTurnPlan plan,
            List<ChatMessage> messages
    ) {
        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);
        LocalTurnTraceCapture.recordPromptAudit(snapshot);
        return snapshot;
    }

    private static void emitPromptAuditIfEnabled(PromptAuditSnapshot snapshot, Context ctx) {
        if (snapshot == null || ctx == null || ctx.streamSink() == null || ctx.session() == null) return;
        if (ctx.session().getDebugLevel() != DebugLevel.PROMPT) return;
        ctx.streamSink().accept("\n" + snapshot.renderCompact() + "\n");
    }

    private static LlmClient.StreamResult chatStreamFull(Context ctx, List<ChatMessage> messages) {
        return ctx.llm().chatStreamFull(messages, ctx.streamSink(), ctx.nativeToolSpecs());
    }

    private static LlmClient.StreamResult chatFull(Context ctx, List<ChatMessage> messages) {
        return ctx.llm().chatFull(messages, ctx.nativeToolSpecs());
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = contract.mutationAllowed()
                ? ExecutionPhase.APPLY
                : ExecutionPhase.INSPECT;
        List<String> visibleTools = defaultVisibleToolNames(contract, phase);
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                contract, phase, visibleTools, visibleTools, List.of()));
    }

    public static void injectTaskContractInstruction(List<ChatMessage> messages, CurrentTurnPlan plan) {
        if (messages == null || messages.isEmpty()) return;
        if (messages.stream().anyMatch(AssistantTurnExecutor::isTaskContractInstruction)) return;

        CurrentTurnPlan safePlan = plan == null
                ? CurrentTurnPlan.compatibility(
                        TaskContractResolver.fromMessages(messages),
                        null,
                        List.of(),
                        List.of(),
                        List.of())
                : plan;
        String instruction = CurrentTurnCapabilityFrame.render(safePlan);
        injectTaskContractInstruction(messages, instruction);
    }

    public static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            TaskContract contract,
            ExecutionPhase phase,
            List<String> visibleTools
    ) {
        TaskContract safeContract = contract == null ? TaskContractResolver.fromMessages(messages) : contract;
        ExecutionPhase safePhase = phase == null
                ? (safeContract.mutationAllowed() ? ExecutionPhase.APPLY : ExecutionPhase.INSPECT)
                : phase;
        injectTaskContractInstruction(messages, CurrentTurnPlan.compatibility(
                safeContract, safePhase, visibleTools, visibleTools, List.of()));
    }

    private static void injectTaskContractInstruction(
            List<ChatMessage> messages,
            String instruction
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (messages.stream().anyMatch(AssistantTurnExecutor::isTaskContractInstruction)) return;

        int insertAt = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                insertAt = i;
                break;
            }
        }
        if (insertAt == messages.size()) {
            insertAt = 0;
            for (int i = 0; i < messages.size(); i++) {
                if ("system".equals(messages.get(i).role())) {
                    insertAt = i + 1;
                    break;
                }
            }
        }
        messages.add(insertAt, ChatMessage.system(instruction));
    }

    private static List<String> defaultVisibleToolNames(TaskContract contract, ExecutionPhase phase) {
        if (contract == null || contract.type() == TaskType.SMALL_TALK) return List.of();
        if (contract.type() == TaskType.DIRECTORY_LISTING) return List.of("talos.list_dir");
        if (contract.mutationAllowed() && phase == ExecutionPhase.APPLY) {
            return List.of(
                    "talos.edit_file",
                    "talos.grep",
                    "talos.list_dir",
                    "talos.read_file",
                    "talos.retrieve",
                    "talos.write_file");
        }
        return List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");
    }

    static void injectStaticVerificationRepairInstruction(
            List<ChatMessage> messages,
            TaskContract taskContract
    ) {
        if (messages == null || messages.isEmpty()) return;
        if (messages.stream().anyMatch(AssistantTurnExecutor::isStaticVerificationRepairInstruction)) {
            return;
        }
        RepairPolicy.planForStaticVerification(messages, taskContract)
                .plan()
                .ifPresent(plan -> {
                    String instruction = plan.instruction();
                    if (instruction.isBlank()) return;
                    LocalTurnTraceCapture.recordRepair("PLANNED", plan.traceSummary());
                    int insertAt = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        ChatMessage message = messages.get(i);
                        if ("system".equals(message.role())) {
                            insertAt = i + 1;
                            if (isTaskContractInstruction(message)) {
                                break;
                            }
                        }
                    }
                    messages.add(insertAt, ChatMessage.system(instruction));
                });
    }

    private static boolean isTaskContractInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && (message.content().startsWith("[TaskContract]")
                || message.content().startsWith("[CurrentTurnCapability]"));
    }

    private static boolean isStaticVerificationRepairInstruction(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[Static verification repair context]");
    }

    private static String deterministicDirectAnswerIfNeeded(
            List<ChatMessage> messages,
            TaskContract contract
    ) {
        String userRequest = latestUserRequest(messages);
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
        return verifiedFollowUpSummaryIfNeeded(messages, userRequest);
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
        String directoryListingAnswer = directoryListingAnswerIfApplicable(messages, plan, loopResult);
        if (!directoryListingAnswer.isBlank()) {
            return sanitizeAndTruncate(directoryListingAnswer, opts);
        }
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, messages, loopResult, workspace, extraMutationSuccesses);
        return sanitizeAndTruncate(outcome.finalAnswer(), opts);
    }

    private static String directoryListingAnswerIfApplicable(
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult
    ) {
        TaskContract contract = safePlanFromMessages(plan, messages, null).taskContract();
        if (contract.type() != TaskType.DIRECTORY_LISTING || loopResult == null) return "";
        String body = latestToolResultBody(loopResult.messages(), "talos.list_dir");
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
        // Task 4 will move ExecutionOutcome to plan-based overloads. Until then,
        // keep the existing message-based calls for compatibility.
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

    public static final String POLICY_DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because permission "
            + "policy denied or blocked the requested write.]\n\n";

    public static final String MIXED_DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because all requested "
            + "writes were denied or blocked.]\n\n";

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
                .filter(o -> !isRecoveredInvalidEditFailure(o, mutating))
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

    private static boolean isRecoveredInvalidEditFailure(
            ToolCallLoop.ToolOutcome failure,
            List<ToolCallLoop.ToolOutcome> orderedMutatingOutcomes
    ) {
        if (failure == null || orderedMutatingOutcomes == null || orderedMutatingOutcomes.isEmpty()) return false;
        if (!failure.invalidEmptyEditArguments() && !failure.fullRewriteRepairRedirect()) return false;
        String failedPath = ToolCallSupport.normalizePath(failure.pathHint());
        if (failedPath.isBlank()) return false;
        boolean sawFailure = false;
        for (ToolCallLoop.ToolOutcome outcome : orderedMutatingOutcomes) {
            if (outcome == failure) {
                sawFailure = true;
                continue;
            }
            if (!sawFailure) continue;
            if (outcome.mutating()
                    && outcome.success()
                    && failedPath.equals(ToolCallSupport.normalizePath(outcome.pathHint()))) {
                return true;
            }
        }
        return false;
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

        List<ToolCallLoop.ToolOutcome> approvalDeniedMutations = deniedMutations.stream()
                .filter(AssistantTurnExecutor::isUserApprovalDeniedOutcome)
                .toList();
        List<ToolCallLoop.ToolOutcome> policyDeniedMutations = deniedMutations.stream()
                .filter(outcome -> !isUserApprovalDeniedOutcome(outcome))
                .toList();

        StringBuilder out = new StringBuilder(deniedMutationAnnotation(
                policyDeniedMutations,
                approvalDeniedMutations));
        if (!policyDeniedMutations.isEmpty()) {
            out.append("No file changes were applied because permission policy denied or blocked:\n");
            for (ToolCallLoop.ToolOutcome outcome : policyDeniedMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(trimFailureMessage(outcome.errorMessage()))
                        .append('\n');
            }
        }
        if (!approvalDeniedMutations.isEmpty()) {
            if (!policyDeniedMutations.isEmpty()) out.append('\n');
            out.append("No file changes were applied because approval was denied for:\n");
            for (ToolCallLoop.ToolOutcome outcome : approvalDeniedMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": approval denied\n");
            }
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

    private static String deniedMutationAnnotation(List<ToolCallLoop.ToolOutcome> policyDeniedMutations,
                                                   List<ToolCallLoop.ToolOutcome> approvalDeniedMutations) {
        if (!policyDeniedMutations.isEmpty() && approvalDeniedMutations.isEmpty()) {
            return POLICY_DENIED_MUTATION_ANNOTATION;
        }
        if (!policyDeniedMutations.isEmpty()) {
            return MIXED_DENIED_MUTATION_ANNOTATION;
        }
        return DENIED_MUTATION_ANNOTATION;
    }

    private static boolean isUserApprovalDeniedOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.errorMessage() == null) return false;
        return outcome.errorMessage().startsWith("User did not approve ");
    }

    static String summarizeDeniedProtectedReadOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null) return answer;
        List<ToolCallLoop.ToolOutcome> deniedProtectedReads = loopResult.toolOutcomes().stream()
                .filter(AssistantTurnExecutor::isDeniedProtectedReadOutcome)
                .toList();
        if (deniedProtectedReads.isEmpty()) return answer;

        StringBuilder out = new StringBuilder();
        out.append("[Approval blocked: protected content was not read]\n\n")
                .append("Protected content was not read because approval was denied for:\n");
        for (ToolCallLoop.ToolOutcome outcome : deniedProtectedReads) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": approval denied\n");
        }
        out.append("\nNo protected file content was shown. ")
                .append("Approve the protected read if you want Talos to inspect it.");
        return out.toString().stripTrailing();
    }

    private static boolean isDeniedProtectedReadOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.mutating() || outcome.success() || !outcome.denied()) {
            return false;
        }
        if (!"talos.read_file".equals(outcome.toolName())) return false;
        if (!ToolError.DENIED.equals(outcome.errorCode())) return false;
        return isUserApprovalDeniedOutcome(outcome);
    }

    static String summarizeReadOnlyDeniedMutationOutcomesIfNeeded(String answer,
                                                                  List<ChatMessage> messages,
                                                                  ToolCallLoop.LoopResult loopResult,
                                                                  int extraMutationSuccesses) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;

        TaskContract contract = TaskContractResolver.fromMessages(messages);
        if (contract.mutationAllowed()) return answer;

        List<ToolCallLoop.ToolOutcome> readOnlyBlockedMutations = loopResult.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .toList();
        if (readOnlyBlockedMutations.isEmpty()) return answer;

        String cleanReadOnlyAnswer = readOnlyDeniedCleanAnswer(answer);
        if (cleanReadOnlyAnswer.isBlank()) {
            return READ_ONLY_DENIED_MUTATION_REPLACEMENT;
        }
        return READ_ONLY_DENIED_MUTATION_REPLACEMENT
                + "\n\nRead-only answer from inspected evidence:\n"
                + cleanReadOnlyAnswer;
    }

    private static String readOnlyDeniedCleanAnswer(String answer) {
        String stripped = ToolCallParser.stripToolCalls(answer == null ? "" : answer).strip();
        if (stripped.isBlank()) return "";

        List<String> kept = new ArrayList<>();
        for (String line : stripped.lines().toList()) {
            if (looksLikeFakeApprovalLine(line)) continue;
            kept.add(line);
        }
        String cleaned = String.join("\n", kept).strip();
        if (cleaned.isBlank()) return "";
        if (looksLikeOnlyMutationPreparation(cleaned)) return "";
        return cleaned;
    }

    private static boolean looksLikeFakeApprovalLine(String line) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT).strip();
        return lower.contains("do you approve these changes")
                || lower.contains("please approve these changes")
                || lower.contains("allow these changes")
                || lower.contains("would you like me to apply these changes");
    }

    private static boolean looksLikeOnlyMutationPreparation(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT).strip();
        return lower.equals("i prepared the update.")
                || lower.equals("i prepared the update")
                || lower.equals("i prepared these changes.")
                || lower.equals("i prepared these changes");
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
        String failureReason = loopResult.failureDecision() == null
                ? ""
                : loopResult.failureDecision().reason();
        if (failureReason != null && !failureReason.isBlank()) {
            out.append("\nFailure policy reason:\n- ")
                    .append(trimFailureMessage(failureReason))
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
    record MutationRetryResult(
            String answer,
            int mutationsInRetry,
            String extraSummary,
            ToolCallLoop.LoopResult retryLoopResult
    ) {
        MutationRetryResult(String answer, int mutationsInRetry, String extraSummary) {
            this(answer, mutationsInRetry, extraSummary, null);
        }
    }

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
        return mutationRequestRetryIfNeeded(
                answer,
                messages,
                compatibilityPlanFromMessages(messages, ctx),
                loopResult,
                workspace,
                ctx);
    }

    static MutationRetryResult mutationRequestRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
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

        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        String userRequest = safePlan.originalUserRequest();
        TaskContract retryContract = safePlan.taskContract();
        if (!retryContract.mutationAllowed()) {
            return new MutationRetryResult(answer, 0, null);
        }
        ActionObligation obligation = safePlan.actionObligation();
        if (!ResponseObligationVerifier.unsatisfiedNoToolResponse(obligation, answer)) {
            return new MutationRetryResult(answer, 0, null);
        }
        String priorMutationRequest = previousMutationUserRequest(messages, userRequest);

        LOG.info("Missing-mutation retry fired: user asked for a change but 0 mutating "
                + "tool calls succeeded. Re-prompting with an explicit write nudge.");

        LocalTurnTraceCapture.recordActionObligation(
                obligation.name(),
                "UNSATISFIED",
                "model response had no write/edit tool calls");
        messages.add(ChatMessage.assistant(ResponseObligationVerifier.retryFailureSummary(answer)));
        messages.add(ChatMessage.system(CurrentTurnCapabilityFrame.render(safePlan)));
        messages.add(ChatMessage.user(
                "The current-turn obligation was not satisfied: this turn has mutationAllowed=true "
                + "and visible write/edit tools, but the previous response did not call talos.write_file "
                + "or talos.edit_file. "
                + mutationRetryRequestContext(userRequest, priorMutationRequest)
                + "Call the appropriate write/edit tool NOW to perform the workspace change. "
                + "Do not say you lack filesystem or workspace access; the runtime exposes file tools "
                + "and handles approval, permissions, checkpointing, and verification. "
                + "If you truly cannot (e.g., you do not know which file, or the "
                + "content is impossible to produce), state exactly which file and why "
                + "in one sentence. Do not ask further questions — act."));

        try {
            LlmClient.StreamResult retry = chatFull(ctx, messages);
            String retryText = retry.text() == null ? "" : retry.text();

            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
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
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "SATISFIED_AFTER_RETRY",
                            "retry response issued write/edit tool calls");
                } else if (hasDeniedMutation(retryLoop)) {
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "BLOCKED_AFTER_RETRY",
                            "retry response issued mutating tool calls but policy blocked them");
                } else {
                    LocalTurnTraceCapture.recordActionObligation(
                            obligation.name(),
                            "ATTEMPTED_AFTER_RETRY",
                            "retry response issued tool calls but no mutation completed");
                }
                return new MutationRetryResult(
                        mergedAnswer == null || mergedAnswer.isBlank() ? answer : mergedAnswer,
                        retryLoop.mutatingToolSuccesses(),
                        summary,
                        retryLoop);
            }

            // No tool calls on the retry — the model declined. Keep the retry
            // text if it's non-blank (model explained why it can't), otherwise
            // fall back to the original answer.
            if (!retryText.isBlank() && !retryText.equals(answer)) {
                String stripped = ToolCallParser.stripToolCalls(retryText);
                String deterministic = ResponseObligationVerifier.deterministicNoActionAnswer();
                LocalTurnTraceCapture.recordActionObligation(
                        obligation.name(),
                        "FAILED",
                        "retry response still had no write/edit tool calls");
                return new MutationRetryResult(deterministic, 0, null);
            }
        } catch (Exception e) {
            LOG.warn("Missing-mutation retry failed: {}", e.getMessage());
        }
        LocalTurnTraceCapture.recordActionObligation(
                obligation.name(),
                "FAILED",
                "retry failed before write/edit tool calls executed");
        return new MutationRetryResult(ResponseObligationVerifier.deterministicNoActionAnswer(), 0, null);
    }

    private static String mutationRetryRequestContext(String userRequest, String priorMutationRequest) {
        if (priorMutationRequest != null && !priorMutationRequest.isBlank()
                && !Objects.equals(priorMutationRequest, userRequest)) {
            return "The current user message is a retry/repair follow-up:\n\n«"
                    + pinForRetryPrompt(userRequest)
                    + "»\n\n"
                    + "The previous mutation request to reissue is:\n\n«"
                    + pinForRetryPrompt(priorMutationRequest)
                    + "»\n\n";
        }
        return "The user's request was:\n\n«"
                + pinForRetryPrompt(userRequest)
                + "»\n\n";
    }

    private static String previousMutationUserRequest(List<ChatMessage> messages, String latestUserRequest) {
        if (messages == null || messages.isEmpty()) return null;
        boolean skippedLatest = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            if (content == null || content.isBlank()) continue;
            if (!skippedLatest && Objects.equals(content, latestUserRequest)) {
                skippedLatest = true;
                continue;
            }
            TaskContract prior = TaskContractResolver.fromUserRequest(content);
            if (prior.mutationAllowed()) {
                return content;
            }
        }
        return null;
    }

    private static String pinForRetryPrompt(String text) {
        if (text == null) return "";
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "…";
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
        return inspectCompletenessRetryIfNeeded(
                answer,
                messages,
                compatibilityPlanFromMessages(messages, ctx),
                loopResult,
                workspace,
                ctx);
    }

    static InspectRetryResult inspectCompletenessRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        if (answer == null) answer = "";
        if (loopResult == null || ctx == null || ctx.llm() == null || ctx.toolCallLoop() == null) {
            return new InspectRetryResult(answer, null);
        }
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages, ctx);
        String userRequest = safePlan.originalUserRequest();
        TaskContract contract = safePlan.taskContract();
        if (!looksLikeInspectFirstRequest(userRequest) && !requiresWorkspaceEvidence(contract)) {
            return new InspectRetryResult(answer, null);
        }
        List<String> missing = missingPrimaryReads(workspace, loopResult);
        if (missing.isEmpty()) return new InspectRetryResult(answer, null);
        if (loopResult.mutatingToolSuccesses() > 0) return new InspectRetryResult(answer, null);
        if (answer.isBlank()) return new InspectRetryResult(answer, null);

        LOG.info("Inspect-completeness retry fired: tiny workspace, inspect-first request, "
                + "missing reads for {}", missing);

        List<ChatMessage> retryMessages = new ArrayList<>(messages);
        retryMessages.add(ChatMessage.assistant(answer));
        retryMessages.add(ChatMessage.user(
                "You started diagnosing the workspace before reading all of the obvious primary files. "
                        + "Read these files now before answering: "
                        + String.join(", ", missing)
                        + ". After reading them, answer concretely from the file contents. "
                        + "Do not speculate about files that do not exist."));
        try {
            LlmClient.StreamResult retry = chatFull(ctx, retryMessages);
            String retryText = retry.text() == null ? "" : retry.text();
            if (retry.hasToolCalls() || hasAnyTextToolCalls(retryText)) {
                ToolCallLoop.LoopResult retryLoop = ctx.toolCallLoop().run(
                        retryText, retry.toolCalls(), retryMessages, workspace, ctx);
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

    static String overrideUnsupportedDocumentClaimsIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return answer;
        List<String> unsupportedPaths = unsupportedDocumentReadPaths(loopResult);
        if (unsupportedPaths.isEmpty()) return answer;

        String current = answer == null ? "" : answer;
        String cleaned = removeUnsupportedDocumentContentClaims(current, unsupportedPaths).strip();
        String note = unsupportedDocumentCapabilityNote(unsupportedPaths);
        if (cleaned.isBlank()) {
            cleaned = "Talos inspected the supported text files it could read, but it did not inspect the "
                    + "unsupported binary document contents.";
        }
        if (cleaned.startsWith(note)) return cleaned;
        return note + "\n\n" + cleaned;
    }

    private static List<String> unsupportedDocumentReadPaths(ToolCallLoop.LoopResult loopResult) {
        List<String> paths = new ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(outcome.toolName())) continue;
            if (outcome.success()) continue;
            if (!ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) continue;
            String path = outcome.pathHint();
            if (path == null || path.isBlank()) continue;
            if (!paths.contains(path)) paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static String unsupportedDocumentCapabilityNote(List<String> unsupportedPaths) {
        return "[Document capability note: Talos could not inspect unsupported binary document contents with "
                + "the current local text-tool surface: "
                + String.join(", ", unsupportedPaths)
                + ". It cannot confirm whether those files are empty or what they contain.]";
    }

    private static String removeUnsupportedDocumentContentClaims(String answer, List<String> unsupportedPaths) {
        if (answer == null || answer.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        String[] lines = answer.split("\\R", -1);
        for (String line : lines) {
            if (isUnsupportedDocumentContentClaim(line, unsupportedPaths)) {
                StringBuilder sentenceKept = new StringBuilder();
                for (String sentence : line.split("(?<=[.!?])\\s+")) {
                    if (isUnsupportedDocumentContentClaim(sentence, unsupportedPaths)) continue;
                    if (!sentence.isBlank()) {
                        if (sentenceKept.length() > 0) sentenceKept.append(' ');
                        sentenceKept.append(sentence.strip());
                    }
                }
                if (sentenceKept.length() > 0) {
                    kept.append(sentenceKept).append('\n');
                }
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static boolean isUnsupportedDocumentContentClaim(String line, List<String> unsupportedPaths) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        boolean mentionsUnsupported = lower.contains("these files")
                || lower.contains("binary files")
                || lower.contains("document files");
        for (String path : unsupportedPaths) {
            if (path != null && !path.isBlank() && lower.contains(path.toLowerCase(Locale.ROOT))) {
                mentionsUnsupported = true;
                break;
            }
            String extension = extensionOf(path);
            if (!extension.isBlank() && lower.contains("." + extension)) {
                mentionsUnsupported = true;
                break;
            }
        }
        if (!mentionsUnsupported) return false;
        return lower.contains("no extractable text")
                || lower.contains("no readable text")
                || lower.contains("do not contain any")
                || lower.contains("does not contain any")
                || lower.contains("are empty")
                || lower.contains("is empty")
                || lower.contains("no content")
                || lower.contains("nothing to extract");
    }

    private static String extensionOf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String overrideReadOnlyWebDiagnosticsIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (declaresTaskType(messages, TaskType.WORKSPACE_EXPLAIN)) return answer;
        String userRequest = latestUserRequest(messages);
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(userRequest)) return answer;

        String grounded = StaticTaskVerifier.renderWebDiagnostics(workspace);
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

    public static final String MALFORMED_TOOL_PROTOCOL_REPLACEMENT =
            "[Truth check: the model produced an invalid tool-call payload, so no action was taken.]\n\n"
            + "No file changes were applied. Please retry the request.";

    public static final String READ_ONLY_DENIED_MUTATION_REPLACEMENT =
            "[Truth check: no file was changed in this turn. The model attempted "
            + "to call mutating tools, but this turn was classified as read-only, "
            + "so those calls were blocked.]\n\n"
            + "No file changes were applied. Ask explicitly to edit, update, or "
            + "create files if you want Talos to modify the workspace.";

    public static final String LOCAL_ACCESS_CAPABILITY_CORRECTION =
            "[Capability correction: Talos can inspect files in the current workspace "
            + "with local read tools, but no file tool was called in this turn.]\n\n"
            + "I can read, list, and search files in this workspace when the task calls "
            + "for it. I did not inspect files in this turn, so I cannot give an "
            + "evidence-backed workspace answer yet.";

    private static final Set<String> NEGATIVE_LOCAL_ACCESS_MARKERS = Set.of(
            "don't have direct access to your local workspace",
            "do not have direct access to your local workspace",
            "don't have direct access to your local files",
            "do not have direct access to your local files",
            "can't browse your local files",
            "cannot browse your local files",
            "can't access your local files",
            "cannot access your local files",
            "can't inspect your local files",
            "cannot inspect your local files",
            "can't read your files",
            "cannot read your files",
            "if you provide the file contents",
            "if you provide specific details or content from the files"
    );

    private static final Set<String> LOCAL_WORKSPACE_TURN_MARKERS = Set.of(
            "workspace",
            "folder",
            "directory",
            "file",
            "files",
            "project",
            "repo",
            "repository",
            "here",
            "this"
    );

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

    static String correctNegativeLocalAccessClaimIfNeeded(
            String answer,
            List<ChatMessage> messages
    ) {
        if (!shouldCorrectNegativeLocalAccessClaim(answer, messages)) return answer;
        return LOCAL_ACCESS_CAPABILITY_CORRECTION;
    }

    static boolean shouldCorrectNegativeLocalAccessClaim(
            String answer,
            List<ChatMessage> messages
    ) {
        if (!containsNegativeLocalAccessClaim(answer)) return false;
        return looksLikeLocalWorkspaceTurn(messages, answer);
    }

    static boolean containsNegativeLocalAccessClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String marker : NEGATIVE_LOCAL_ACCESS_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    private static boolean looksLikeLocalWorkspaceTurn(
            List<ChatMessage> messages,
            String answer
    ) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        if (contract.mutationRequested()) return false;

        TaskType type = contract.type();
        if (type == TaskType.DIRECTORY_LISTING
                || type == TaskType.WORKSPACE_EXPLAIN
                || type == TaskType.DIAGNOSE_ONLY
                || type == TaskType.VERIFY_ONLY) {
            return true;
        }

        String userRequest = latestUserRequest(messages);
        if (containsLocalWorkspaceMarker(userRequest)) return true;
        return containsLocalWorkspaceMarker(answer) && type != TaskType.SMALL_TALK;
    }

    private static boolean containsLocalWorkspaceMarker(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : LOCAL_WORKSPACE_TURN_MARKERS) {
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

