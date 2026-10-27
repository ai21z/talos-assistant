package dev.talos.cli;

import java.nio.file.Path;

/**
 * Shared CLI utility methods for path display and workspace detection.
 */
public final class CliUtil {
    private CliUtil() {}

    /**
     * Shortens a path for display by replacing home directory with ~ if applicable.
     * Falls back to the folder name, or to the root text for a drive or filesystem root.
     */
    public static String shortenPath(Path path) {
        String home = System.getProperty("user.home");
        String pathStr = path.toString();
        if (home != null && !home.isBlank() && pathStr.startsWith(home)) {
            return "~" + pathStr.substring(home.length()).replace('\\', '/');
        }
        return folderName(path);
    }

    /** The last name of a path, or its root text, such as D:\, when the path is a root. */
    public static String folderName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
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

