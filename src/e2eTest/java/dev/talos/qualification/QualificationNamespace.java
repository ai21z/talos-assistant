package dev.talos.qualification;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** One canonical path-segment contract for qualification identities and artifacts. */
final class QualificationNamespace {
    private static final Pattern SAFE_SEGMENT = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?");
    private static final Set<String> WINDOWS_DEVICE_BASENAMES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CLOCK$",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private QualificationNamespace() {}

    static String requireSegment(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        Path candidate;
        try {
            candidate = Path.of(normalized);
        } catch (RuntimeException invalidPath) {
            throw invalid(label);
        }
        String deviceBase = normalized.contains(".")
                ? normalized.substring(0, normalized.indexOf('.'))
                : normalized;
        if (!normalized.equals(value)
                || !SAFE_SEGMENT.matcher(normalized).matches()
                || candidate.isAbsolute()
                || candidate.getRoot() != null
                || candidate.getNameCount() != 1
                || WINDOWS_DEVICE_BASENAMES.contains(deviceBase.toUpperCase(Locale.ROOT))) {
            throw invalid(label);
        }
        return normalized;
    }

    static boolean isSegment(String value) {
        try {
            requireSegment(value, "qualification identity");
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static IllegalArgumentException invalid(String label) {
        return new IllegalArgumentException(label + " is not a safe path segment");
    }
}
