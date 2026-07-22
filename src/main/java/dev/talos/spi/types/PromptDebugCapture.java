package dev.talos.spi.types;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local holder for the latest prompt debug snapshot. */
public final class PromptDebugCapture {
    public static final String BACKGROUND_MAINTENANCE_TAG = "prompt-debug:background-maintenance";

    private static final AtomicReference<PromptDebugSnapshot> LATEST_RECORDED = new AtomicReference<>();
    private static final AtomicReference<PromptDebugSnapshot> LATEST_USER_FACING = new AtomicReference<>();
    private static final AtomicReference<List<PromptDebugSnapshot>> USER_FACING_HISTORY =
            new AtomicReference<>(List.of());
    private static final AtomicReference<Boolean> LAST_TURN_WITHOUT_PROVIDER_REQUEST =
            new AtomicReference<>(false);
    private static final AtomicReference<Map<String, String>> TURN_DIAGNOSTICS =
            new AtomicReference<>(Map.of());
    private static final ThreadLocal<Boolean> TURN_ACTIVE = new ThreadLocal<>();

    private PromptDebugCapture() {}

    public static void record(PromptDebugSnapshot snapshot) {
        if (snapshot != null) {
            boolean backgroundMaintenance = isBackgroundMaintenance(snapshot);
            PromptDebugSnapshot enriched = backgroundMaintenance
                    ? snapshot
                    : snapshot.withDiagnostics(TURN_DIAGNOSTICS.get());
            LAST_TURN_WITHOUT_PROVIDER_REQUEST.set(false);
            LATEST_RECORDED.set(enriched);
            if (!backgroundMaintenance) {
                LATEST_USER_FACING.set(enriched);
                USER_FACING_HISTORY.updateAndGet(existing -> {
                    var copy = new java.util.ArrayList<>(
                            existing == null ? List.of() : existing);
                    copy.add(enriched);
                    return List.copyOf(copy);
                });
            }
        }
    }

    /** Starts a new user-visible assistant turn before any provider request is known. */
    public static void beginTurn() {
        LATEST_RECORDED.set(null);
        LATEST_USER_FACING.set(null);
        USER_FACING_HISTORY.set(List.of());
        LAST_TURN_WITHOUT_PROVIDER_REQUEST.set(true);
        TURN_DIAGNOSTICS.set(Map.of());
        TURN_ACTIVE.set(true);
    }

    /** Opens a direct-executor scope without resetting a runtime-owned outer turn. */
    public static TurnScope openTurnIfAbsent() {
        boolean owned = !Boolean.TRUE.equals(TURN_ACTIVE.get());
        if (owned) beginTurn();
        return new TurnScope(owned);
    }

    /** Ends mutable turn diagnostics while retaining the inspectable snapshot. */
    public static void endTurn() {
        TURN_DIAGNOSTICS.set(Map.of());
        TURN_ACTIVE.remove();
    }

    public static final class TurnScope implements AutoCloseable {
        private final boolean owned;
        private boolean closed;

        private TurnScope(boolean owned) {
            this.owned = owned;
        }

        @Override
        public void close() {
            if (!closed && owned) {
                closed = true;
                endTurn();
            }
        }
    }

    /** Adds turn-scoped prompt-debug metadata to the next user-facing capture. */
    public static void putTurnDiagnostic(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        TURN_DIAGNOSTICS.updateAndGet(existing -> {
            java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>(
                    existing == null ? Map.of() : existing);
            merged.put(key.strip(), value.strip());
            return Map.copyOf(merged);
        });
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

    public static boolean lastTurnHadNoProviderRequest() {
        return Boolean.TRUE.equals(LAST_TURN_WITHOUT_PROVIDER_REQUEST.get());
    }

    public static void clear() {
        LATEST_RECORDED.set(null);
        LATEST_USER_FACING.set(null);
        USER_FACING_HISTORY.set(List.of());
        LAST_TURN_WITHOUT_PROVIDER_REQUEST.set(false);
        TURN_DIAGNOSTICS.set(Map.of());
        TURN_ACTIVE.remove();
    }

    private static boolean isBackgroundMaintenance(PromptDebugSnapshot snapshot) {
        return snapshot.controls().debugTags().stream()
                .anyMatch(BACKGROUND_MAINTENANCE_TAG::equals);
    }
}
