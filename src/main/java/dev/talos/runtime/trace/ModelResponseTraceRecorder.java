package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

final class ModelResponseTraceRecorder {
    private ModelResponseTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, String assistantText) {
        if (builder == null) return;
        builder.assistantSummary(assistantText);
        builder.event(TurnTraceEvent.simple("MODEL_RESPONSE_RECEIVED", Instant.now().toString(), Map.of(
                "assistantHash", TraceRedactor.hash(assistantText),
                "assistantChars", assistantText == null ? 0 : assistantText.length())));
    }
}
