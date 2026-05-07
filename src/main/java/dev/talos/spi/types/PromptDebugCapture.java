package dev.talos.spi.types;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local holder for the latest prompt debug snapshot. */
public final class PromptDebugCapture {
    public static final String BACKGROUND_MAINTENANCE_TAG = "prompt-debug:background-maintenance";

    private static final AtomicReference<PromptDebugSnapshot> LATEST_RECORDED = new AtomicReference<>();
    private static final AtomicReference<PromptDebugSnapshot> LATEST_USER_FACING = new AtomicReference<>();
    private static final AtomicReference<List<PromptDebugSnapshot>> USER_FACING_HISTORY =
            new AtomicReference<>(List.of());

    private PromptDebugCapture() {}

    public static void record(PromptDebugSnapshot snapshot) {
        if (snapshot != null) {
            LATEST_RECORDED.set(snapshot);
            if (!isBackgroundMaintenance(snapshot)) {
                LATEST_USER_FACING.set(snapshot);
                USER_FACING_HISTORY.updateAndGet(existing -> {
                    var copy = new java.util.ArrayList<>(
                            existing == null ? List.<PromptDebugSnapshot>of() : existing);
                    copy.add(snapshot);
                    return List.copyOf(copy);
                });
            }
        }
    }

    /**
     * Returns the latest user-facing prompt capture. Background maintenance
     * calls, such as conversation summarization, are intentionally excluded so
     * maintainer commands inspect the last audited assistant turn by default.
     */
    public static Optional<PromptDebugSnapshot> latest() {
        return Optional.ofNullable(LATEST_USER_FACING.get());
    }

    /** Returns the latest prompt capture of any kind, including maintenance calls. */
    public static Optional<PromptDebugSnapshot> latestRecorded() {
        return Optional.ofNullable(LATEST_RECORDED.get());
    }

    /** Returns user-facing prompt captures since the last clear, in record order. */
    public static List<PromptDebugSnapshot> history() {
        return USER_FACING_HISTORY.get();
    }

    public static void clear() {
        LATEST_RECORDED.set(null);
        LATEST_USER_FACING.set(null);
        USER_FACING_HISTORY.set(List.of());
    }

    private static boolean isBackgroundMaintenance(PromptDebugSnapshot snapshot) {
        return snapshot.controls().debugTags().stream()
                .anyMatch(BACKGROUND_MAINTENANCE_TAG::equals);
    }
}
