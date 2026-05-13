package dev.talos.runtime;

import dev.talos.runtime.toolcall.ToolCallSupport;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-thread, per-assistant-turn read evidence for source-derived artifacts.
 *
 * <p>A single assistant turn can run more than one {@link ToolCallLoop}, for
 * example a read-only pass followed by a bounded mutation retry. Loop-local
 * state is not enough for source-derived artifacts: a write in the retry loop
 * must still see the source read that happened earlier in the same turn.
 */
public final class TurnSourceEvidenceCapture {
    private static final ThreadLocal<Set<String>> HOLDER = new ThreadLocal<>();

    private TurnSourceEvidenceCapture() {}

    public static void begin() {
        HOLDER.set(new LinkedHashSet<>());
    }

    public static void recordRead(String path) {
        String normalized = normalize(path);
        if (normalized.isBlank()) return;
        Set<String> paths = HOLDER.get();
        if (paths == null) {
            return;
        }
        paths.add(normalized);
    }

    public static Set<String> readPaths() {
        Set<String> paths = HOLDER.get();
        return paths == null ? Set.of() : Set.copyOf(paths);
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static String normalize(String path) {
        String normalized = ToolCallSupport.normalizePath(path).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
