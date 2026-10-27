package dev.talos.runtime.expectation;

/** Explicit label and statement expectation for one append-only line. */
public record AppendStatementExpectation(
        String targetPath,
        String label,
        String statement,
        String sourcePattern
) implements TaskExpectation {

    public AppendStatementExpectation {
        targetPath = targetPath == null ? "" : normalizePath(targetPath);
        label = label == null ? "" : label.strip();
        statement = statement == null ? "" : statement.strip();
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "APPEND_STATEMENT";
    }

    public String expectedContent() {
        return label + ": " + statement;
    }

    public String expectedHash() {
        return LiteralContentExpectation.hash(expectedContent());
    }

    public int expectedBytes() {
        return LiteralContentExpectation.byteCount(expectedContent());
    }

    public int expectedChars() {
        return LiteralContentExpectation.charCount(expectedContent());
    }

    private static String normalizePath(String path) {
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
