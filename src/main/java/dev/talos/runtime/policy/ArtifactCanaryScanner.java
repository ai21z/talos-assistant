package dev.talos.runtime.policy;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Deterministic scanner for generated artifacts that must not contain raw privacy canaries. */
public final class ArtifactCanaryScanner {
    private ArtifactCanaryScanner() {}

    private static final long MAX_TEXT_FILE_BYTES = 2_000_000L;
    private static final Pattern EXPLICIT_TEST_SECRET = Pattern.compile(
            "(?i)\\b(?:t275-token-should-not-appear|t275-password-should-not-appear|t275-client-secret-should-not-appear)\\b");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".json", ".jsonl", ".yaml", ".yml",
            ".log", ".trace", ".out", ".err", ".csv", ".tsv", ".html", ".xml",
            ".properties", ".conf", ".config");

    private static final Set<String> ALWAYS_SKIPPED_DIRECTORY_NAMES = Set.of(
            ".git", ".gradle", "classes", "generated", "generated-sources",
            "generated-test-sources", "jacoco");

    private static final Set<String> BROAD_SCAN_SKIPPED_DIRECTORY_NAMES = Set.of(
            "test-results", "reports", "tmp");

    public record Finding(Path path, int line, String snippet) {}

    public static List<Finding> scanExisting(List<Path> roots, List<Path> allowlist) throws IOException {
        List<Path> existing = roots == null
                ? List.of()
                : roots.stream().filter(Files::exists).toList();
        return scan(existing, allowlist);
    }

    public static List<Finding> scan(List<Path> roots, List<Path> allowlist) throws IOException {
        return scanInternal(roots, allowlist, true);
    }

    public static List<Finding> scanRuntimeArtifacts(List<Path> roots, List<Path> allowlist) throws IOException {
        return scanInternal(roots, allowlist, false);
    }

    private static List<Finding> scanInternal(List<Path> roots, List<Path> allowlist, boolean broadScan)
            throws IOException {
        if (roots == null || roots.isEmpty()) return List.of();
        Set<Path> allowed = normalizedAllowlist(allowlist);
        List<Finding> findings = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream
                        .filter(Files::isRegularFile)
                        .filter(path -> !isUnderSkippedDirectory(path, broadScan))
                        .filter(path -> !allowed.contains(normalize(path)))
                        .filter(ArtifactCanaryScanner::looksTextLike)
                        .toList()) {
                    findings.addAll(scanFile(path));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static List<Finding> scanFile(Path path) throws IOException {
        if (Files.size(path) > MAX_TEXT_FILE_BYTES) return List.of();
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return List.of();
        }
        if (!containsKnownArtifactCanary(text)) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (containsKnownArtifactCanary(line)) {
                findings.add(new Finding(path, i + 1, sanitizeFindingSnippet(line.strip())));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean containsKnownArtifactCanary(String text) {
        return ProtectedContentPolicy.containsRawCanary(text)
                || EXPLICIT_TEST_SECRET.matcher(text).find()
                || ProtectedContentPolicy.containsRawPrivateDocumentFactCanary(text);
    }

    private static String sanitizeFindingSnippet(String text) {
        String sanitized = ProtectedContentPolicy.sanitizeText(text);
        return EXPLICIT_TEST_SECRET.matcher(sanitized).replaceAll("[redacted-test-secret]");
    }

    private static Set<Path> normalizedAllowlist(List<Path> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) return Set.of();
        Set<Path> out = new HashSet<>();
        for (Path path : allowlist) {
            if (path != null) out.add(normalize(path));
        }
        return out;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isUnderSkippedDirectory(Path path, boolean broadScan) {
        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (ALWAYS_SKIPPED_DIRECTORY_NAMES.contains(name)) return true;
            if (broadScan && BROAD_SCAN_SKIPPED_DIRECTORY_NAMES.contains(name)) return true;
        }
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("build/resources/") || normalized.contains("/build/resources/")) return true;
        if (broadScan && (normalized.startsWith("local/manual-testing/")
                || normalized.contains("/local/manual-testing/"))) return true;
        if (broadScan && (normalized.startsWith("local/manual-workspaces/")
                || normalized.contains("/local/manual-workspaces/"))) return true;
        return false;
    }

    private static boolean looksTextLike(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : TEXT_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return name.contains("prompt-debug")
                || name.contains("provider-body")
                || name.contains("trace")
                || name.contains("session")
                || name.contains("turn");
    }
}
