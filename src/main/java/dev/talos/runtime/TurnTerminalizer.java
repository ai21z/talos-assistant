package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnSettlementTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Freezes exactly one terminal envelope for an allocated turn. */
final class TurnTerminalizer {
    private enum State { OPEN, FREEZING, FROZEN, BROKEN }

    private final int turnNumber;
    private final long startNanos;
    private final Runnable freezeProbe;
    private State state = State.OPEN;
    private TurnSettlement settlement;
    private RetrievalTrace capturedRetrieval;
    private boolean retrievalConsumed;

    TurnTerminalizer(int turnNumber, long startNanos) {
        this(turnNumber, startNanos, () -> { });
    }

    TurnTerminalizer(int turnNumber, long startNanos, Runnable freezeProbe) {
        this.turnNumber = turnNumber;
        this.startNanos = startNanos;
        this.freezeProbe = Objects.requireNonNull(freezeProbe, "turn freeze probe");
    }

    synchronized TurnSettlement complete(Result result, RuntimeTurnContext ctx) {
        Objects.requireNonNull(result, "completed turn result");
        beginFreeze();
        try {
            if (result instanceof Result.Ok ok) {
                result = new Result.Ok(ok.text, PreparedAnswer.freeze(ok.text, ctx, turnNumber));
            } else if (result instanceof Result.Streamed legacy) {
                String text = legacy.fullText.endsWith(legacy.suffix)
                        ? legacy.fullText : legacy.fullText + legacy.suffix;
                result = new Result.Streamed(legacy.fullText, legacy.suffix, PreparedAnswer.freeze(text, ctx, turnNumber));
            }
            RetrievalTrace retrieval = consumeRetrieval();
            updateFinalPhase(ctx);
            String assistantText = MemoryUpdateListener.extractText(result);
            String deliveredAssistantText = assistantText == null
                    ? ""
                    : MemoryUpdateListener.assistantTextForPersistence(
                            assistantText, TurnUserRequestCapture.get());
            String committedAssistantText = assistantText == null
                    ? ""
                    : TurnAuditCapture.currentContentRestrictions().forHistory(deliveredAssistantText);
            TurnAuditCapture.validateWorkStatusPresentation(
                    TurnUserRequestCapture.get(), deliveredAssistantText, committedAssistantText);
            TurnAuditCapture.settleReadOnlyResults(deliveredAssistantText, committedAssistantText,
                    assistantText != null && !dev.talos.core.util.UiChrome.containsTurnAbortMarker(assistantText)
                            && MemoryUpdateListener.isMemorizableAssistantReply(result, committedAssistantText));
            LocalTurnTraceCapture.recordAnswerTransformedForRouting(
                    committedAssistantText,
                    LocalTurnTraceCapture.AnswerTransformationReason.SESSION_COMMIT_NORMALIZATION);
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(deliveredAssistantText, committedAssistantText);
            String resultStatus = JsonTurnLogAppender.statusOf(result).toUpperCase(Locale.ROOT);
            LocalTurnTraceCapture.recordOutcomeIfAbsent(
                    resultStatus,
                    "NOT_RUN",
                    "UNKNOWN",
                    "UNKNOWN",
                    fallbackOutcomeClassification(
                            resultStatus, TurnAuditCapture.currentPolicyTrace()));
            TurnResult turnResult = freezeResult(result, retrieval);
            settlement = TurnSettlement.completed(
                    TurnExecutionStage.TURN_FINALIZATION, turnResult);
            state = State.FROZEN;
            return settlement;
        } catch (RuntimeException | Error failure) {
            state = State.BROKEN;
            throw failure;
        }
    }

    synchronized TurnSettlement noResult(RuntimeTurnContext ctx) {
        beginFreeze();
        try {
            RetrievalTrace retrieval = consumeRetrieval();
            updateFinalPhase(ctx);
            LocalTurnTraceCapture.closeProviderLifecycle();
            LocalTurnTraceCapture.recordOutcomeIfAbsent(
                    "NO_RESULT", "NOT_RUN", "UNKNOWN", "UNKNOWN", "NO_MODE_RESULT");
            LocalTurnTraceCapture.prepareForTerminalSettlement();
            LocalTurnTraceCapture.recordTurnSettlement(
                    TurnSettlement.Kind.NO_RESULT,
                    TurnExecutionStage.MODE_ROUTING,
                    TurnFailureClassifier.Classification.none());
            TurnResult turnResult = freezeResult(
                    new Result.Info("(no result)"), retrieval);
            settlement = TurnSettlement.noResult(TurnExecutionStage.MODE_ROUTING, turnResult);
            state = State.FROZEN;
            return settlement;
        } catch (RuntimeException | Error failure) {
            state = State.BROKEN;
            throw failure;
        }
    }

