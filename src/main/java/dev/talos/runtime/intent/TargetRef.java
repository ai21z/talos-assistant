package dev.talos.runtime.intent;

import java.util.Objects;

public record TargetRef(
        String path,
        TargetRole role,
        IntentDerivation derivation
) {
    public TargetRef {
        path = normalizePath(path);
        role = Objects.requireNonNull(role, "role must not be null");
        derivation = derivation == null ? IntentDerivation.unknown() : derivation;
    }

    public static TargetRef of(String path, TargetRole role) {
        return new TargetRef(path, role, IntentDerivation.unknown());
    }

    public static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("target path must not be blank");
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
