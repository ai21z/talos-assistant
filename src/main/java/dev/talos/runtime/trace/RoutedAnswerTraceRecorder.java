package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

/** Records the post-routing answer without asserting who authored it. */
final class RoutedAnswerTraceRecorder {
    private RoutedAnswerTraceRecorder() {}

    static void recordComponentsCommitted(
            LocalTurnTrace.Builder builder, String retainedAssistantText, boolean delivered, boolean retained) {
        if (builder == null) return;
        builder.event(TurnTraceEvent.simple(
                "READ_ONLY_ANSWER_COMPONENTS_COMMITTED",
                Instant.now().toString(),
                Map.of("deliveredIntact", delivered, "retainedIntact", retained,
                        "answerHash", TraceRedactor.hash(retainedAssistantText))));
    }

    static void record(LocalTurnTrace.Builder builder, String assistantText) {
        if (builder == null) return;
        builder.assistantSummary(assistantText);
        builder.event(TurnTraceEvent.simple(
                "ROUTED_ANSWER_CAPTURED",
                Instant.now().toString(),
                Map.of(
                        "answerHash", TraceRedactor.hash(assistantText),
                        "answerChars", assistantText == null ? 0 : assistantText.length())));
    }
}
