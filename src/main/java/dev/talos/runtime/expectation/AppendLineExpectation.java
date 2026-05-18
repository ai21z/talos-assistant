package dev.talos.runtime.expectation;

/** Exact final-line expectation derived from explicit append-line wording. */
public record AppendLineExpectation(
        String targetPath,
        String expectedLine,
        String sourcePattern
) implements TaskExpectation {

    public AppendLineExpectation {
        targetPath = targetPath == null ? "" : normalizePath(targetPath);
        expectedLine = expectedLine == null ? "" : expectedLine.strip();
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "APPEND_LINE";
    }

    public String expectedHash() {
        return LiteralContentExpectation.hash(expectedLine);
    }

    public int expectedBytes() {
        return LiteralContentExpectation.byteCount(expectedLine);
    }

    public int expectedChars() {
        return LiteralContentExpectation.charCount(expectedLine);
    }

    private static String normalizePath(String path) {
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
