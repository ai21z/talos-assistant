package dev.talos.runtime.expectation;

/** Exact markdown/list bullet count expectation derived from explicit user wording. */
public record BulletListExpectation(
        String targetPath,
        int expectedBulletCount,
        String sourcePattern
) implements TaskExpectation {

    public BulletListExpectation {
        targetPath = normalizePath(targetPath);
        expectedBulletCount = Math.max(0, expectedBulletCount);
        sourcePattern = sourcePattern == null ? "" : sourcePattern.strip();
    }

    @Override
    public String kind() {
        return "BULLET_LIST_COUNT";
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
