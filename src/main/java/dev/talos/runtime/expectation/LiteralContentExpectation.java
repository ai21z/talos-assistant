package dev.talos.runtime.expectation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact full-file content expectation for explicit literal overwrite requests. */
public record LiteralContentExpectation(
        String targetPath,
        String expectedContent,
        MatchMode matchMode,
        String sourcePattern
) implements TaskExpectation {
    public enum MatchMode {
        EXACT
    }

    public LiteralContentExpectation {
        targetPath = targetPath == null ? "" : normalizePath(targetPath);
        expectedContent = expectedContent == null ? "" : expectedContent;
        matchMode = matchMode == null ? MatchMode.EXACT : matchMode;
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "LITERAL_CONTENT";
    }

    public String expectedHash() {
        return sha256(expectedContent);
    }

    public int expectedBytes() {
        return expectedContent.getBytes(StandardCharsets.UTF_8).length;
    }

    public int expectedChars() {
        return expectedContent.length();
    }

    public int expectedLines() {
        return lineCount(expectedContent);
    }

    public static String hash(String content) {
        return sha256(content == null ? "" : content);
    }

    public static int byteCount(String content) {
        return (content == null ? "" : content).getBytes(StandardCharsets.UTF_8).length;
    }

    public static int charCount(String content) {
        return content == null ? 0 : content.length();
    }

    public static int lineCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\\R", -1).length;
    }

    private static String normalizePath(String path) {
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
