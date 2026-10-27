package dev.talos.runtime;

import java.util.Objects;

/** Terminal envelope with an independently frozen retrieval snapshot. */
public record TurnSettlement(
        Kind kind,
        TurnExecutionStage stage,
        TurnResult result,
        TurnFailureClassifier.Classification failure
) {
    public enum Kind {
        COMPLETED,
        NO_RESULT,
        FAILED,
        CANCELLED
    }

    public TurnSettlement {
        kind = Objects.requireNonNull(kind, "turn settlement kind");
        stage = stage == null ? TurnExecutionStage.UNKNOWN : stage;
        result = Objects.requireNonNull(result, "settled turn result");
        if (result.trace() != null) {
            result = new TurnResult(
                    result.result(),
                    result.trace().immutableSnapshot(),
                    result.turnNumber(),
                    result.elapsed(),
                    result.audit());
        }
        if (kind != Kind.COMPLETED) {
            result = new TurnResult(
                    result.result(),
                    result.trace(),
                    result.turnNumber(),
                    result.elapsed(),
                    result.audit().safeTerminalProjection(
                            kind,
                            result.trace() != null
                                    && result.trace().hasPersistableActivity()));
        }
        failure = failure == null ? TurnFailureClassifier.Classification.none() : failure;
        boolean failed = kind == Kind.FAILED || kind == Kind.CANCELLED;
        if (failed != (failure.kind() != TurnFailureClassifier.ErrorKind.NONE)) {
            throw new IllegalArgumentException(
                    "settlement kind and failure classification must agree");
        }
        if (kind == Kind.CANCELLED
                && failure.kind() != TurnFailureClassifier.ErrorKind.CANCELLATION) {
            throw new IllegalArgumentException(
                    "a cancelled settlement requires cancellation evidence");
        }
        if (kind == Kind.FAILED
                && failure.kind() == TurnFailureClassifier.ErrorKind.CANCELLATION) {
            throw new IllegalArgumentException(
                    "cancellation evidence cannot be published as a failed settlement");
        }
        if (failed && !(result.result() instanceof Result.Error)) {
            throw new IllegalArgumentException(
                    "an abnormal settlement requires a non-conversational error result");
        }
        if (kind == Kind.NO_RESULT && !(result.result() instanceof Result.Info)) {
            throw new IllegalArgumentException(
                    "a no-result settlement requires an informational result");
        }
    }

    public boolean ordinaryCompletion() {
        return kind == Kind.COMPLETED;
    }

    public static TurnSettlement completed(TurnExecutionStage stage, TurnResult result) {
        return new TurnSettlement(
                Kind.COMPLETED, stage, result, TurnFailureClassifier.Classification.none());
    }

    public static TurnSettlement noResult(TurnExecutionStage stage, TurnResult result) {
        return new TurnSettlement(
                Kind.NO_RESULT, stage, result, TurnFailureClassifier.Classification.none());
    }

    public static TurnSettlement abnormal(
            TurnExecutionStage stage,
            TurnResult result,
            TurnFailureClassifier.Classification failure
    ) {
        Kind kind = failure != null
                && failure.kind() == TurnFailureClassifier.ErrorKind.CANCELLATION
                ? Kind.CANCELLED
                : Kind.FAILED;
        return new TurnSettlement(kind, stage, result, failure);
    }
}
