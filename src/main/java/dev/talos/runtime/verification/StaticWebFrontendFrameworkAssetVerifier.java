package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Verifies generic frontend framework local artifacts outside the Tailwind-specific lane. */
final class StaticWebFrontendFrameworkAssetVerifier {
    private StaticWebFrontendFrameworkAssetVerifier() {}

    static List<String> problems(
            Path root,
            TaskContract contract,
            Collection<String> mutatedPaths
    ) {
        if (root == null || mutatedPaths == null || mutatedPaths.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        boolean localFrameworkArtifactsForbidden =
                forbidsLocalFrameworkArtifacts(contract == null ? "" : contract.originalUserRequest());
        for (String path : mutatedPaths) {
            String normalized = normalize(path);
            FrameworkArtifact artifact = FrameworkArtifact.fromPath(normalized);
            if (artifact == null) continue;
            String content = read(root, normalized);
            if (localFrameworkArtifactsForbidden || looksPlaceholder(content, artifact.framework())) {
                out.add(normalized + ": local " + artifact.displayName()
                        + " artifact is unsupported without an explicit build-backed local artifact request.");
            }
        }
        return List.copyOf(out);
    }

    private static boolean forbidsLocalFrameworkArtifacts(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("no local framework artifact")
                || lower.contains("no local framework file")
                || lower.contains("no local frontend artifact")
                || lower.contains("no local cdn file")
                || lower.contains("cdn only")
                || lower.contains("through the cdn only")
                || lower.contains("with the cdn only");
    }

    private static boolean looksPlaceholder(String content, String framework) {
        if (content == null || content.isBlank()) return true;
        String lower = content.toLowerCase(Locale.ROOT).strip();
        if (lower.equals("/* */") || lower.equals("//")) return true;
        return lower.contains("placeholder")
                || lower.contains("todo")
                || lower.contains("stub")
                || lower.contains(framework + " placeholder");
    }

    private static String read(Path root, String relative) {
        try {
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root.normalize()) || !Files.isRegularFile(resolved)) return "";
            return Files.readString(resolved);
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private record FrameworkArtifact(String framework, String displayName) {
        static FrameworkArtifact fromPath(String path) {
            if (path == null || path.isBlank()) return null;
            String normalized = normalize(path).toLowerCase(Locale.ROOT);
            int slash = normalized.lastIndexOf('/');
            String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (name.equals("bootstrap.css")
                    || name.equals("bootstrap.min.css")
                    || name.equals("bootstrap.js")
                    || name.equals("bootstrap.min.js")
                    || name.equals("bootstrap.bundle.js")
                    || name.equals("bootstrap.bundle.min.js")) {
                return new FrameworkArtifact("bootstrap", "Bootstrap");
            }
            if (name.equals("alpine.js") || name.equals("alpine.min.js")) {
                return new FrameworkArtifact("alpine", "Alpine");
            }
            if (name.equals("htmx.js") || name.equals("htmx.min.js")) {
                return new FrameworkArtifact("htmx", "HTMX");
            }
            if (name.equals("react.js")
                    || name.equals("react.min.js")
                    || name.equals("react-dom.js")
                    || name.equals("react-dom.min.js")) {
                return new FrameworkArtifact("react", "React");
            }
            if (name.equals("vue.js") || name.equals("vue.min.js")) {
                return new FrameworkArtifact("vue", "Vue");
            }
            return null;
        }
    }
}
