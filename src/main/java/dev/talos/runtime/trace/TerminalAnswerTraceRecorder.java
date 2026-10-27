package dev.talos.runtime.trace;

import dev.talos.spi.types.ProviderResponseCapture;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records the explicit answer-origin decision made at the routing boundary. */
final class TerminalAnswerTraceRecorder {
    private TerminalAnswerTraceRecorder() {}

    static void record(
            LocalTurnTrace.Builder builder,
            String answer,
            ProviderResponseCapture.AnswerOrigin origin,
            String sourceGenerationId) {
        record(builder, answer, origin, sourceGenerationId, "");
    }

    static void record(
            LocalTurnTrace.Builder builder,
            String answer,
            ProviderResponseCapture.AnswerOrigin origin,
            String sourceGenerationId,
            String sourceAttemptId) {
        if (builder == null || origin == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answerHash", TraceRedactor.hash(answer));
        data.put("answerChars", answer == null ? 0 : answer.length());
        data.put("origin", origin.name());
        if (sourceGenerationId != null && !sourceGenerationId.isBlank()) {
            data.put("sourceGenerationId", sourceGenerationId.strip());
        }
        if (sourceAttemptId != null && !sourceAttemptId.isBlank()) {
            data.put("sourceAttemptId", sourceAttemptId.strip());
        }
        builder.event(TurnTraceEvent.simple(
                "TERMINAL_ANSWER_SELECTED", Instant.now().toString(), data));
    }
}
