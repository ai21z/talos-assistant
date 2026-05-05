package dev.talos.runtime.command;

import java.util.List;

/** Immutable definition of one allowed command shape. */
public record CommandProfile(
        String id,
        String displayName,
        String executable,
        List<String> fixedArgs,
        CommandRisk risk,
        boolean networkAccess,
        boolean interactive,
        List<String> expectedWrites,
        boolean requiresApproval,
        boolean requiresCheckpoint,
        long defaultTimeoutMs,
        long defaultIdleTimeoutMs,
        CommandOutputLimits outputLimits
) {
    public static final long DEFAULT_TIMEOUT_MS = 120_000;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 30_000;

    public CommandProfile {
        id = require(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.strip();
        executable = require(executable, "executable");
        fixedArgs = immutableClean(fixedArgs);
        risk = risk == null ? CommandRisk.UNKNOWN : risk;
        expectedWrites = immutableClean(expectedWrites);
        defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : DEFAULT_TIMEOUT_MS;
        defaultIdleTimeoutMs = defaultIdleTimeoutMs > 0 ? defaultIdleTimeoutMs : DEFAULT_IDLE_TIMEOUT_MS;
        outputLimits = outputLimits == null ? CommandOutputLimits.defaults() : outputLimits;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Command profile " + field + " is required.");
        }
        return value.strip();
    }

    private static List<String> immutableClean(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .toList();
    }
}
