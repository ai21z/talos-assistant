package dev.talos.runtime.command;

import java.nio.file.Path;
import java.util.List;

/** Runtime-owned plan for a command profile request. This does not execute anything. */
public record CommandPlan(
        String profileId,
        String displayName,
        String executable,
        List<String> argv,
        Path cwd,
        CommandRisk risk,
        boolean networkAccess,
        boolean interactive,
        List<String> expectedWrites,
        boolean requiresApproval,
        boolean requiresCheckpoint,
        long timeoutMs,
        long idleTimeoutMs,
        CommandOutputLimits outputLimits
) {
    public CommandPlan {
        profileId = profileId == null ? "" : profileId.strip();
        displayName = displayName == null ? "" : displayName.strip();
        executable = executable == null ? "" : executable.strip();
        argv = argv == null ? List.of() : List.copyOf(argv);
        cwd = cwd == null ? Path.of(".").toAbsolutePath().normalize() : cwd.toAbsolutePath().normalize();
        risk = risk == null ? CommandRisk.UNKNOWN : risk;
        expectedWrites = expectedWrites == null ? List.of() : List.copyOf(expectedWrites);
        timeoutMs = timeoutMs > 0 ? timeoutMs : CommandProfile.DEFAULT_TIMEOUT_MS;
        idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : CommandProfile.DEFAULT_IDLE_TIMEOUT_MS;
        outputLimits = outputLimits == null ? CommandOutputLimits.defaults() : outputLimits;
    }

    public CommandPlan withTimeoutMs(long timeoutMs) {
        return new CommandPlan(
                profileId,
                displayName,
                executable,
                argv,
                cwd,
                risk,
                networkAccess,
                interactive,
                expectedWrites,
                requiresApproval,
                requiresCheckpoint,
                timeoutMs,
                idleTimeoutMs,
                outputLimits);
    }
}
