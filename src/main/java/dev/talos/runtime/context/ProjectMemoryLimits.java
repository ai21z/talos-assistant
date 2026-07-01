package dev.talos.runtime.context;

/** Bounded project-memory read and render budgets. */
public record ProjectMemoryLimits(
        int maxFiles,
        int maxUserMemoryFiles,
        int maxBytesPerFile,
        int maxCharsPerFile,
        int maxLinesPerFile,
        int totalChars
) {
    public ProjectMemoryLimits {
        maxFiles = Math.max(1, maxFiles);
        maxUserMemoryFiles = Math.max(0, maxUserMemoryFiles);
        maxBytesPerFile = Math.max(256, maxBytesPerFile);
        maxCharsPerFile = Math.max(128, maxCharsPerFile);
        maxLinesPerFile = Math.max(1, maxLinesPerFile);
        totalChars = Math.max(256, totalChars);
    }

    public static ProjectMemoryLimits defaults() {
        return new ProjectMemoryLimits(
                8,
                3,
                256 * 1024,
                12_000,
                200,
                16_000);
    }
}
