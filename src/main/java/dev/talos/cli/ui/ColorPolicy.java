package dev.talos.cli.ui;

import java.util.Locale;
import java.util.Map;

/**
 * Color policy requested by the user or inferred from environment.
 */
public enum ColorPolicy {
    AUTO,
    ALWAYS,
    NEVER;

    public static ColorPolicy parse(String value, ColorPolicy fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto" -> AUTO;
            case "always", "true", "1", "yes", "on" -> ALWAYS;
            case "never", "false", "0", "no", "off" -> NEVER;
            default -> fallback;
        };
    }

    public static ColorPolicy fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env, System.getProperty("talos.color"));
    }

    static ColorPolicy fromEnvironment(Map<String, String> env, String systemProperty) {
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        if (hasEnv(safeEnv, "NO_COLOR")) {
            return NEVER;
        }

        ColorPolicy fromProperty = parse(systemProperty, null);
        if (fromProperty != null) {
            return fromProperty;
        }

        String override = envValue(safeEnv, "TALOS_COLOR");
        ColorPolicy fromOverride = parse(override, null);
        return fromOverride == null ? AUTO : fromOverride;
    }

    static boolean hasEnv(Map<String, String> env, String key) {
        return envValue(env, key) != null;
    }

    static String envValue(Map<String, String> env, String key) {
        if (env == null || key == null) return null;
        String exact = env.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