    synchronized TurnSettlement fail(
            Throwable throwable,
            TurnExecutionStage failedAt,
            RuntimeTurnContext ctx
    ) {
        beginFreeze();
        try {
            TurnFailureClassifier.Classification failure =
                    TurnFailureClassifier.classify(throwable);
            TurnSettlement.Kind kind = failure.kind() == TurnFailureClassifier.ErrorKind.CANCELLATION
                    ? TurnSettlement.Kind.CANCELLED
                    : TurnSettlement.Kind.FAILED;
            RetrievalTrace retrieval = consumeRetrieval();
            updateFinalPhase(ctx);
            LocalTurnTraceCapture.closeProviderLifecycle();
            LocalTurnTraceCapture.recordOutcomeIfAbsent(
                    kind == TurnSettlement.Kind.CANCELLED ? "ABORTED" : "ERROR",
                    "NOT_RUN",
                    "UNKNOWN",
                    "UNKNOWN",
                    kind == TurnSettlement.Kind.CANCELLED ? "TURN_CANCELLED" : "TURN_FAILED");
            LocalTurnTraceCapture.prepareForTerminalSettlement();
            LocalTurnTraceCapture.recordTurnSettlement(kind, failedAt, failure);
            TurnResult turnResult = freezeResult(
                    new Result.Error("", failure.code()), retrieval);
            settlement = TurnSettlement.abnormal(failedAt, turnResult, failure);
            state = State.FROZEN;
            return settlement;
        } catch (RuntimeException | Error terminalFailure) {
            state = State.BROKEN;
            throw terminalFailure;
        }
    }

