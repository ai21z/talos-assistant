package dev.talos.runtime;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ProviderResponseCapture;

/** A missing completed candidate is not a safe partial answer or a new execution permit. */
public final class IncompleteGenerationPresentation {
    public static final String NOTICE = "[Answer incomplete: uncommitted provider text was withheld. "
            + "Execution and verification evidence are reported separately.]";
    public static final String NOTICE_WITHOUT_TEXT = "[Answer incomplete: the provider generation ended "
            + "without text, so nothing was withheld. Execution and verification evidence are reported separately.]";

    private IncompleteGenerationPresentation() { }

    public static boolean required(LlmClient.StreamResult result) {
        return result != null && !result.aborted() && (result.truncated() || result.outputLimitReached()
                || result.completion() == ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP
                || result.completion() == ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS
                || result.completion() == ProviderResponseCapture.Completion.RUNTIME_ABORT);
    }

    /** The notice names what was dropped: provider text, or nothing when the generation held none. */
    public static String notice(LlmClient.StreamResult result) {
        // Without a result nothing is known about the text, and the existing wording is kept.
        return result != null && (result.text() == null || result.text().isBlank()) ? NOTICE_WITHOUT_TEXT : NOTICE;
    }

    public static void record(String selected) {
        LocalTurnTraceCapture.recordUncommittedProviderAnswer(selected);
    }

    /** Records the withheld answer with the result's own knowledge of whether provider text existed. */
    public static void record(String selected, LlmClient.StreamResult result) {
        LocalTurnTraceCapture.recordUncommittedProviderAnswer(selected,
                result == null ? null : result.text() != null && !result.text().isBlank());
    }

    public static boolean isAbortedFailure(Throwable failure) {
        return failure instanceof GenerationAbortedException;
    }

    public static RuntimeException abortedFailure(LlmClient.StreamResult result) {
        return switch (result.abortOrigin()) {
            case OPERATOR_EXTERNAL -> new java.util.concurrent.CancellationException("turn generation cancelled");
            case RUNTIME_THREAD_INTERRUPT -> new RuntimeException(new InterruptedException("turn generation interrupted"));
            case RUNTIME_WATCHDOG -> result.abandonment().<RuntimeException>map(cause -> switch (cause) {
                case REPETITION_LOOP, IDLE_TIMEOUT, WALL_CLOCK_TIMEOUT -> new GenerationWatchdogException(cause);
                default -> new GenerationAbortedException();
            }).orElseGet(GenerationAbortedException::new);
            case NONE, RUNTIME_STREAM_CLOSE, PROVIDER_TRANSPORT -> new GenerationAbortedException();
        };
    }

    private static final class GenerationAbortedException extends RuntimeException {
        GenerationAbortedException() { super("turn generation ended without a completed response"); }
    }
}
