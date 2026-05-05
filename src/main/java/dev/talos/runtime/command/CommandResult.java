package dev.talos.runtime.command;

/** Runtime-owned result of a bounded command execution attempt. */
public record CommandResult(
        CommandPlan plan,
        int exitCode,
        long durationMs,
        boolean timedOut,
        boolean killed,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean redactionApplied,
        String errorMessage
) {
    public CommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean success() {
        return !timedOut && exitCode == 0 && errorMessage.isBlank();
    }

    static CommandResult internalFailure(CommandPlan plan, long durationMs, String message) {
        return new CommandResult(
                plan,
                -1,
                durationMs,
                false,
                false,
                "",
                "",
                false,
                false,
                false,
                message);
    }
}