    synchronized TurnSettlement failWithoutActiveTrace(
            Throwable throwable,
            TurnExecutionStage failedAt,
            RuntimeTurnContext ctx,
            String traceId,
            String sessionId,
            String timestamp,
            String userInput
    ) {
        beginFreeze();
        try {
            TurnFailureClassifier.Classification failure =
                    TurnFailureClassifier.classify(throwable);
            TurnSettlement.Kind kind = failure.kind()
                    == TurnFailureClassifier.ErrorKind.CANCELLATION
                    ? TurnSettlement.Kind.CANCELLED
                    : TurnSettlement.Kind.FAILED;
            RetrievalTrace retrieval = consumeRetrieval();
            LocalTurnTrace trace = LocalTurnTrace.builder(
                            traceId, sessionId, turnNumber, timestamp)
                    .workspaceHash(sessionId)
                    .mode("unknown")
                    .promptSummary(userInput)
                    .event(TurnTraceEvent.simple("TRACE_STARTED", timestamp, Map.of(
                            "turnNumber", turnNumber,
                            "redactionMode", "DEFAULT",
                            "initialization", "DEGRADED")))
                    .event(TurnSettlementTraceEvent.create(
                            timestamp, kind, failedAt, failure))
                    .event(TurnTraceEvent.simple("TRACE_COMPLETED", timestamp, Map.of()))
                    .outcome(
                            kind == TurnSettlement.Kind.CANCELLED ? "ABORTED" : "ERROR",
                            "NOT_RUN",
                            "UNKNOWN",
                            "UNKNOWN",
                            "TURN_INITIALIZATION_FAILED")
                    .build();
            TurnAudit audit = TurnAuditCapture.isActive()
                    ? TurnAuditCapture.end().withLocalTrace(trace)
                    : TurnAudit.empty().withLocalTrace(trace);
            TurnResult turnResult = new TurnResult(
                    new Result.Error("", failure.code()),
                    retrieval,
                    turnNumber,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos)),
                    audit);
            settlement = TurnSettlement.abnormal(failedAt, turnResult, failure);
            state = State.FROZEN;
            return settlement;
        } catch (RuntimeException | Error terminalFailure) {
            state = State.BROKEN;
            throw terminalFailure;
        }
    }

    synchronized TurnSettlement settlement() {
        return settlement;
    }

    /**
     * Last-resort, non-destructive settlement after ordinary terminalization
     * has failed. This deliberately does not retry any capture finalizer.
     */
    synchronized TurnSettlement recoverAfterTerminalizationFailure(
            Throwable primaryFailure,
            TurnExecutionStage failedAt,
            Throwable terminalizationFailure,
            String traceId,
            String sessionId,
            String timestamp
    ) {
        if (state != State.BROKEN || settlement != null) {
            throw new IllegalStateException(
                    "terminalization recovery requires one broken, unsettled turn");
        }
        TurnFailureClassifier.Classification failure =
                TurnFailureClassifier.classify(primaryFailure);
        TurnSettlement.Kind kind = failure.kind()
                == TurnFailureClassifier.ErrorKind.CANCELLATION
                ? TurnSettlement.Kind.CANCELLED
                : TurnSettlement.Kind.FAILED;
        String safeTimestamp = timestamp == null ? "" : timestamp;
        String terminalizationClass = terminalizationFailure == null
                ? ""
                : terminalizationFailure.getClass().getName();
        LocalTurnTrace trace = LocalTurnTrace.builder(
                        traceId, sessionId, turnNumber, safeTimestamp)
                .workspaceHash(sessionId)
                .mode("unknown")
                .event(TurnTraceEvent.simple("TRACE_STARTED", safeTimestamp, Map.of(
                        "turnNumber", turnNumber,
                        "redactionMode", "DEFAULT",
                        "initialization", "TERMINALIZATION_RECOVERY")))
                .event(TurnTraceEvent.simple(
                        "TURN_TERMINALIZATION_RECOVERED",
                        safeTimestamp,
                        Map.of(
                                "schemaVersion", 1,
                                "exceptionClass", terminalizationClass)))
                .event(TurnSettlementTraceEvent.create(
                        safeTimestamp, kind, failedAt, failure))
                .event(TurnTraceEvent.simple("TRACE_COMPLETED", safeTimestamp, Map.of()))
                .outcome(
                        kind == TurnSettlement.Kind.CANCELLED ? "ABORTED" : "ERROR",
                        "NOT_RUN",
                        "UNKNOWN",
                        "UNKNOWN",
                        kind == TurnSettlement.Kind.CANCELLED
                                ? "TURN_CANCELLED"
                                : "TURN_FAILED")
                .build();
        TurnResult result = new TurnResult(
                new Result.Error("", failure.code()),
                consumeRetrieval(),
                turnNumber,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos)),
                TurnAudit.empty().withLocalTrace(trace));
        settlement = TurnSettlement.abnormal(failedAt, result, failure);
        state = State.FROZEN;
        return settlement;
    }

    private void beginFreeze() {
        if (state != State.OPEN) {
            throw new IllegalStateException("turn already has a terminal settlement");
        }
        state = State.FREEZING;
        try {
            freezeProbe.run();
        } catch (RuntimeException | Error failure) {
            state = State.BROKEN;
            throw failure;
        }
    }

    private RetrievalTrace consumeRetrieval() {
        if (!retrievalConsumed) {
            capturedRetrieval = TurnTraceCapture.consume();
            retrievalConsumed = true;
        }
        return capturedRetrieval;
    }

    private TurnResult freezeResult(
            Result result,
            RetrievalTrace retrieval
    ) {
        LocalTurnTrace localTrace = LocalTurnTraceCapture.complete();
        TurnAudit audit = TurnAuditCapture.end()
                .withLocalTrace(localTrace);
        return new TurnResult(
                result,
                retrieval,
                turnNumber,
                Duration.ofNanos(Math.max(0L, System.nanoTime() - startNanos)),
                audit);
    }

    private static void updateFinalPhase(RuntimeTurnContext ctx) {
        if (ctx != null && ctx.executionPhaseState() != null) {
            TurnAuditCapture.updateFinalPhase(ctx.executionPhaseState().phase().name());
        }
    }

    private static String fallbackOutcomeClassification(
            String resultStatus,
            TurnPolicyTrace policyTrace
    ) {
        if ("OK".equals(resultStatus)
                && policyTrace != null
                && policyTrace.hasPolicyData()
                && !policyTrace.mutationAllowed()) {
            return TaskCompletionStatus.READ_ONLY_ANSWERED.name();
        }
        return "TURN_RECORDED";
    }
}
