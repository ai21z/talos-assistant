package dev.talos.runtime.command;

/** Output capture caps for a planned command. */
public record CommandOutputLimits(
        int stdoutLimitBytes,
        int stderrLimitBytes,
        int traceSummaryLimitBytes
) {
    public static final int DEFAULT_STREAM_LIMIT_BYTES = 65_536;
    public static final int DEFAULT_TRACE_SUMMARY_LIMIT_BYTES = 16_384;

    public CommandOutputLimits {
        stdoutLimitBytes = positiveOrDefault(stdoutLimitBytes, DEFAULT_STREAM_LIMIT_BYTES);
        stderrLimitBytes = positiveOrDefault(stderrLimitBytes, DEFAULT_STREAM_LIMIT_BYTES);
        traceSummaryLimitBytes = positiveOrDefault(traceSummaryLimitBytes, DEFAULT_TRACE_SUMMARY_LIMIT_BYTES);
    }

    public static CommandOutputLimits defaults() {
        return new CommandOutputLimits(
                DEFAULT_STREAM_LIMIT_BYTES,
                DEFAULT_STREAM_LIMIT_BYTES,
                DEFAULT_TRACE_SUMMARY_LIMIT_BYTES);
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
