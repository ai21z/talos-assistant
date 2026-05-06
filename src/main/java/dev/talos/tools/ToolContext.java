package dev.talos.tools;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Execution context provided to tools at invocation time.
 *
 * <p>Every tool receives a ToolContext so it can:
 * <ul>
 *   <li>Resolve file paths against the workspace root</li>
 *   <li>Enforce sandbox path policy before file I/O</li>
 *   <li>Read configuration (e.g., limits, feature flags)</li>
 * </ul>
 *
 * <p>Tools must <em>never</em> bypass the sandbox for file access.
 * Any path resolved from user input must pass {@link Sandbox#allowedPath(Path)}
 * before reading or writing.
 */
public record ToolContext(Path workspace, Sandbox sandbox, Config config) {
    public ToolContext {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(sandbox, "sandbox must not be null");
        Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Resolve a user-supplied relative path against the workspace root.
     * Does NOT check sandbox policy — caller must call
     * {@code sandbox().allowedPath()} on the result before I/O.
     */
    public Path resolve(String relativePath) {
        PathArgumentCanonicalizer.Resolution resolution =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, relativePath);
        if (resolution.resolvedPath() == null) {
            return workspace.resolve(relativePath).normalize();
        }
        return resolution.resolvedPath();
    }
}

