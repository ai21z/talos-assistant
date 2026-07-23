package dev.talos.engine.llamacpp;

import java.util.Objects;

/** Exact path-free facts captured by the launcher that owns ProcessBuilder.start(). */
record LlamaCppProcessLaunchObservation(
        long processId,
        long processStartEpochMilli,
        long launchObservedEpochMilli,
        String launchNonce,
        String launchCommandSha256,
        String effectiveEnvironmentSha256) {

    LlamaCppProcessLaunchObservation {
        if (processId < 1) throw new IllegalArgumentException("process ID must be positive");
        if (processStartEpochMilli < 0) {
            throw new IllegalArgumentException("process start identity cannot be negative");
        }
        if (launchObservedEpochMilli < 1) {
            throw new IllegalArgumentException("launch observation must be positive");
        }
        launchNonce = pattern(launchNonce, "[0-9a-f]{32}", "launch nonce");
        launchCommandSha256 = pattern(
                launchCommandSha256, "[0-9a-f]{64}", "launch command SHA-256");
        effectiveEnvironmentSha256 = pattern(
                effectiveEnvironmentSha256, "[0-9a-f]{64}", "environment SHA-256");
    }

    boolean exactProcessIdentityAvailable() {
        return processStartEpochMilli > 0
                && launchObservedEpochMilli >= processStartEpochMilli;
    }

    boolean sameProcess(LlamaCppProcessLaunchObservation other) {
        return equals(other);
    }

    private static String pattern(String value, String regex, String label) {
        String exact = Objects.toString(value, "");
        if (!exact.matches(regex)) throw new IllegalArgumentException(label + " is invalid");
        return exact;
    }
}
