package dev.talos.runtime.verification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class StaticWebSurfaceDetector {
    private static final Set<String> SMALL_WORKSPACE_WEB_EXTS = Set.of(
            ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx"
    );
    private static final int MAX_SMALL_WORKSPACE_VISIBLE_FILES = 6;
    static final int MAX_TARGET_AWARE_WORKSPACE_VISIBLE_FILES = 12;
    private static final int MAX_PRIMARY_WEB_FILES = 5;

    private StaticWebSurfaceDetector() {}

    static List<String> obviousPrimaryFiles(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return List.of();
        try {
            List<Path> visibleFiles = visibleRegularFiles(workspace);
            if (visibleFiles.isEmpty()
                    || visibleFiles.size() > MAX_SMALL_WORKSPACE_VISIBLE_FILES) return List.of();
            List<String> webFiles = webFileNames(visibleFiles);
            if (webFiles.isEmpty() || webFiles.size() > MAX_PRIMARY_WEB_FILES) return List.of();
            return webFiles.stream().sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<String> targetAwarePrimaryFiles(Path workspace, Collection<String> targetHints) {
        if (workspace == null || !Files.isDirectory(workspace) || targetHints == null || targetHints.isEmpty()) {
            return List.of();
        }
        try {
            List<Path> visibleFiles = visibleRegularFiles(workspace);
            if (visibleFiles.isEmpty()
                    || visibleFiles.size() > MAX_TARGET_AWARE_WORKSPACE_VISIBLE_FILES) return List.of();

            Set<String> visibleNames = new LinkedHashSet<>();
            for (Path file : visibleFiles) {
                String name = visibleFileName(file);
                if (!name.isBlank()) visibleNames.add(name);
            }
            if (visibleNames.isEmpty() || !hasVisibleWebTarget(visibleNames, targetHints)) return List.of();

            List<String> webFiles = webFileNames(visibleFiles);
            if (webFiles.isEmpty() || webFiles.size() > MAX_PRIMARY_WEB_FILES) return List.of();
            return webFiles.stream().sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<Path> visibleRegularFiles(Path workspace) throws java.io.IOException {
        List<Path> visibleFiles = new ArrayList<>();
        try (var stream = Files.list(workspace)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = visibleFileName(file);
                        return !name.isBlank() && !name.startsWith(".");
                    })
                    .forEach(visibleFiles::add);
        }
        return visibleFiles;
    }

    static String visibleFileName(Path file) {
        return file == null || file.getFileName() == null ? "" : file.getFileName().toString();
    }

    static boolean isSmallWorkspaceWebFile(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot) : "";
        return SMALL_WORKSPACE_WEB_EXTS.contains(ext);
    }

    static List<String> preferredWebTargetFiles(Collection<String> primaryHints, Collection<String> secondaryHints) {
        List<String> preferred = new ArrayList<>();
        addPreferredWebTargetFiles(preferred, primaryHints);
        addPreferredWebTargetFiles(preferred, secondaryHints);
        return preferred;
    }

    static List<String> missingPrimaryReads(Path workspace, Collection<String> readPaths) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (primary.isEmpty()) return List.of();
        Set<String> read = new LinkedHashSet<>();
        if (readPaths != null) {
            for (String p : readPaths) {
                if (p == null || p.isBlank()) continue;
                String normalized = p.replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                read.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String file : primary) {
            if (!read.contains(file)) missing.add(file);
        }
        return List.copyOf(missing);
    }

    static List<String> primaryHtmlTargets(Path workspace) {
        return primaryHtmlTargets(obviousPrimaryFiles(workspace));
    }

    static List<String> primaryHtmlTargets(List<String> primary) {
        if (primary == null || primary.isEmpty()) return List.of();
        List<String> html = primary.stream()
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".html") || lower.endsWith(".htm");
                })
                .toList();
        if (html.isEmpty()) return List.of();
        for (String candidate : html) {
            if ("index.html".equalsIgnoreCase(candidate) || "index.htm".equalsIgnoreCase(candidate)) {
                return List.of(candidate);
            }
        }
        return List.of(html.get(0));
    }

    static boolean hasPrimaryWebSurface(List<String> files) {
        return StaticWebSelectorAnalyzer.pickPrimary(files, ".html", ".htm") != null
                && StaticWebSelectorAnalyzer.pickPrimary(files, ".css") != null
                && StaticWebSelectorAnalyzer.pickPrimary(files, ".js") != null;
    }

    private static List<String> webFileNames(List<Path> visibleFiles) {
        List<String> webFiles = new ArrayList<>();
        if (visibleFiles == null) return webFiles;
        for (Path file : visibleFiles) {
            String name = visibleFileName(file);
            if (isSmallWorkspaceWebFile(name)) {
                webFiles.add(name.replace('\\', '/'));
            }
        }
        return webFiles;
    }

    private static boolean hasVisibleWebTarget(Set<String> visibleNames, Collection<String> targetHints) {
        boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
        for (String hint : targetHints) {
            String normalized = normalizePath(hint);
            if (normalized.isBlank() || normalized.contains("/") || !isSmallWorkspaceWebFile(normalized)) {
                continue;
            }
            for (String visibleName : visibleNames) {
                if (expectedTargetMatches(visibleName, normalized, caseInsensitive)) return true;
            }
        }
        return false;
    }

    private static void addPreferredWebTargetFiles(List<String> preferred, Collection<String> targetHints) {
        if (preferred == null || targetHints == null || targetHints.isEmpty()) return;
        boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
        for (String hint : targetHints) {
            String normalized = normalizePath(hint);
            if (normalized.isBlank()
                    || normalized.contains("/")
                    || !isSmallWorkspaceWebFile(normalized)) {
                continue;
            }
            boolean alreadyPresent = preferred.stream()
                    .anyMatch(existing -> expectedTargetMatches(existing, normalized, caseInsensitive));
            if (!alreadyPresent) preferred.add(normalized);
        }
    }

    private static boolean expectedTargetMatches(String expectedTarget, String mutatedPath, boolean caseInsensitive) {
        String expected = normalizePath(expectedTarget);
        String mutated = normalizePath(mutatedPath);
        if (expected.isBlank() || mutated.isBlank()) return false;
        if (caseInsensitive) {
            return expected.equalsIgnoreCase(mutated);
        }
        return expected.equals(mutated);
    }

    private static boolean expectedTargetMatchingIsCaseInsensitive() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
