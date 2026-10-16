package dev.talos.runtime;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ProviderResponseCapture;

/** A missing completed candidate is not a safe partial answer or a new execution permit. */
public final class IncompleteGenerationPresentation {
    public static final String NOTICE = "[Answer incomplete: uncommitted provider text was withheld. "
            + "Execution and verification evidence are reported separately.]";

    private IncompleteGenerationPresentation() { }

    public static boolean required(LlmClient.StreamResult result) {
        return result != null && !result.aborted() && (result.truncated() || result.outputLimitReached()
                || result.completion() == ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP
                || result.completion() == ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS
                || result.completion() == ProviderResponseCapture.Completion.RUNTIME_ABORT);
    }

    public static void record(String selected) {
        LocalTurnTraceCapture.recordUncommittedProviderAnswer(selected);
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
