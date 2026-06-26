package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public record CheckpointConfig(
        boolean enabled,
        boolean failClosed,
        long maxFileBytes,
        long maxTurnBytes,
        Path root
) {
    private static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final long DEFAULT_MAX_TURN_BYTES = 50L * 1024L * 1024L;

    public CheckpointConfig {
        if (maxFileBytes <= 0) maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        if (maxTurnBytes <= 0) maxTurnBytes = DEFAULT_MAX_TURN_BYTES;
        if (root == null) root = defaultRoot();
    }

    public static CheckpointConfig from(Config config) {
        Map<String, Object> map = checkpointMap(config);
        return new CheckpointConfig(
                bool(map.get("enabled"), true),
                bool(map.get("fail_closed"), true),
                longVal(map.get("max_file_bytes"), DEFAULT_MAX_FILE_BYTES),
                longVal(map.get("max_turn_bytes"), DEFAULT_MAX_TURN_BYTES),
                pathVal(map.get("root"), defaultRoot()));
    }

    public static Path defaultRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) home = System.getenv("USERPROFILE");
        if (home == null || home.isBlank()) home = ".";
        return Path.of(home, ".talos", "checkpoints");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkpointMap(Config config) {
        if (config == null) return Map.of();
        Object raw = config.data.get("checkpoint");
        if (raw instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    private static long longVal(Object raw, long fallback) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Path pathVal(Object raw, Path fallback) {
        if (raw instanceof String s && !s.isBlank()) return Path.of(s);
        return fallback;
    }
}
