package dev.talos.runtime.expectation;

/** Old-text/new-text replacement expectation derived from explicit user wording. */
public record ReplacementExpectation(
        String targetPath,
        String oldText,
        String newText,
        String sourcePattern
) implements TaskExpectation {

    public ReplacementExpectation {
        targetPath = targetPath == null ? "" : normalizePath(targetPath);
        oldText = oldText == null ? "" : oldText.strip();
        newText = newText == null ? "" : newText.strip();
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "TEXT_REPLACEMENT";
    }

    public String oldHash() {
        return LiteralContentExpectation.hash(oldText);
    }

    public String newHash() {
        return LiteralContentExpectation.hash(newText);
    }

    public int newBytes() {
        return LiteralContentExpectation.byteCount(newText);
    }

    public int newChars() {
        return LiteralContentExpectation.charCount(newText);
    }

    private static String normalizePath(String path) {
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
