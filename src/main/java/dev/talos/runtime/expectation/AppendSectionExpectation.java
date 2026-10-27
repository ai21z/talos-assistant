package dev.talos.runtime.expectation;

/** Explicit Markdown section and sentence expectation for an append-only request. */
public record AppendSectionExpectation(
        String targetPath,
        String sectionName,
        String expectedSentence,
        String sourcePattern
) implements TaskExpectation {

    public AppendSectionExpectation {
        targetPath = targetPath == null ? "" : normalizePath(targetPath);
        sectionName = sectionName == null ? "" : sectionName.strip();
        expectedSentence = expectedSentence == null ? "" : expectedSentence.strip();
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "APPEND_SECTION";
    }

    public String expectedContent() {
        return sectionName + "\n" + expectedSentence;
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
