package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.UiChrome;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.outcome.ProtectedReadAnswerGuard;
import dev.talos.runtime.outcome.RuntimeVerificationStatusAnswer;
import dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.CapabilityAnswerPolicy;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.ConversationBoundaryPolicy;
import dev.talos.runtime.policy.EvidenceGate;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy;
import dev.talos.runtime.policy.SessionActionAnswerPolicy;
import dev.talos.runtime.policy.WorkStatusAnswerPolicy;
import dev.talos.runtime.policy.ProjectHandoffPolicy;
import dev.talos.runtime.policy.ProjectHandoffSourceAccess;
import dev.talos.runtime.WorkStatusReceipt;
import dev.talos.runtime.intent.WorkStatusRequest;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.trace.TaskOutcomeTraceRecorder;
import dev.talos.runtime.policy.UnsupportedDocumentMutationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.HistoricalFollowUpRequest;
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
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment;
import dev.talos.runtime.verification.StaticWebImportIntent;
import dev.talos.runtime.verification.WebDiagnosticIntent;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.safety.ContentRestrictionScope;
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
     * is intentionally NOT included here. Code-block writes are disabled - they only
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
        private UnaryOperator<String> beforeClip = UnaryOperator.identity();
        private CapabilityPosture capabilityPosture = CapabilityPosture.AGENT;
        private ModelIdentitySnapshot runtimeIdentity;

        public Options runtimeIdentity(ModelIdentitySnapshot identity) {
            this.runtimeIdentity = Objects.requireNonNull(identity);
            return this;
        }

        public Options llmTimeoutMs(long ms)         { this.llmTimeoutMs = ms; return this; }
        public Options responseMaxChars(long chars)   { this.responseMaxChars = chars; return this; }
        public CapabilityPosture capabilityPosture()  { return capabilityPosture; }
        public Options capabilityPosture(CapabilityPosture posture) {
            this.capabilityPosture = posture == null ? CapabilityPosture.AGENT : posture;
            return this;
        }

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
        try (PromptDebugCapture.TurnScope ignored = PromptDebugCapture.openTurnIfAbsent()) {
            return executeWithinPromptDebugScope(messages, workspace, ctx, presentationOptions(opts, ctx));
        } finally {
            if (ctx != null && ctx.streamSink() instanceof ToolCallStreamFilter filter) {
                filter.reset();
            }
        }
    }

    private static Options presentationOptions(Options supplied, Context ctx) {
        Options source = supplied == null ? new Options() : supplied;
        Options scoped = new Options();
        scoped.llmTimeoutMs = source.llmTimeoutMs;
        scoped.responseMaxChars = source.responseMaxChars;
        scoped.capabilityPosture = source.capabilityPosture;
        scoped.runtimeIdentity = source.runtimeIdentity;
        var redactor = ctx == null || ctx.redactor() == null
                ? new dev.talos.core.security.Redactor() : ctx.redactor();
        scoped.answerSanitizer = source.answerSanitizer;
        scoped.beforeClip = value -> {
            String selected = ToolCallStreamFilter.projectComplete(value);
            String sanitized = dev.talos.core.llm.ProviderInlineHtmlLiteralProjection.preserveIfInert(
                    selected, dev.talos.core.util.Sanitize.sanitizeForOutput(selected));
            return dev.talos.runtime.TurnAuditCapture.projectPublicAnswer(sanitized, redactor);
        };
        return scoped;
    }

    private static TurnOutput executeWithinPromptDebugScope(
            List<ChatMessage> messages,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.ASSISTANT_PREPARATION);
        dev.talos.runtime.TurnAuditCapture.setAnswerPresentationLimit(opts.responseMaxChars);
        ModelIdentitySnapshot runtimeIdentity = opts != null && opts.runtimeIdentity != null
                ? opts.runtimeIdentity
                : ctx == null || ctx.llm() == null ? ModelIdentitySnapshot.empty() : ctx.llm().modelIdentity();
        recordModelIdentityDiagnostics(runtimeIdentity);
        String identityRequest = latestUserRequest(messages);
        boolean composeRuntimeIdentity = RuntimeModelIdentityAnswerPolicy.requestsIdentityComponent(identityRequest)
                && !RuntimeModelIdentityAnswerPolicy.isIdentityOnlyRequest(identityRequest);
        StringBuilder out = new StringBuilder();
        WorkspaceBoundaryPreflight workspaceBoundaryPreflight =
                workspaceBoundaryPreflight(messages, workspace, ctx);
        if (ctx != null && ctx.memory() != null
                && (workspaceBoundaryPreflight.directAnswer() != null
                    || workspaceBoundaryPreflight.effectiveUserRequest() != null)) {
            ctx.memory().setPendingContentSave(null);
            ctx.memory().setPendingContentDraft(null);
        }
        if (workspaceBoundaryPreflight.directAnswer() != null) {
            return directTurnOutput(workspaceBoundaryPreflight.directAnswer(), ctx, opts,
                    runtimeIdentity, composeRuntimeIdentity);
        }
        boolean workspaceBoundaryReplayedRequest = workspaceBoundaryPreflight.effectiveUserRequest() != null;
        if (workspaceBoundaryPreflight.effectiveUserRequest() != null) {
            messages = replaceLatestUserRequest(messages, workspaceBoundaryPreflight.effectiveUserRequest());
        }
        RuntimeModelIdentityAnswerPolicy.project(messages, runtimeIdentity);
        AssistantTurnPreparation.PreparedTurn preparedTurn = AssistantTurnPreparation.prepare(
                messages,
                workspace,
                ctx,
                workspaceBoundaryReplayedRequest,
                opts == null ? CapabilityPosture.AGENT : opts.capabilityPosture());
        ctx = preparedTurn.ctx();
        CurrentTurnPlan currentTurnPlan = preparedTurn.plan();
        var currentTaskResolution = preparedTurn.taskResolution();
        if (ctx.requestFrame() != null && ctx.requestFrame().controlled()) {
            ctx.requestFrame().requireCurrent();
            if (ctx.requestFrame().state() != dev.talos.runtime.observation.TurnRequestFrame.State.ADOPTED)
                return directTurnOutput("The scope remains unresolved. Please clarify what may be inspected and what may be shown.", ctx, opts);
            if (currentTurnPlan.requestedResults().retainedWork())
                return capturedWorkStatusTurnOutput(currentTurnPlan, currentTaskResolution, messages, ctx, opts, workspace);
            if (!currentTurnPlan.requestedResults().requires(dev.talos.runtime.intent.RequestedResultInventory.Kind.DIRECTORY_CONTENTS)
                    && !currentTurnPlan.requestedResults().requires(dev.talos.runtime.intent.RequestedResultInventory.Kind.DIRECTORY_COUNT)
                    && !currentTurnPlan.requestedResults().requires(dev.talos.runtime.intent.RequestedResultInventory.Kind.DIRECTORY_EMPTINESS))
                {
                    var assessment = dev.talos.runtime.policy.WorkspaceResultEvidencePolicy
                            .assess(currentTurnPlan, null, workspace);
                    dev.talos.runtime.TurnAuditCapture.recordLocationPresentation(
                            dev.talos.runtime.RuntimeLocationPresentation.issue(assessment, ""));
                    return directTurnOutput(assessment.compose(""), ctx, opts);
                }
        }
        LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.ASSISTANT_EXECUTION);
        if (preparedTurn.draftGeneration() != null) {
            return contentDraftOutput(preparedTurn.draftGeneration(), ctx, opts);
        }
        if (preparedTurn.directAnswer() != null) {
            if (ctx.memory() != null && ctx.memory().pendingContentDraft() != null
                    && !draftPresentationIntact(preparedTurn.directAnswer(), ctx, opts)) {
                ctx.memory().setPendingContentDraft(null);
                return directTurnOutput("The complete draft could not be presented safely. No draft was retained and no write was attempted.", ctx, opts);
            }
            return directTurnOutput(preparedTurn.directAnswer(), ctx, opts,
                    runtimeIdentity, composeRuntimeIdentity);
        }
        if (currentTurnPlan.contentSaveRequest().draftSelection() != null) {
            return selectedDraftOutput(currentTaskResolution, currentTurnPlan, messages, workspace, ctx, opts);
        }
        Context turnContext = ctx;
        var workStatus = WorkStatusAnswerPolicy.requestFor(currentTurnPlan.taskContract());
        if (workStatus.isPresent()) {
            return workStatusTurnOutput(workStatus.orElseThrow(), currentTaskResolution,
                    messages, runtimeIdentity, composeRuntimeIdentity, ctx, opts, workspace);
        }
        String directAnswer = deterministicDirectAnswerIfNeeded(
                messages, currentTurnPlan.taskContract(), workspace, ctx, runtimeIdentity);
        if (directAnswer != null) {
            return directTurnOutput(directAnswer, ctx, opts, runtimeIdentity, composeRuntimeIdentity);
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
            return composeRuntimeIdentity
                    ? completeRuntimeIdentityOutput(out.toString(), runtimeIdentity, ctx, opts)
                    : completeTurnOutput(out.toString(), false);
        }
        boolean useStreaming = !composeRuntimeIdentity && shouldUseStreaming(ctx, currentTurnPlan, workspace);
        // Transport capture remains bounded. Public presentation follows settlement.

        TurnSourceEvidenceCapture.begin();
        TurnTaskContractCapture.set(currentTaskResolution, messages, runtimeIdentity, composeRuntimeIdentity);
        TurnTaskContractCapture.setRequestedResults(currentTurnPlan.requestedResults());
        try {
            if (useStreaming) {
                // ── Streaming path ──────────────────────────────────────────
                LlmClient.StreamResult streamResult =
                        TurnModelDispatcher.dispatchStreaming(withDispatchSink(ctx,
                                AssistantTurnExecutor::discardStreamChunk),
                                messages, currentTurnPlan);
                String answer = streamResult.text();
                recordOutputLimitIfNeeded(streamResult);

                completeStream(ctx);

                if (incompletePublicCandidate(streamResult)) {
                    out.append(incompleteGenerationAnswer(messages, currentTurnPlan, ctx, opts));
                } else if (answer != null) {
                    if (isAbortedGeneration(streamResult, answer)) {
                        // An interrupted partial is failure evidence, never a
                        // selected answer or an executable tool proposal.
                        recordLlmAbortOutcome(streamResult, answer);
                        throw abortedGenerationFailure(streamResult);
                    } else if (ctx.toolCallLoop() != null && hasAnyToolCalls(streamResult)) {
                        if (blocksToolCallsForContract(currentTurnPlan.taskContract())) {
                            BlockedSmallTalkAnswer blocked =
                                    answerForBlockedSmallTalkToolCalls(answer, messages, opts);
                            answer = blocked.text();
                            recordBlockedSmallTalkAnswerOrigin(
                                    blocked, streamResult.provenance());
                            out.append(answer);
                        } else {
                            LOG.debug("Tool calls detected in streamed response (native: {}), entering tool-call loop",
                                    streamResult.hasToolCalls());
                            ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                    answer, streamResult.toolCalls(), streamResult.provenance(),
                                    messages, workspace, ctx);
                            answer = loopResult.finalAnswer();
                            LOG.debug("Streaming tool-call loop complete: {} iterations, {} tools invoked",
                                    loopResult.iterations(), loopResult.toolsInvoked());
                            ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                    answer, messages, currentTurnPlan, loopResult, workspace, ctx, opts);
                            appendExtraSummary(out, resolution.extraSummary());
                            out.append(withOutputLimitNotice(resolution.answer(),
                                    resolution.outputLimitReached(), ctx, false));
                        }
                    } else {
                        // Keep streaming outcome semantics, including no silent
                        // grounding retry, even when answer emission was deferred.
                        AnswerOutputLimit.Presentation presentation = shapeAnswerWithoutToolsSelected(
                                answer, messages, currentTurnPlan, ctx, true, false, opts);
                        answer = withOutputLimitNotice(presentation.answer(),
                                presentation.outputLimit().apply(streamResult.outputLimitReached()), ctx, false);
                        out.append(answer);
                    }
                } else {
                    out.append("(no answer)");
                }
            } else {
                // ── Non-streaming fallback (tests, non-interactive) ─────────
                // Use chatFull() so native tool calls are captured too
                // (chat() returns only String, losing native tool calls).
                LlmClient.StreamResult streamResult = TurnModelDispatcher.dispatchBufferedWithTimeout(
                        turnContext,
                        messages,
                        currentTurnPlan,
                        opts.llmTimeoutMs);
                recordOutputLimitIfNeeded(streamResult);
                if (ctx.streamSink() != null && !composeRuntimeIdentity) {
                    completeStream(ctx);
                }
                String answer = streamResult.text();
                if (incompletePublicCandidate(streamResult)) {
                    out.append(incompleteGenerationAnswer(messages, currentTurnPlan, ctx, opts));
                } else if (answer != null) {
                    if (isAbortedGeneration(streamResult, answer)) {
                        recordLlmAbortOutcome(streamResult, answer);
                        throw abortedGenerationFailure(streamResult);
                    } else if (ctx.toolCallLoop() != null && hasAnyToolCalls(streamResult)) {
                        if (blocksToolCallsForContract(currentTurnPlan.taskContract())) {
                            BlockedSmallTalkAnswer blocked =
                                    answerForBlockedSmallTalkToolCalls(answer, messages, opts);
                            answer = blocked.text();
                            recordBlockedSmallTalkAnswerOrigin(
                                    blocked, streamResult.provenance());
                        } else {
                            LOG.debug("Tool calls detected in LLM response (native: {}), entering tool-call loop",
                                    streamResult.hasToolCalls());
                            ToolCallLoop.LoopResult loopResult = ctx.toolCallLoop().run(
                                    answer, streamResult.toolCalls(), streamResult.provenance(),
                                    messages, workspace, ctx);
                            answer = loopResult.finalAnswer();
                            LOG.debug("Buffered tool-call loop complete: {} iterations, {} tools invoked",
                                    loopResult.iterations(), loopResult.toolsInvoked());
                            ToolLoopAnswerResolution resolution = resolveToolLoopAnswer(
                                    answer, messages, currentTurnPlan, loopResult, workspace, ctx, opts);
                            appendExtraSummary(out, resolution.extraSummary());
                            answer = withOutputLimitNotice(resolution.answer(),
                                    resolution.outputLimitReached(), ctx, false);
                        }
                    } else {
                        // No-tool-call path. Zero tools were invoked this turn.
                        // Grounding retry gate: if the user explicitly asked for evidence
                        // / reading / inspection and the answer is long-and-confident,
                        // re-prompt once asking the model to answer from workspace evidence.
                        ToolLoopAnswerResolution resolution = resolveNoToolAnswer(
                                answer, streamResult.outputLimitReached(), messages, currentTurnPlan, workspace, ctx, opts);
                        appendExtraSummary(out, resolution.extraSummary());
                        answer = withOutputLimitNotice(resolution.answer(), resolution.outputLimitReached(), ctx, false);
                    }
                    out.append(answer);
                } else {
                    out.append("(no answer)");
                }
            }
        } catch (java.util.concurrent.CancellationException cancellation) {
            throw cancellation;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new RuntimeException(te);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw propagateGenerationFailure(cause == null ? ex : cause);
        } catch (EngineException ee) {
            throw ee;
        } catch (Exception e) {
            throw propagateGenerationFailure(e);
        } finally {
            TurnTaskContractCapture.clear();
            TurnSourceEvidenceCapture.clear();
        }

        return composeRuntimeIdentity
                ? completeRuntimeIdentityOutput(out.toString(), runtimeIdentity, ctx, opts)
                : completeTurnOutput(out.toString(), false);
    }

    private static RuntimeException propagateGenerationFailure(Throwable failure) {
        if (TurnFailureClassifier.isCancellation(failure)
                || TurnFailureClassifier.isInterruption(failure)) {
            return TurnFailureClassifier.propagateControlFlow(failure);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new RuntimeException(failure);
    }

    private static boolean incompletePublicCandidate(LlmClient.StreamResult result) {
        return dev.talos.runtime.IncompleteGenerationPresentation.required(result);
    }

    private static String incompleteGenerationAnswer(List<ChatMessage> messages, CurrentTurnPlan plan,
                                                     Context ctx, Options opts) {
        String notice = dev.talos.runtime.IncompleteGenerationPresentation.NOTICE;
        LocalTurnTraceCapture.recordOperatorAnswerSelected(notice);
        String answer = shapeAnswerWithoutToolsSelected(notice, messages, plan, ctx, true, false, opts).answer();
        dev.talos.runtime.IncompleteGenerationPresentation.record(answer);
        return answer;
    }

    private static RuntimeException abortedGenerationFailure(LlmClient.StreamResult result) {
        return dev.talos.runtime.IncompleteGenerationPresentation.abortedFailure(result);
    }

    static void recordModelIdentityDiagnostics(ModelIdentitySnapshot identity) {
        if (identity == null || !identity.available()) return;
        identity.diagnostics().forEach(PromptDebugCapture::putTurnDiagnostic);
        LocalTurnTraceCapture.recordModelIdentity(identity);
    }

    private static void recordOutputLimitIfNeeded(LlmClient.StreamResult result) {
        if (result == null || !result.outputLimitReached()) return;
        LocalTurnTraceCapture.warning(
                "LLM_OUTPUT_LIMIT_REACHED",
                "Provider reported finish_reason=length; response stopped at the configured output limit and may be incomplete.");
    }

    private static String withOutputLimitNotice(
            String answer,
            boolean outputLimitReached,
            Context ctx,
            boolean alreadyStreamed
    ) {
        String safeAnswer = answer == null ? "" : answer;
        if (!outputLimitReached) {
            return safeAnswer;
        }
        String notice = "\n\n" + UiChrome.OUTPUT_LIMIT_PREFIX
                + ": provider reported finish_reason=length; the answer may be incomplete.]";
        return safeAnswer + notice;
    }

    private static void recordBackendFailureOutcome(String classification) {
        LocalTurnTraceCapture.recordOutcome(
                "FAILED",
                "NOT_RUN",
                "UNKNOWN",
                "BACKEND_ERROR",
                classification);
    }

    static void recordLlmAbortOutcome(LlmClient.StreamResult result, String answer) {
        LocalTurnTraceCapture.recordModelGenerationAborted(
                result == null ? ProviderResponseCapture.AbortOrigin.NONE : result.abortOrigin(),
                result == null ? null : result.abandonment().orElse(null),
                hasAbortPartialOutput(answer));
        if (result != null
                && result.abortOrigin() == ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL
                && !hasAbortPartialOutput(answer)) {
            LocalTurnTraceCapture.recordOperatorAnswerSelected(answer);
        }
        recordBackendFailureOutcome("LLM_ABORTED");
    }

    private static boolean hasAbortPartialOutput(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return answer.lines()
                .map(String::strip)
                .anyMatch(line -> !line.isBlank() && !line.startsWith(UiChrome.TURN_ABORTED_PREFIX));
    }

    /**
     * Aborted-generation discriminator: the {@link LlmClient.StreamResult}
     * abort metadata is authoritative; the shared line-anchored marker scan
     * backstops results that were reassembled from plain text and lost the
     * metadata. A plain startsWith is NOT enough - a transport abort after
     * partial output appends the marker AFTER the partial text.
     */
    static boolean isAbortedGeneration(LlmClient.StreamResult result, String answer) {
        if (result != null && result.aborted()) return true;
        if (result != null && (result.hasToolCalls() || ToolProtocolText.containsToolCalls(result.text()))) {
            return false;
        }
        return UiChrome.containsTurnAbortMarker(answer);
    }

    /** Apply mode-specific sanitization then truncate if over budget. */
    private static String sanitizeAndTruncate(String answer, Options opts) {
        var presented = boundedAnswer(answer, opts);
        if (presented.clipped()) LocalTurnTraceCapture.recordAnswerPrefixPresented(presented.input(), presented.text(),
                presented.retainedInputChars(), true);
        return presented.text();
    }

    private record BoundedAnswer(String input, String text, int retainedInputChars) {
        boolean clipped() { return retainedInputChars < input.length(); }
    }

    private static BoundedAnswer boundedAnswer(String answer, Options opts) {
        answer = opts.answerSanitizer.apply(answer);
        String projected = opts.beforeClip.apply(answer);
        if (projected.length() > opts.responseMaxChars) {
            String text = projected.substring(0, (int) Math.max(0, opts.responseMaxChars)) + "\n\n[output truncated]";
            return new BoundedAnswer(projected, text, (int) opts.responseMaxChars);
        }
        return new BoundedAnswer(answer, answer, answer.length());
    }

    private static TurnOutput directTurnOutput(String answer, Context ctx, Options opts) {
        return directTurnOutput(answer, ctx, opts, ModelIdentitySnapshot.empty(), false);
    }

    private static TurnOutput workStatusTurnOutput(WorkStatusRequest request, TaskResolution resolution,
            List<ChatMessage> messages, ModelIdentitySnapshot identity, boolean composeIdentity,
            Context ctx, Options opts, Path workspace) {
        TurnTaskContractCapture.set(resolution, messages, identity, composeIdentity);
        try {
            var current = messages.stream().filter(message -> "user".equals(message.role()))
                    .reduce((earlier, later) -> later).orElse(null);
            var localDetails = java.util.Optional.<String>empty();
            if (request.runtimeOnly() && request.requiresOperationDetails() && current != null) {
                ContentRestrictionScope.restrictIfActive(current.contentRestrictions());
                localDetails = WorkStatusAnswerPolicy.renderLocalEditDetails(
                        request, ctx.memory(), workspace, current.contentRestrictions());
            }
            // A partially parsed handoff frame is answered concisely from retained records, not as a runtime ledger.
            // No provider or tool is used, and coverage stays PARTIAL.
            boolean partialHandoff = workspace != null && request.handoffFrame()
                    && request.coverage() == WorkStatusRequest.Coverage.PARTIAL;
            // The current input's restrictions still govern the turn and its retained memory.
            if (partialHandoff && current != null) ContentRestrictionScope.restrictIfActive(current.contentRestrictions());
            UnaryOperator<String> projection = handoffProjection(ctx, opts);
            String assessed = partialHandoff
                    ? WorkStatusAnswerPolicy.renderPartialHandoff(request, ctx.memory(), workspace,
                        request.unsupportedText().stream().map(text -> handoffQuestionLiteral(text.strip(), projection)).toList())
                    : localDetails.orElseGet(() -> WorkStatusAnswerPolicy.render(request, ctx.memory(), workspace));
            boolean projectHandoff = !partialHandoff && ProjectHandoffPolicy.required(request);
            if (!projectHandoff) LocalTurnTraceCapture.recordDeterministicAnswerSelected(assessed);
            ProjectHandoffPresentation handoff = projectHandoff
                    ? projectHandoffPresentation(request, messages, ctx, opts, assessed, identity, composeIdentity) : null;
            String shaped = handoff == null ? sanitizeAndTruncate(assessed, opts) : handoff.answer();
            boolean intact = handoff == null ? assessed.equals(shaped) : handoff.intact();
            if (composeIdentity && handoff == null) {
                var presentation = RuntimeModelIdentityAnswerPolicy.present(identity, shaped,
                        opts.answerSanitizer, opts.responseMaxChars);
                LocalTurnTraceCapture.recordAnswerPrefixPresented(shaped, presentation.answer(),
                        presentation.retainedInputChars(), presentation.truncated());
                shaped = presentation.answer();
                intact &= presentation.identityComplete() && !presentation.truncated();
            }
            boolean complete = intact && !projectHandoff && !request.requiresOperationDetails()
                    && request.coverage() == WorkStatusRequest.Coverage.COVERED;
            dev.talos.runtime.TurnAuditCapture.recordWorkStatus(WorkStatusReceipt.assessed(request, shaped, complete));
            var verification = TaskVerificationResult.notRun("No fresh verification was performed for retained work status.");
            var outcome = new TaskOutcome(resolution.contract(), complete
                    ? TaskCompletionStatus.READ_ONLY_ANSWERED : TaskCompletionStatus.ADVISORY_ONLY,
                    null, verification, List.of(), List.of());
            TaskOutcomeTraceRecorder.record(complete ? "COMPLETE" : "PARTIAL", "NOT_RUN", outcome, verification);
            if (!complete) LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.WORK_STATUS_INCOMPLETE);
            if (handoff != null && handoff.receipt() != null) {
                LocalTurnTraceCapture.recordProviderAnswerSelectedAfterTransformation(shaped, handoff.receipt(),
                        LocalTurnTraceCapture.AnswerTransformationReason.EXECUTOR_POLICY_SHAPING);
            } else if (handoff != null) LocalTurnTraceCapture.recordDeterministicAnswerSelected(shaped);
            if (handoff != null && handoff.clipped()) {
                LocalTurnTraceCapture.recordAnswerDeliverySelected(shaped, true, true);
            }
            completeStream(ctx);
            return completeTurnOutput(shaped, false);
        } finally {
            TurnTaskContractCapture.clear();
        }
    }

    private static TurnOutput capturedWorkStatusTurnOutput(CurrentTurnPlan plan, TaskResolution resolution,
            List<ChatMessage> messages, Context ctx, Options opts, Path workspace) {
        TurnTaskContractCapture.set(resolution, messages, ModelIdentitySnapshot.empty(), false);
        try {
            var current = messages.stream().filter(message -> "user".equals(message.role()))
                    .reduce((earlier, later) -> later).orElse(null);
            if (current != null) ContentRestrictionScope.restrictIfActive(current.contentRestrictions());
            String assessed = WorkStatusAnswerPolicy.renderCaptured(plan.requestedResults(), ctx.memory(), workspace);
            String shaped = opts.answerSanitizer.apply(assessed);
            if (shaped.length() > Math.max(0, opts.responseMaxChars)) {
                shaped = "The requested handoff did not fit. No complete handoff was presented.";
                if (shaped.length() > Math.max(0, opts.responseMaxChars)) shaped = "";
                LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                        LocalTurnTraceCapture.AnswerPresentationLimitation.OUTPUT_TRUNCATED);
            }
            dev.talos.runtime.TurnAuditCapture.recordWorkStatus(WorkStatusReceipt.captured(plan.requestedResults(), shaped));
            var verification = TaskVerificationResult.notRun("No fresh verification was performed for retained work status.");
            var outcome = new TaskOutcome(resolution.contract(), TaskCompletionStatus.ADVISORY_ONLY,
                    null, verification, List.of(), List.of());
            TaskOutcomeTraceRecorder.record("PARTIAL", "NOT_RUN", outcome, verification);
            LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.WORK_STATUS_INCOMPLETE);
            LocalTurnTraceCapture.recordDeterministicAnswerSelected(shaped);
            completeStream(ctx);
            return completeTurnOutput(shaped, false);
        } finally {
            TurnTaskContractCapture.clear();
        }
    }

    private record ProjectHandoffPresentation(String answer, ProviderResponseCapture.Receipt receipt,
                                               boolean intact, boolean clipped) {
        ProjectHandoffPresentation(String answer, ProviderResponseCapture.Receipt receipt, boolean intact) {
            this(answer, receipt, intact, false);
        }
    }

    /** The presentation projection shared by handoff paths, so request text is redacted the same way on both. */
    private static UnaryOperator<String> handoffProjection(Context ctx, Options opts) {
        return value -> {
            String sanitized = opts.answerSanitizer.apply(value);
            return ctx.redactor() == null ? new dev.talos.core.security.Redactor().redactBlock(sanitized)
                    : ctx.redactor().redactBlock(sanitized);
        };
    }

    private static ProjectHandoffPresentation projectHandoffPresentation(WorkStatusRequest request,
            List<ChatMessage> messages, Context ctx, Options opts, String runtime,
            ModelIdentitySnapshot identity, boolean composeIdentity) {
        UnaryOperator<String> projection = handoffProjection(ctx, opts);
        String minimum = composeProjectHandoff(projectHandoffGaps(request, null, projection), runtime, identity, composeIdentity, opts);
        if (!handoffFits(minimum, opts)) {
            LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.OUTPUT_TRUNCATED);
            String unavailable = "[Handoff unavailable. Required runtime facts and project gaps do not fit.]";
            int end = (int) Math.min(unavailable.length(), Math.max(0, opts.responseMaxChars));
            return new ProjectHandoffPresentation(unavailable.substring(0, end), null, false, true);
        }
        var fallback = new ProjectHandoffPresentation(minimum, null, true);
        var current = messages.stream().filter(message -> "user".equals(message.role()))
                .reduce((earlier, later) -> later).orElse(null);
        if (current != null) ContentRestrictionScope.restrictIfActive(current.contentRestrictions());
        if (request.coverage() != WorkStatusRequest.Coverage.COVERED || current == null
                || !current.contentRestrictions().modelAllowed() || !ContentRestrictionScope.current().modelAllowed()
                || ctx.llm() == null)
            return fallback;
        var priorStage = LocalTurnTraceCapture.currentExecutionStage();
        try {
            var policy = ProjectHandoffPolicy.capture(
                    ctx.memory() == null ? List.of() : ctx.memory().getTurns(), request, current.contentRestrictions());
            if (policy.entries().isEmpty() || !ctx.llm().supportsJsonSchemaResponse())
                return fallback;
            ContentRestrictionScope.restrictIfActive(policy.restrictions());
            var controls = new ChatRequestControls(ToolChoiceMode.NONE, "",
                    ResponseFormatMode.JSON_SCHEMA, policy.advisorySchema(), List.of(ProjectHandoffSourceAccess.TAG),
                    null, 512, ProviderResponseCapture.ToolAuthorityMode.NONE).withSingleAttempt(true);
            ProjectHandoffSourceAccess.bind(policy);
            LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.GENERATION_COORDINATION);
            var result = ctx.llm().chatFull(policy.advisoryProviderMessages(), opts.llmTimeoutMs, List.of(), controls);
            if (result != null && isAbortedGeneration(result, result.text())) {
                recordLlmAbortOutcome(result, result.text());
                throw abortedGenerationFailure(result);
            }
            if (result == null || dev.talos.runtime.IncompleteGenerationPresentation.required(result)
                    || result.hasToolCalls() || ToolCallParser.containsToolCalls(result.text())
                    || UiChrome.containsTurnAbortMarker(result.text())) return fallback;
            var decoded = ProjectHandoffPolicy.decodeAdvisory(policy, result.text());
            if (decoded.isEmpty() || !ProjectHandoffSourceAccess.available(policy, projection)
                    || !advisoryRawPresentationIntact(decoded.get(), projection)) return fallback;
            var draft = decoded.get();
            String answer = composeProjectHandoff(ProjectHandoffPolicy.renderAdvisory(draft), runtime,
                    identity, composeIdentity, opts);
            if (handoffFits(answer, opts)) return new ProjectHandoffPresentation(answer, result.provenance(), true);
            // Optional prose is omitted as a unit. Model-identified gaps and runtime facts are not clipped.
            String gaps = composeProjectHandoff(projectHandoffGaps(request, draft, projection), runtime, identity, composeIdentity, opts);
            return handoffFits(gaps, opts)
                    ? new ProjectHandoffPresentation(gaps, result.provenance(), true) : fallback;
        } catch (java.util.concurrent.CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException failure) {
            if (TurnFailureClassifier.isCancellation(failure) || TurnFailureClassifier.isInterruption(failure))
                throw TurnFailureClassifier.propagateControlFlow(failure);
            if (Thread.currentThread().isInterrupted()) throw failure;
            if (dev.talos.runtime.IncompleteGenerationPresentation.isAbortedFailure(failure)
                    || failure instanceof dev.talos.runtime.GenerationWatchdogException)
                throw failure;
            LocalTurnTraceCapture.warning("PROJECT_HANDOFF_UNAVAILABLE", "The bounded reference selection was unavailable. No retry was attempted.");
            return fallback;
        } finally {
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
        }
    }

    private static boolean advisoryRawPresentationIntact(ProjectHandoffPolicy.AdvisoryDraft draft,
                                                         UnaryOperator<String> projection) {
        for (var slot : draft.slots()) {
            if (!slot.result().text().equals(projection.apply(slot.result().text()))) return false;
            if (!slot.gap().equals(projection.apply(slot.gap()))) return false;
            for (var unit : java.util.stream.Stream.concat(slot.summary().stream(), slot.proposal().stream()).toList())
                if (!unit.text().equals(projection.apply(unit.text()))) return false;
        }
        return true;
    }

    private static String projectHandoffGaps(WorkStatusRequest request, ProjectHandoffPolicy.AdvisoryDraft draft,
                                           UnaryOperator<String> projection) {
        if (request.coverage() == WorkStatusRequest.Coverage.PARTIAL)
            return "Unresolved request, project meaning remains unresolved:\n"
                    + handoffQuestionLiteral(request.originalRequest(), projection) + "\n";
        var answer = new StringBuilder(ProjectHandoffPolicy.unavailable());
        if (draft != null) answer.append("Optional advisory prose was omitted by the presentation bound. Model-identified gaps remain unverified.\n");
        for (var requested : ProjectHandoffPolicy.requestedResults(request)) {
            String id = requested.id();
            String question = requested.text();
            String literal = handoffQuestionLiteral(question, projection);
            answer.append('\n').append(id).append(". Original project question: ").append(literal).append('\n')
                    .append(draft == null ? "No historical material was selected for this result. Its project meaning remains unresolved.\n"
                            : "Historical summaries were not presented for this result. Its project meaning remains unresolved.\n");
            if (draft != null) draft.slots().stream().filter(slot -> id.equals(slot.result().id()))
                    .filter(slot -> !slot.gap().isEmpty()).forEach(slot -> answer.append("Model-identified gap, unverified: ")
                            .append(ProjectHandoffPolicy.literal(slot.gap())).append('\n'));
        }
        return answer.toString();
    }

    private static String handoffQuestionLiteral(String question, UnaryOperator<String> projection) {
        try {
            return question.equals(projection.apply(question)) ? ProjectHandoffPolicy.literal(question)
                    : "[Original question withheld by presentation restrictions.]";
        } catch (IllegalArgumentException invalid) {
            return "[Original question could not be presented intact.]";
        }
    }

    private static String composeProjectHandoff(String project, String runtime, ModelIdentitySnapshot identity,
                                               boolean composeIdentity, Options opts) {
        String answer = project + "\nRuntime activity and verification\n" + runtime;
        if (composeIdentity) {
            var presentation = RuntimeModelIdentityAnswerPolicy.present(identity, answer, opts.answerSanitizer, Long.MAX_VALUE);
            if (!presentation.identityComplete() || presentation.truncated()) return null;
            answer = presentation.answer();
        }
        return answer.equals(opts.answerSanitizer.apply(answer)) ? answer : null;
    }

    private static boolean handoffFits(String answer, Options opts) {
        return answer != null && answer.length() <= Math.max(0, opts.responseMaxChars);
    }

    static String selectedProjectReferences(ProjectHandoffPolicy.References references, LlmClient.StreamResult result) {
        if (result == null || result.aborted() || result.outputLimitReached() || result.truncated()
                || result.completion() == ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP
                || result.hasToolCalls() || ToolCallParser.containsToolCalls(result.text())
                || UiChrome.containsTurnAbortMarker(result.text())) return ProjectHandoffPolicy.unavailable(
                        WorkStatusRequest.parse(references.currentRequest()));
        return ProjectHandoffPolicy.renderComposition(references, result.text());
    }

    private static TurnOutput contentDraftOutput(dev.talos.runtime.context.PendingContentDraft pending,
                                                 Context ctx, Options opts) {
        String answer = "A complete draft could not be prepared. No write was attempted. Please restate the full content request.";
        ProviderResponseCapture.Receipt receipt = null;
        dev.talos.runtime.context.PendingContentDraft accepted = null;
        String draftText = null;
        String questionText = null;
        boolean plainPunctuation = false;
        var priorStage = LocalTurnTraceCapture.currentExecutionStage();
        try {
            if (ctx.memory() != null && ctx.llm() != null) {
                boolean schemaSupported = ctx.llm().supportsJsonSchemaResponse();
                var controls = new ChatRequestControls(ToolChoiceMode.NONE, "",
                        schemaSupported ? ResponseFormatMode.JSON_SCHEMA : ResponseFormatMode.TEXT,
                        schemaSupported ? dev.talos.runtime.context.ContentDraftProposal.SCHEMA : "", List.of("content-draft-proposal"),
                        null, 0, ProviderResponseCapture.ToolAuthorityMode.NONE).withSingleAttempt(true);
                LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.GENERATION_COORDINATION);
                var result = ctx.llm().chatFull(dev.talos.runtime.context.ContentDraftProposal.messages(pending),
                        opts.llmTimeoutMs, List.of(), controls);
                if (result != null && isAbortedGeneration(result, result.text())) {
                    recordLlmAbortOutcome(result, result.text());
                    throw abortedGenerationFailure(result);
                }
                if (result != null && !dev.talos.runtime.IncompleteGenerationPresentation.required(result)
                        && !result.hasToolCalls() && !ToolCallParser.containsToolCalls(result.text())
                        && !UiChrome.containsTurnAbortMarker(result.text())) {
                    var proposal = dev.talos.runtime.context.ContentDraftProposal.decode(result.text());
                    if (proposal.isPresent()) {
                        var value = proposal.get();
                        String text = "draft".equals(value.kind())
                                ? showableText(value.text(), t -> dev.talos.runtime.context.ContentDraftContinuation.render(
                                        t, pending.destination(), pending.id()), ctx, opts)
                                : showableText(value.text(), AssistantTurnExecutor::clarificationAnswer, ctx, opts);
                        String redacted = ctx.redactor() == null
                                ? new dev.talos.core.security.Redactor().redactBlock(text)
                                : ctx.redactor().redactBlock(text);
                        if (text.equals(redacted) && text.equals(opts.answerSanitizer.apply(text))) {
                            receipt = result.provenance();
                            if ("draft".equals(value.kind())) {
                                draftText = text;
                                plainPunctuation = !text.equals(value.text());
                                accepted = new dev.talos.runtime.context.PendingContentDraft(pending.origin(),
                                        pending.operationIndex(), pending.workspace(), pending.originTurn(),
                                        pending.expiresAfterTurn(), pending.id(), pending.destination(), text);
                                answer = (plainPunctuation ? PLAIN_PUNCTUATION_NOTE + "\n" : "")
                                        + dev.talos.runtime.context.ContentDraftContinuation.render(accepted);
                            } else {
                                questionText = text;
                                answer = clarificationAnswer(text);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            if (TurnFailureClassifier.isCancellation(failure) || TurnFailureClassifier.isInterruption(failure))
                throw TurnFailureClassifier.propagateControlFlow(failure);
            if (Thread.currentThread().isInterrupted()
                    || dev.talos.runtime.IncompleteGenerationPresentation.isAbortedFailure(failure)
                    || failure instanceof dev.talos.runtime.GenerationWatchdogException) throw failure;
            LocalTurnTraceCapture.warning("CONTENT_DRAFT_UNAVAILABLE", "Draft generation failed. No retry was attempted.");
        } finally {
            LocalTurnTraceCapture.enterExecutionStage(priorStage);
        }
        if (!draftPresentationIntact(answer, ctx, opts)) {
            accepted = null;
            receipt = null;
            answer = questionText != null
                    ? "The clarification question could not be shown exactly on this display. "
                        + "No draft was retained and no write was attempted." + unshowableCharacter(questionText, ctx)
                    : "The complete draft could not be presented safely. No draft was retained and no write was attempted."
                        + unshowableCharacter(draftText, ctx);
        } else if (accepted != null && plainPunctuation) {
            LocalTurnTraceCapture.warning("CONTENT_DRAFT_PLAIN_PUNCTUATION",
                    "Typographic punctuation the display cannot show was replaced before the draft was shown and kept.");
        }
        var verification = TaskVerificationResult.notRun("Draft generation does not execute or verify a file save.");
        TaskOutcomeTraceRecorder.record("PARTIAL", "NOT_RUN", new TaskOutcome(pending.origin().contract(),
                TaskCompletionStatus.ADVISORY_ONLY, null, verification, List.of(), List.of()), verification);
        if (receipt == null) LocalTurnTraceCapture.recordDeterministicAnswerSelected(answer);
        else LocalTurnTraceCapture.recordProviderAnswerSelectedAfterTransformation(answer, receipt,
                LocalTurnTraceCapture.AnswerTransformationReason.EXECUTOR_POLICY_SHAPING);
        if (ctx.memory() != null) ctx.memory().setPendingContentDraft(accepted);
        completeStream(ctx);
        return completeTurnOutput(answer, false);
    }

    private static final String PLAIN_PUNCTUATION_NOTE = "Typographic quotes, dashes, special spaces or ellipses that this "
            + "display cannot show were replaced with plain characters, so the draft below is exactly what would be saved.";

    private static String clarificationAnswer(String question) {
        return "No draft was selected and no file was written.\n\n" + question
                + "\n\nPlease provide the clarification together with the complete original request.";
    }

    /** Only typographic punctuation the display cannot show is made plain, so what is shown is what is kept. */
    private static String showableText(String text, java.util.function.UnaryOperator<String> render,
                                       Context ctx, Options opts) {
        if (ctx.exactAnswerPresentation() == null || draftPresentationIntact(render.apply(text), ctx, opts)) return text;
        var preserves = ctx.exactAnswerPresentation().preserves();
        String plain = dev.talos.core.util.Sanitize.plainPunctuation(text, cp -> !preserves.test(Character.toString(cp)));
        return !plain.equals(text) && dev.talos.runtime.context.ContentDraftProposal.validText(plain)
                && draftPresentationIntact(render.apply(plain), ctx, opts) ? plain : text;
    }

    /** Names the first character outside the plain punctuation table that the display cannot show. */
    private static String unshowableCharacter(String text, Context ctx) {
        if (text == null || ctx.exactAnswerPresentation() == null) return "";
        var preserves = ctx.exactAnswerPresentation().preserves();
        String plain = dev.talos.core.util.Sanitize.plainPunctuation(text, cp -> !preserves.test(Character.toString(cp)));
        return plain.codePoints().filter(cp -> cp > 0x7E && !preserves.test(Character.toString(cp))).findFirst()
                .stream().mapToObj(cp -> " This display cannot show the character U+"
                        + String.format(java.util.Locale.ROOT, "%04X", cp)
                        + (Character.getName(cp) == null ? "" : " " + Character.getName(cp)) + ".")
                .findFirst().orElse("");
    }

    private static boolean draftPresentationIntact(String answer, Context ctx, Options opts) {
        String redacted = ctx.redactor() == null ? new dev.talos.core.security.Redactor().redactBlock(answer)
                : ctx.redactor().redactBlock(answer);
        return answer.equals(redacted) && answer.equals(sanitizeAndTruncate(answer, opts))
                && ctx.exactAnswerPresentation() != null
                && ctx.exactAnswerPresentation().preserves().test(answer);
    }

    private static TurnOutput selectedDraftOutput(dev.talos.runtime.task.TaskResolution resolution,
            CurrentTurnPlan plan, List<ChatMessage> messages, Path workspace, Context ctx, Options opts) {
        if (ctx.toolCallLoop() == null) return directTurnOutput("The selected draft cannot be saved in this context. No write was attempted.", ctx, opts);
        var selected = plan.contentSaveRequest().draftSelection();
        String presentation = dev.talos.runtime.context.ContentDraftContinuation.render(
                selected.content(), selected.destination(), selected.id());
        if (!draftPresentationIntact(presentation, ctx, opts)) {
            return directTurnOutput("The selected draft cannot be presented unchanged with the current display settings. "
                    + "No write was attempted. Please request a new draft.", ctx, opts);
        }
        TurnSourceEvidenceCapture.begin();
        TurnTaskContractCapture.set(resolution, messages);
        TurnTaskContractCapture.setRequestedResults(plan.requestedResults());
        try {
            var call = new ChatMessage.NativeToolCall("draft-" + selected.id(), "talos.write_file",
                    java.util.Map.of("path", selected.destination(), "content", selected.content()));
            var result = ctx.toolCallLoop().runRuntimeDeterministic("", List.of(call),
                    LocalTurnTraceCapture.RuntimeToolAuthorityReason.SELECTED_CONTENT_DRAFT, messages, workspace, ctx);
            String answer = shapeAnswerAfterToolLoop(result.finalAnswer(), messages, plan, result, workspace, 0, opts);
            return completeTurnOutput(answer, false);
        } finally { TurnTaskContractCapture.clear(); TurnSourceEvidenceCapture.clear(); }
    }

    private static TurnOutput directTurnOutput(String answer, Context ctx, Options opts,
                                               ModelIdentitySnapshot identity, boolean composeIdentity) {
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(answer);
        String shaped = sanitizeAndTruncate(answer == null ? "" : answer, opts);
        if (composeIdentity) shaped = presentRuntimeIdentity(shaped, identity, opts);
        if (ctx != null) completeStream(ctx);
        return completeTurnOutput(shaped, false);
    }

    private static TurnOutput completeRuntimeIdentityOutput(String answer, ModelIdentitySnapshot identity,
                                                             Context ctx, Options opts) {
        String shaped = presentRuntimeIdentity(answer, identity, opts);
        if (ctx != null) completeStream(ctx);
        return completeTurnOutput(shaped, false);
    }

    private static String presentRuntimeIdentity(String answer, ModelIdentitySnapshot identity, Options opts) {
        var unbounded = RuntimeModelIdentityAnswerPolicy.present(identity, answer, opts.answerSanitizer, Long.MAX_VALUE);
        if (opts.beforeClip.apply(unbounded.answer()).length() > opts.responseMaxChars) answer = opts.beforeClip.apply(answer);
        var presentation = RuntimeModelIdentityAnswerPolicy.present(
                identity, answer, opts.answerSanitizer, opts.responseMaxChars);
        LocalTurnTraceCapture.recordAnswerPrefixPresented(answer, presentation.answer(),
                presentation.retainedInputChars(), presentation.truncated());
        if (!presentation.identityComplete()) {
            LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.RUNTIME_IDENTITY_INCOMPLETE);
            LocalTurnTraceCapture.warning("RUNTIME_IDENTITY_PRESENTATION_INCOMPLETE",
                    "The runtime identity component was not presented intact.");
        }
        if (presentation.truncated()) {
            LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.OUTPUT_TRUNCATED);
            LocalTurnTraceCapture.warning("RUNTIME_IDENTITY_OUTPUT_LIMIT",
                    "The composed answer exceeded the presentation limit and remains incomplete.");
        }
        return presentation.answer();
    }

    private static void completeStream(Context ctx) {
        propagateObservedStreamCompletionInterruption();
        if (ctx.streamSink() instanceof ToolCallStreamFilter filter) {
            filter.flush();
        }
        propagateObservedStreamCompletionInterruption();
        runStreamCompletion(ctx.onStreamComplete());
    }

    private static TurnOutput completeTurnOutput(String answer, boolean streamed) {
        String safe = Objects.toString(answer, "");
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(
                safe,
                LocalTurnTraceCapture.AnswerTransformationReason.EXECUTOR_POLICY_SHAPING);
        return new TurnOutput(safe, streamed);
    }

    private static Context withDispatchSink(Context ctx, java.util.function.Consumer<String> sink) {
        return new Context(ctx.cfg(), ctx.limits(), ctx.session(), ctx.audit(), ctx.redactor(),
                ctx.sandbox(), ctx.rag(), ctx.llm(), ctx.netPolicy(), ctx.memory(), ctx.approvalGate(),
                ctx.toolRegistry(), ctx.conversationManager(), ctx.toolCallLoop(), sink,
                ctx.onStreamComplete(), ctx.executionPhaseState(), ctx.nativeToolSpecs(), ctx.pinnedFiles(),
                ctx.exactAnswerPresentation(), ctx.requestFrame());
    }

    private static void discardStreamChunk(String ignored) {
        propagateObservedStreamCompletionInterruption();
    }

    static void runStreamCompletion(Runnable completion) {
        if (completion == null) return;
        propagateObservedStreamCompletionInterruption();
        try {
            completion.run();
        } catch (RuntimeException failure) {
            if (TurnFailureClassifier.isCancellation(failure)
                    || TurnFailureClassifier.isInterruption(failure)) {
                throw TurnFailureClassifier.propagateControlFlow(failure);
            }
        }
        propagateObservedStreamCompletionInterruption();
    }

    private static void propagateObservedStreamCompletionInterruption() {
        if (Thread.currentThread().isInterrupted()) {
            throw TurnFailureClassifier.propagateControlFlow(
                    new InterruptedException("stream completion interrupted"));
        }
    }

    record ToolLoopAnswerResolution(String answer, String extraSummary, boolean outputLimitReached) {}

    private static ToolLoopAnswerResolution resolveToolLoopAnswer(
            String answer,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        AssistantToolLoopOutcomeResolver.Resolution resolution = AssistantToolLoopOutcomeResolver.resolveSelected(
                answer,
                messages,
                plan,
                loopResult,
                workspace,
                ctx,
                (outcomeLoopResult, extraMutationSuccesses) ->
                        moveToVerifyAfterSuccessfulMutation(ctx, outcomeLoopResult, extraMutationSuccesses),
                (resolvedAnswer, outcomeLoopResult, extraMutationSuccesses, actionObligationFailed) ->
                        shapeAnswerAfterToolLoopSelected(
                                resolvedAnswer,
                                messages,
                                plan,
                                outcomeLoopResult,
                                workspace,
                                extraMutationSuccesses,
                                actionObligationFailed,
                                opts)
        );
        return new ToolLoopAnswerResolution(resolution.answer(), resolution.extraSummary(), resolution.outputLimitReached());
    }

    private static ToolLoopAnswerResolution resolveNoToolAnswer(
            String answer,
            boolean initialOutputLimitReached,
            List<ChatMessage> messages,
            CurrentTurnPlan plan,
            Path workspace,
            Context ctx,
            Options opts
    ) {
        AssistantNoToolOutcomeResolver.Resolution resolution = AssistantNoToolOutcomeResolver.resolveSelected(
                answer,
                initialOutputLimitReached,
                messages,
                plan,
                workspace,
                ctx,
                (loopResult, extraMutationSuccesses) ->
                        moveToVerifyAfterSuccessfulMutation(ctx, loopResult, extraMutationSuccesses),
                (resolvedAnswer, loopResult, extraMutationSuccesses, actionObligationFailed) ->
                        shapeAnswerAfterToolLoopSelected(
                                resolvedAnswer,
                                messages,
                                plan,
                                loopResult,
                                workspace,
                                extraMutationSuccesses,
                                actionObligationFailed,
                                opts),
                (resolvedAnswer, actionObligationFailed) ->
                        shapeAnswerWithoutToolsSelected(
                                resolvedAnswer,
                                messages,
                                plan,
                                ctx,
                                false,
                                actionObligationFailed,
                                opts)
        );
        return new ToolLoopAnswerResolution(resolution.answer(), resolution.extraSummary(), resolution.outputLimitReached());
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
                null,
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
        CurrentTurnPlan capturedPlan = requireCapturedRetryPlan(plan);
        return ReadOnlyInspectionRetry.retryIfNeeded(
                answer,
                messages,
                capturedPlan,
                workspace,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        capturedPlan,
                        RetryDispatchPurpose.READ_ONLY_INSPECTION));
    }

    private static CurrentTurnPlan requireCapturedRetryPlan(CurrentTurnPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        return plan;
    }

    private static void appendExtraSummary(StringBuilder out, String extraSummary) {
        if (extraSummary != null) out.append(extraSummary).append("\n\n");
    }

    private static CurrentTurnPlan compatibilityPlanFromMessages(List<ChatMessage> messages, Context ctx) {
        var resolution = TurnTaskContractCapture.getResolution();
        if (resolution == null) {
            resolution = TaskContractResolver.resolveFromMessages(messages);
        }
        TaskContract contract = resolution.contract();
        ExecutionPhase phase = currentExecutionPhase(ctx, contract);
        List<String> promptTools = ctx == null
                ? ToolSurfacePlanner.defaultVisibleToolNamesForResolution(
                        resolution,
                        phase)
                : NativeToolSpecPolicy.names(ctx.nativeToolSpecs());
        boolean nativeEnabled = ctx == null
                || ctx.llm() == null
                || ctx.llm().nativeToolsCurrentlyEnabled();
        List<String> nativeTools = nativeEnabled ? promptTools : List.of();
        return CurrentTurnPlan.createWithActionObligation(
                resolution,
                phase,
                nativeTools,
                promptTools,
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.derivedVerifierProfile(contract),
                dev.talos.runtime.policy.ActionObligationPolicy.derive(
                        resolution,
                        phase),
                ctx == null ? null : ctx.cfg());
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
        if (plan != null && plan.workspaceResultsApplicable()) return false;
        TaskContract taskContract = plan == null ? null : plan.taskContract();
        if (taskContract != null && taskContract.mutationAllowed()) return false;
        if (EvidenceGate.requiresReadEvidenceHandoff(EvidenceGate.selectObligation(
                plan,
                workspace,
                ctx.cfg()))) return false;
        return !requiresWorkspaceEvidence(taskContract);
    }

    private static boolean blocksToolCallsForContract(TaskContract taskContract) {
        return taskContract != null && taskContract.type() == TaskType.SMALL_TALK;
    }

    private record BlockedSmallTalkAnswer(String text, boolean providerDerived, boolean clipped, boolean inputRetained) {
        private BlockedSmallTalkAnswer {
            text = Objects.toString(text, "");
        }
        static BlockedSmallTalkAnswer present(String text, boolean providerDerived, Options opts) {
            var bounded = boundedAnswer(text, opts);
            return new BlockedSmallTalkAnswer(bounded.text(), providerDerived, bounded.clipped(), bounded.retainedInputChars() > 0);
        }
    }

    private static BlockedSmallTalkAnswer answerForBlockedSmallTalkToolCalls(
            String answer,
            List<ChatMessage> messages,
            Options opts
    ) {
        String stripped = ToolCallParser.stripToolCalls(answer == null ? "" : answer).strip();
        if (!stripped.isBlank()) {
            return BlockedSmallTalkAnswer.present(stripped, true, opts);
        }
        String userRequest = latestUserRequest(messages);
        if (CapabilityAnswerPolicy.looksLikeWorkspaceSwitchRequest(userRequest)) {
            return BlockedSmallTalkAnswer.present(CapabilityAnswerPolicy.workspaceSwitchUnsupportedAnswer(), false, opts);
        }
        if (looksLikeAssistantIdentityTurn(userRequest)) {
            return BlockedSmallTalkAnswer.present(CapabilityAnswerPolicy.identityAnswer(), false, opts);
        }
        if (looksLikeAssistantCapabilityTurn(userRequest)) {
            return BlockedSmallTalkAnswer.present(CapabilityAnswerPolicy.capabilityAnswer(), false, opts);
        }
        return BlockedSmallTalkAnswer.present("Hi, I am Talos.", false, opts);
    }

    private static void recordBlockedSmallTalkAnswerOrigin(
            BlockedSmallTalkAnswer answer,
            ProviderResponseCapture.Receipt receipt) {
        if (answer.providerDerived()) {
            LocalTurnTraceCapture.recordProviderAnswerSelectedAfterToolCallSuppression(
                    answer.text(),
                    receipt,
                    LocalTurnTraceCapture.ProviderToolSuppressionReason
                            .TASK_CONTRACT_BLOCKED_TOOL_CALLS);
        } else {
            LocalTurnTraceCapture.recordDeterministicAnswerSelected(answer.text());
        }
        if (answer.clipped()) LocalTurnTraceCapture.recordAnswerDeliverySelected(answer.text(), answer.inputRetained(), true);
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
            Context ctx,
            ModelIdentitySnapshot runtimeIdentity
    ) {
        String userRequest = latestUserRequest(messages);
        if (MutationIntent.classifyShape(userRequest).decision() == MutationIntent.Decision.AMBIGUOUS) {
            return "Your request asks for a file change and also says not to change files. "
                    + "I did not modify anything. Should Talos apply the requested change, "
                    + "or keep this turn read-only?";
        }
        Optional<String> identityAnswer = RuntimeModelIdentityAnswerPolicy.answer(userRequest, runtimeIdentity);
        if (identityAnswer.isPresent()) return identityAnswer.get();
        Optional<String> sessionAnswer = SessionActionAnswerPolicy.answer(userRequest, ctx == null ? null : ctx.memory());
        if (sessionAnswer.isPresent()) return sessionAnswer.get();
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
        if ((contract == null || !contract.mutationAllowed())
                && HistoricalFollowUpRequest.isVerificationRequest(userRequest)) {
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
                || !"session-uncertainty-question".equals(contract.classificationReason())
                || !HistoricalFollowUpRequest.isSessionUncertaintyRequest(contract.originalUserRequest())) {
            return null;
        }
        ChangeSummaryContext context = ctx == null || ctx.memory() == null
                ? null
                : ctx.memory().changeSummaryContext();
        if (!hasSessionUncertaintyEvidence(context)) {
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
        SessionEvidenceKind kind = switch (HistoricalFollowUpRequest.sessionEvidenceAction(
                userRequest, contract.expectedTargets())) {
            case READ -> SessionEvidenceKind.READ;
            case MUTATE -> SessionEvidenceKind.MUTATE;
            case NONE -> SessionEvidenceKind.UNKNOWN;
        };
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
        if (!HistoricalFollowUpRequest.isStaticDiagnosticRequest(userRequest)) return null;
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
        Set<String> formats = HistoricalFollowUpRequest.documentStatusFormats(userRequest);
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
        if (!HistoricalFollowUpRequest.isPureHistoryRequest(userRequest)
                || (!looksLikeChangeSummaryFollowUp(userRequest)
                && !MutationIntent.looksPriorChangeStatusQuestion(userRequest))) {
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
        if (!HistoricalFollowUpRequest.isPureHistoryRequest(userRequest)) return false;
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
        return shapeAnswerAfterToolLoopSelected(answer, messages, plan, loopResult, workspace,
                extraMutationSuccesses, failedActionObligation, opts).answer();
    }

    private static AnswerOutputLimit.Presentation shapeAnswerAfterToolLoopSelected(
            String answer, List<ChatMessage> messages, CurrentTurnPlan plan, ToolCallLoop.LoopResult loopResult,
            Path workspace, int extraMutationSuccesses, boolean failedActionObligation, Options opts) {
        String directoryListingAnswer = directoryListingAnswerIfApplicable(messages, plan, loopResult);
        if (!directoryListingAnswer.isBlank()) {
            LocalTurnTraceCapture.recordAnswerDeliverySelected(directoryListingAnswer, false, false);
            return new AnswerOutputLimit.Presentation(sanitizeAndTruncate(directoryListingAnswer, opts), false);
        }
        String verifyOnlyPathAnswer = verifyOnlyPathCheckAnswerIfApplicable(messages, plan, loopResult);
        if (!verifyOnlyPathAnswer.isBlank()) {
            LocalTurnTraceCapture.recordAnswerDeliverySelected(verifyOnlyPathAnswer, false, false);
            return new AnswerOutputLimit.Presentation(sanitizeAndTruncate(verifyOnlyPathAnswer, opts), false);
        }
        String readTargetAnswer = readTargetAnswerIfApplicable(answer, messages, plan, loopResult);
        if (!readTargetAnswer.isBlank()) {
            LocalTurnTraceCapture.recordAnswerDeliverySelected(readTargetAnswer, false, false);
            return new AnswerOutputLimit.Presentation(sanitizeAndTruncate(readTargetAnswer, opts), false);
        }
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                answer, plan, messages, loopResult, workspace,
                extraMutationSuccesses, failedActionObligation, (text, components) -> {
                    String grounded = groundedReadOnlyProposalAnswerIfNeeded(text, messages, plan, loopResult);
                    if (!components.active()) return sanitizeAndTruncate(grounded, opts);
                    var presentation = components.presentWithDelivery(grounded, opts.answerSanitizer, opts.responseMaxChars);
                    LocalTurnTraceCapture.recordAnswerDeliverySelected(presentation.answer(), true, presentation.clipped());
                    return presentation.answer();
                });
        return new AnswerOutputLimit.Presentation(outcome.finalAnswer(), outcome.outputLimit());
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
        return shapeAnswerWithoutToolsSelected(answer, messages, plan, ctx, streamed, failedActionObligation, opts).answer();
    }

    private static AnswerOutputLimit.Presentation shapeAnswerWithoutToolsSelected(
            String answer, List<ChatMessage> messages, CurrentTurnPlan plan, Context ctx,
            boolean streamed, boolean failedActionObligation, Options opts) {
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
        LocalTurnTraceCapture.recordAnswerDeliverySelected(outcome.finalAnswer(),
                outcome.outputLimit() != AnswerOutputLimit.CLEAR, false);
        return new AnswerOutputLimit.Presentation(sanitizeAndTruncate(outcome.finalAnswer(), opts), outcome.outputLimit());
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
     * <p>Answers over 1500 chars always pass - they are long enough to be substantive.
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
        return synthesisRetryIfNeeded(
                answer, toolsInvoked, null, messages, ctx);
    }

    static String synthesisRetryIfNeeded(
            String answer,
            int toolsInvoked,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx
    ) {
        CurrentTurnPlan capturedPlan = requireCapturedRetryPlan(plan);
        return PostToolSynthesisRetry.synthesizeIfNeeded(
                answer,
                toolsInvoked,
                messages,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        capturedPlan,
                        RetryDispatchPurpose.POST_TOOL_SYNTHESIS));
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

    // ── Point 3 - Missing-mutation retry ─────────────────────────────────

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
     *       {@link TaskContractResolver#fromUserRequest}).</li>
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
                null,
                loopResult,
                workspace,
                ctx);
    }

    static MissingMutationRetry.Result mutationRequestRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        CurrentTurnPlan capturedPlan = requireCapturedRetryPlan(plan);
        return MissingMutationRetry.retryIfNeeded(
                answer,
                messages,
                capturedPlan,
                loopResult,
                workspace,
                ctx,
                (retryMessages, retryPlan, retryToolSpecs) ->
                        TurnModelDispatcher.dispatchEscalatedRetry(
                                ctx,
                                retryMessages,
                                retryPlan,
                                retryToolSpecs,
                                RetryDispatchPurpose.MISSING_MUTATION));
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
                null,
                loopResult,
                workspace,
                ctx);
    }

    static InspectCompletenessRetry.Result inspectCompletenessRetryIfNeeded(
            String answer, List<ChatMessage> messages,
            CurrentTurnPlan plan,
            ToolCallLoop.LoopResult loopResult,
            Path workspace, Context ctx) {
        CurrentTurnPlan capturedPlan = requireCapturedRetryPlan(plan);
        return InspectCompletenessRetry.retryIfNeeded(
                answer,
                messages,
                capturedPlan,
                loopResult,
                workspace,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        capturedPlan,
                        RetryDispatchPurpose.INSPECT_COMPLETENESS));
    }

    static String overrideSelectorMismatchAnalysisIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        return overrideSelectorMismatchAnalysisIfNeeded(
                answer,
                null,
                messages,
                loopResult,
                workspace);
    }

    static String overrideSelectorMismatchAnalysisIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        return overrideSelectorMismatchAnalysisIfNeeded(
                answer, plan, messages, loopResult, workspace, null);
    }

    static String overrideSelectorMismatchAnalysisIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            ReadOnlyWebDiagnosticResult diagnosticSelection) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null || workspace == null) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        TaskContract contract = plan == null
                ? TurnTaskContractCapture.currentOrResolve(messages)
                : plan.taskContract();
        if (contract != null
                && (contract.mutationRequested()
                        || contract.mutationAllowed())) {
            return answer;
        }
        String userRequest = contract == null
                ? ""
                : contract.originalUserRequest();
        if (!looksLikeSelectorMismatchRequest(userRequest)) return answer;

        var evidence = loopResult.readOnlyFinalizationEvidence();
        if (diagnosticSelection != null && answer.equals(diagnosticSelection.answer())
                && evidence.matches(loopResult.finalAnswer(), workspace, contract)
                && evidence.selectionId().equals(diagnosticSelection.association().selectionId())
                && ReadOnlyWebDiagnosticResult.textHash(answer)
                    .equals(diagnosticSelection.association().outputHash())) {
            return diagnosticSelection.answer();
        }
        return selectReadOnlyWebDiagnosticsIfNeeded(answer, plan, messages, loopResult, workspace).answer();
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
        return overrideReadOnlyWebDiagnosticsIfNeeded(
                answer,
                null,
                messages,
                loopResult,
                workspace);
    }

    static String overrideReadOnlyWebDiagnosticsIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        return selectReadOnlyWebDiagnosticsIfNeeded(answer, plan, messages, loopResult, workspace).answer();
    }

    static ReadOnlyWebDiagnosticResult selectReadOnlyWebDiagnosticsIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace) {
        ReadOnlyWebDiagnosticResult unchanged = ReadOnlyWebDiagnosticResult.unaffected(answer);
        if (loopResult == null || workspace == null) return unchanged;
        if (loopResult.mutatingToolSuccesses() > 0) return unchanged;
        TaskContract requestContract = plan == null
                ? TurnTaskContractCapture.currentOrResolve(messages)
                : plan.taskContract();
        if (requestContract == null) return unchanged;
        if (requestContract.mutationRequested()
                || requestContract.mutationAllowed()) {
            return unchanged;
        }
        var retainedEvidence = loopResult.readOnlyFinalizationEvidence();
        if (retainedEvidence.proposalSelected()) {
            if (dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer.rejectedProposalSynthesis(loopResult)
                    && plan != null
                    && dev.talos.runtime.policy.EvidenceObligationAssessment.assessObserved(plan, loopResult)
                        .result().status() == dev.talos.runtime.policy.EvidenceObligationVerifier.Status.SATISFIED) {
                return new ReadOnlyWebDiagnosticResult(
                        Objects.equals(answer, loopResult.finalAnswer()) ? answer
                                : dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer.synthesisUnavailableAnswer(),
                        ReadOnlyWebAnswerAssessment.limited(ReadOnlyWebAnswerAssessment.Reason.SYNTHESIS_UNAVAILABLE),
                        List.of());
            }
            if (!retainedEvidence.matches(answer == null ? "" : answer, workspace, requestContract)
                    || loopResult.failedCalls() > 0 || loopResult.failureDecision().shouldStop()
                    || loopResult.toolOutcomes().stream().anyMatch(outcome -> outcome.denied()
                        || !outcome.success() || outcome.mutating() || outcome.mutationEffects().hasEffects()))
                return StaticTaskVerifier.limitedReadOnlyWebDiagnostics(
                        ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE);
            return dev.talos.runtime.verification.NativeReadOnlyAnswerProposal.assessEvaluation(
                    answer, retainedEvidence.requestMeaning(), retainedEvidence.sources())
                    .withAssociation(retainedEvidence.selectionId(), retainedEvidence.generationId(), answer,
                            retainedEvidence.sources().stream().map(source -> source.observationId()).toList());
        }
        if (requestContract.type() == TaskType.WORKSPACE_EXPLAIN) return unchanged;
        EvidenceObligation requestEvidence = plan == null
                ? EvidenceObligation.NONE
                : EvidenceObligationPolicy.parse(plan.evidenceObligation());
        String originalUserRequest = requestContract.originalUserRequest();
        if ("WORKSPACE_EXPLAIN".equals(
                ToolCallSupport.embeddedRetryTaskType(originalUserRequest))) {
            return unchanged;
        }
        String userRequest =
                ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt(
                        originalUserRequest);
        if (requestEvidence == EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED
                && !looksLikeSelectorMismatchRequest(userRequest)) return unchanged;
        if (StaticWebImportIntent.matches(userRequest)) return unchanged;
        if (!WebDiagnosticIntent.matchesReadOnlyRequest(userRequest)
                && !looksLikeSelectorMismatchRequest(userRequest)) return unchanged;
        if (requestContract.type() == TaskType.VERIFY_ONLY) {
            ReadOnlyWebDiagnosticResult verified = StaticTaskVerifier.assessReadOnlyWebDiagnostics(
                    workspace, loopResult.readPaths(), userRequest, answer);
            return verified.answer() == null ? unchanged : verified;
        }
        var evidence = loopResult.readOnlyFinalizationEvidence();
        if (!evidence.matches(answer == null ? "" : answer, workspace, requestContract)
                || loopResult.failedCalls() > 0 || loopResult.failureDecision().shouldStop()
                || loopResult.toolOutcomes().stream().anyMatch(outcome -> outcome.denied()
                    || !outcome.success() || outcome.mutating() || outcome.mutationEffects().hasEffects())) {
            return StaticTaskVerifier.limitedReadOnlyWebDiagnostics(
                    ReadOnlyWebAnswerAssessment.Reason.SOURCE_EVIDENCE_UNAVAILABLE);
        }
        ReadOnlyWebDiagnosticResult grounded = StaticTaskVerifier.assessReadOnlyWebDiagnostics(
                evidence.sources(), userRequest, answer == null ? "" : answer, requestContract.answerRequirements());
        return grounded.withAssociation(evidence.selectionId(), evidence.generationId(), answer,
                evidence.sources().stream().map(source -> source.observationId()).toList());
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
        if (loopResult.readOnlyFinalizationEvidence().proposalSelected()) return answer;
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

    /**
     * Inspect under-completion truth layer (annotate-first).
     *
     * <p>Fires when <b>all</b> of the following hold:
     * <ol>
     *   <li>The tool loop ran and invoked at least one tool - if the turn
     *       invoked zero tools, {@link #groundingRetryIfNeeded} /
     *       {@link #shouldAppendStreamingGroundingAnnotation} (R6 / N2)
     *       is the correct gate, not this one.</li>
     *   <li>Zero mutating tool successes - a successful mutation means the
     *       model did substantive work and the under-inspection signal is
     *       noise.</li>
     *   <li>The answer is at least {@link #INSPECT_MIN_CHARS} characters -
     *       substantive enough to carry fabricated claims.</li>
     *   <li>{@link #readOnlyToolCount(ToolCallLoop.LoopResult)} ≤ 1 -
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
     * deliberate single-shape decision - when real transcript evidence
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
     * the transcript's long-fabrication shapes (1600+ chars in Turns 2-4) are
     * comfortably inside the window. Values below 600 risk fighting the
     * short-deflection tier (≤ 500 chars) already handled elsewhere.
     */
    static final int UNGROUNDED_MIN_CHARS = NoToolAnswerTruthfulnessGuard.UNGROUNDED_MIN_CHARS;

    /*
     * Phrases in the <em>user request</em> that indicate the user wants the
     * answer grounded in inspected workspace contents. Kept conservative and
     * anchored to real transcript prompt wording - we explicitly do not want
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
     * N2 - streaming-path grounding annotation predicate.
     *
     * <p>Pure detection helper, no side effects. Returns {@code true} iff the
     * streamed turn exhibits the R6 failure shape:
     * <ol>
     *   <li>the answer is non-blank and at least {@link #UNGROUNDED_MIN_CHARS}
     *       characters long;</li>
     *   <li>the latest user request contains an evidence-request marker;</li>
     *   <li>the caller invoked this helper on the no-tool-call streaming
     *       branch - zero-tools is a structural invariant of the call site,
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
     *       long - substantive enough that the existing deflection gate is
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
     * <p><b>Scope note (N1 - non-streaming only):</b> this helper performs a
     * silent retry, which is only safe on the non-streaming branch - the
     * streaming branch has already emitted prose to the terminal by the time
     * this helper could fire, so a retry would double-render. The streaming
     * counterpart is {@link #shouldAppendStreamingGroundingAnnotation}, which
     * is detect-only and never retries.
     *
     * <p>Package-private for direct testing.
     */
    static String groundingRetryIfNeeded(String answer, List<ChatMessage> messages, Context ctx) {
        return groundingRetryIfNeeded(answer, null, messages, ctx);
    }

    static String groundingRetryIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx
    ) {
        CurrentTurnPlan capturedPlan = requireCapturedRetryPlan(plan);
        return NoToolGroundingRetry.retryIfNeeded(
                answer,
                capturedPlan,
                messages,
                ctx,
                retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        retryMessages,
                        capturedPlan,
                        RetryDispatchPurpose.NO_TOOL_GROUNDING));
    }
}

