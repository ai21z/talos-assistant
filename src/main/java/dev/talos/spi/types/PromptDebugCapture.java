package dev.talos.spi.types;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local holder for the latest prompt debug snapshot. */
public final class PromptDebugCapture {
    private static final AtomicReference<PromptDebugSnapshot> LATEST = new AtomicReference<>();

    private PromptDebugCapture() {}

    public static void record(PromptDebugSnapshot snapshot) {
        if (snapshot != null) {
            LATEST.set(snapshot);
        }
    }

    public static Optional<PromptDebugSnapshot> latest() {
        return Optional.ofNullable(LATEST.get());
    }

    public static void clear() {
        LATEST.set(null);
    }
}
