package dev.talos.cli;

import java.nio.file.Path;

/**
 * Shared CLI utility methods for path display and workspace detection.
 */
public final class CliUtil {
    private CliUtil() {}

    /**
     * Shortens a path for display by replacing home directory with ~ if applicable.
     * Falls back to just the filename if home replacement doesn't apply.
     */
    public static String shortenPath(Path path) {
        String home = System.getProperty("user.home");
        String pathStr = path.toString();
        if (home != null && !home.isBlank() && pathStr.startsWith(home)) {
            return "~" + pathStr.substring(home.length()).replace('\\', '/');
        }
        return path.getFileName().toString();
    }

    /**
     * Check if the workspace path indicates we're in the Talos installer directory.
     * This is used to provide helpful hints when users run commands from the wrong location.
     */
    public static boolean isInstallerDirectory(Path workspace) {
        String pathStr = workspace.toString();
        // Check for common installer directory patterns (platform-independent)
        return pathStr.contains("build/install/talos/bin") ||
               pathStr.contains("build\\install\\talos\\bin") ||
               pathStr.endsWith("talos/bin") ||
               pathStr.endsWith("talos\\bin");
    }
}

