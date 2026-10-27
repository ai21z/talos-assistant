package dev.talos.runtime;

import dev.talos.spi.types.ProviderResponseCapture;

/** Runtime-owned watchdog failure; classification never inspects diagnostic prose. */
public final class GenerationWatchdogException extends RuntimeException {
    private final ProviderResponseCapture.Abandonment reason;

    public GenerationWatchdogException(ProviderResponseCapture.Abandonment reason) {
        super(message(reason));
        this.reason = reason;
    }

    public ProviderResponseCapture.Abandonment reason() { return reason; }

    private static String message(ProviderResponseCapture.Abandonment reason) {
        if (reason == null) throw new IllegalArgumentException("watchdog cause is required");
        return switch (reason) {
            case REPETITION_LOOP -> "turn generation stopped: repetition loop detected";
            case IDLE_TIMEOUT -> "turn generation timed out: idle timeout (no tokens)";
            case WALL_CLOCK_TIMEOUT -> "turn generation timed out: wall-clock budget exceeded";
            default -> throw new IllegalArgumentException("not a watchdog cause");
        };
    }
}
