package dev.talos.runtime.trace;

import dev.talos.spi.types.ProviderResponseCapture;

import java.time.Instant;
import java.util.Map;

/** Records hash-only evidence that deterministic runtime code selected the terminal answer. */
final class DeterministicAnswerTraceRecorder {
    private DeterministicAnswerTraceRecorder() {}

    static void record(
            LocalTurnTrace.Builder builder,
            String answer,
            ProviderResponseCapture.AnswerOrigin origin) {
        if (builder == null || !isDeterministic(origin)) return;
        builder.event(TurnTraceEvent.simple(
                "DETERMINISTIC_ANSWER_SELECTED",
                Instant.now().toString(),
                Map.of(
                        "answerHash", TraceRedactor.hash(answer),
                        "answerChars", answer == null ? 0 : answer.length(),
                        "origin", origin.name())));
    }

    private static boolean isDeterministic(ProviderResponseCapture.AnswerOrigin origin) {
        return origin == ProviderResponseCapture.AnswerOrigin.RUNTIME_DETERMINISTIC
                || origin == ProviderResponseCapture.AnswerOrigin.TOOL_DETERMINISTIC
                || origin == ProviderResponseCapture.AnswerOrigin.OPERATOR;
    }
}
