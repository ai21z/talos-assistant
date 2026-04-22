package dev.talos.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages a temporary workspace directory for a scenario harness run.
 *
 * <p>Usage:
 * <pre>
 *   try (var ws = ScenarioWorkspaceFixture.empty()) {
 *       ws.write("index.html", "<html>...</html>");
 *       // run scenario against ws.path()
 *       ws.assertFileExists("index.html");
 *       ws.assertFileContains("index.html", "expected text");
 *   }
 * </pre>
 *
 * <p>The fixture creates an isolated temp dir and deletes it on close.
 */
public final class ScenarioWorkspaceFixture implements AutoCloseable {

    private final Path root;

    private ScenarioWorkspaceFixture(Path root) {
        this.root = root;
    }

    // ── Factory ─────────────────────────────────────────────────────

    /** Creates an empty temporary workspace. */
    public static ScenarioWorkspaceFixture empty() {
        try {
            Path dir = Files.createTempDirectory("talos-harness-");
            return new ScenarioWorkspaceFixture(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create harness workspace", e);
        }
    }

    /**
     * Creates a workspace pre-populated with the given files.
     *
     * @param files map of relative path → content (UTF-8)
     */
    public static ScenarioWorkspaceFixture withFiles(Map<String, String> files) {
        var ws = empty();
        files.forEach(ws::write);
        return ws;
    }

    /** Convenience builder for inline file definitions. */
    public static Builder builder() {
        return new Builder();
    }

    // ── Workspace operations ─────────────────────────────────────────

    /** Root path of the temporary workspace. */
    public Path path() {
        return root;
    }

    /** Resolve a relative path against the workspace root. */
    public Path resolve(String relativePath) {
        return root.resolve(relativePath);
    }

    /**
     * Write a file into the workspace (creates parent directories as needed).
     *
     * @param relativePath path relative to workspace root
     * @param content      UTF-8 content to write
     */
    public void write(String relativePath, String content) {
        try {
            Path target = root.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write workspace file: " + relativePath, e);
        }
    }

    /** Read a file from the workspace. */
    public String read(String relativePath) {
        try {
            return Files.readString(root.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read workspace file: " + relativePath, e);
        }
    }

    /** Return true if the given relative path exists in the workspace. */
    public boolean exists(String relativePath) {
        return Files.exists(root.resolve(relativePath));
    }

    // ── Assertions ───────────────────────────────────────────────────

    /**
     * Assert that a file exists in the workspace.
     *
     * @throws AssertionError if the file does not exist
     */
    public void assertFileExists(String relativePath) {
        if (!exists(relativePath)) {
            throw new AssertionError("Expected file to exist in workspace: " + relativePath
                    + " (workspace root: " + root + ")");
        }
    }

    /**
     * Assert that a file does NOT exist in the workspace.
     *
     * @throws AssertionError if the file exists
     */
    public void assertFileAbsent(String relativePath) {
        if (exists(relativePath)) {
            throw new AssertionError("Expected file to be absent from workspace: " + relativePath);
        }
    }

    /**
     * Assert that a file exists and its content contains the given substring.
     *
     * @throws AssertionError if file missing or content does not contain the substring
     */
    public void assertFileContains(String relativePath, String expectedSubstring) {
        assertFileExists(relativePath);
        String content = read(relativePath);
        if (!content.contains(expectedSubstring)) {
            throw new AssertionError("Expected file '" + relativePath + "' to contain: ["
                    + expectedSubstring + "]\nActual content:\n" + content);
        }
    }

    /**
     * Assert that a file exists and its content does NOT contain the given substring.
     *
     * @throws AssertionError if the content contains the forbidden substring
     */
    public void assertFileNotContains(String relativePath, String forbiddenSubstring) {
        assertFileExists(relativePath);
        String content = read(relativePath);
        if (content.contains(forbiddenSubstring)) {
            throw new AssertionError("Expected file '" + relativePath + "' to NOT contain: ["
                    + forbiddenSubstring + "]");
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Delete the temporary workspace recursively.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    @Override
    public void close() {
        deleteRecursive(root);
    }

    private static void deleteRecursive(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignore) { /* best-effort */ }
                    });
        } catch (IOException ignore) { /* best-effort */ }
    }

    // ── Builder ──────────────────────────────────────────────────────

    public static final class Builder {
        private final Map<String, String> files = new LinkedHashMap<>();

        public Builder file(String relativePath, String content) {
            files.put(relativePath, content);
            return this;
        }

        public ScenarioWorkspaceFixture build() {
            return withFiles(files);
        }
    }
}

