package dev.talos.runtime.policy;

import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Deterministic scanner for generated artifacts that must not contain raw privacy canaries. */
public final class ArtifactCanaryScanner {
    private ArtifactCanaryScanner() {}

    private static final long MAX_TEXT_FILE_BYTES = 2_000_000L;
    public static final String CAPTURED_SCAN_POLICY =
            "talos.capturedArtifactCanaryBytes";
    public static final int CAPTURED_SCAN_POLICY_VERSION = 1;
    public static final String CAPTURED_SCAN_SCOPE =
            "ALL_NON_ALLOWLISTED_CAPTURED_ARTIFACT_BYTES";
    public static final String CAPTURED_PATTERN_ENCODING =
            "CURRENT_KNOWN_ASCII_PATTERNS_UTF8_BYTE_EQUIVALENT";
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

    public enum CapturedFindingReason {
        KNOWN_RAW_CANARY_ASCII_OR_UTF8_BYTES
    }

    public enum CoverageGapReason {
        CAPTURE_BYTES_UNAVAILABLE
    }

    public record CapturedFinding(String relativePath, CapturedFindingReason reason) {
        public CapturedFinding {
            relativePath = requireSafeRelativePath(relativePath);
            reason = Objects.requireNonNull(reason, "captured finding reason");
        }
    }

    public record CoverageGap(String relativePath, CoverageGapReason reason) {
        public CoverageGap {
            relativePath = requireSafeRelativePath(relativePath);
            reason = Objects.requireNonNull(reason, "coverage gap reason");
        }
    }

    public record CapturedRuntimeScan(
            String policyName,
            int policyVersion,
            String scanScope,
            String patternEncoding,
            int capturedArtifactCount,
            int allowlistedArtifactCount,
            int scannedArtifactCount,
            List<CapturedFinding> findings,
            List<CoverageGap> coverageGaps) {
        public CapturedRuntimeScan {
            if (!CAPTURED_SCAN_POLICY.equals(policyName)
                    || policyVersion != CAPTURED_SCAN_POLICY_VERSION
                    || !CAPTURED_SCAN_SCOPE.equals(scanScope)
                    || !CAPTURED_PATTERN_ENCODING.equals(patternEncoding)) {
                throw new IllegalArgumentException("invalid captured canary scan policy");
            }
            if (capturedArtifactCount < 0
                    || allowlistedArtifactCount < 0
                    || scannedArtifactCount < 0) {
                throw new IllegalArgumentException(
                        "captured canary artifact counts must not be negative");
            }
            findings = canonicalFindings(findings);
            coverageGaps = canonicalGaps(coverageGaps);
            if (capturedArtifactCount
                    != allowlistedArtifactCount + scannedArtifactCount + coverageGaps.size()) {
                throw new IllegalArgumentException(
                        "captured canary artifact counts do not reconcile");
            }
            if (findings.size() > scannedArtifactCount) {
                throw new IllegalArgumentException(
                        "captured canary findings exceed scanned artifacts");
            }
            Set<String> gapPaths = coverageGaps.stream()
                    .map(CoverageGap::relativePath)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (findings.stream().map(CapturedFinding::relativePath).anyMatch(gapPaths::contains)) {
                throw new IllegalArgumentException(
                        "captured canary artifact cannot be both scanned and a coverage gap");
            }
        }

        public boolean coverageComplete() {
            return coverageGaps.isEmpty();
        }

        public boolean passed() {
            return coverageComplete() && findings.isEmpty();
        }
    }

    private record CapturedArtifact(
            Path normalizedPath,
            String relativePath,
            byte[] bytes) {}

    public static List<Finding> scanExisting(List<Path> roots, List<Path> allowlist) throws IOException {
        return scan(roots, allowlist);
    }

    public static List<Finding> scan(List<Path> roots, List<Path> allowlist) throws IOException {
        return scanInternal(roots, allowlist, true);
    }

    public static List<Finding> scanRuntimeArtifacts(List<Path> roots, List<Path> allowlist) throws IOException {
        return scanInternal(roots, allowlist, false);
    }

    /**
     * Scans one already-captured artifact snapshot without rereading live files.
     * Every non-allowlisted byte array is inspected regardless of file name,
     * extension, size, or whether the surrounding bytes are valid UTF-8.
     */
    public static CapturedRuntimeScan scanCapturedRuntimeArtifacts(
            Path captureRoot,
            Map<Path, byte[]> artifacts,
            Map<Path, byte[]> allowlist) {
        Path root = Objects.requireNonNull(captureRoot, "capture root")
                .toAbsolutePath().normalize();
        Map<String, CapturedArtifact> captured = capturedArtifacts(root, artifacts);
        Map<String, CapturedArtifact> allowed = capturedArtifacts(root, allowlist);
        List<CapturedFinding> findings = new ArrayList<>();
        List<CoverageGap> coverageGaps = new ArrayList<>();
        int allowlistedArtifactCount = 0;
        int scannedArtifactCount = 0;
        for (CapturedArtifact artifact : captured.values()) {
            CapturedArtifact exemption = allowed.get(artifact.relativePath());
            if (exemption != null && artifact.bytes() != null
                    && Arrays.equals(exemption.bytes(), artifact.bytes())) {
                allowlistedArtifactCount++;
                continue;
            }
            if (artifact.bytes() == null) {
                coverageGaps.add(new CoverageGap(
                        artifact.relativePath(), CoverageGapReason.CAPTURE_BYTES_UNAVAILABLE));
                continue;
            }
            scannedArtifactCount++;
            if (containsKnownArtifactCanaryBytes(artifact.bytes())) {
                findings.add(new CapturedFinding(
                        artifact.relativePath(),
                        CapturedFindingReason.KNOWN_RAW_CANARY_ASCII_OR_UTF8_BYTES));
            }
        }
        return new CapturedRuntimeScan(
                CAPTURED_SCAN_POLICY,
                CAPTURED_SCAN_POLICY_VERSION,
                CAPTURED_SCAN_SCOPE,
                CAPTURED_PATTERN_ENCODING,
                captured.size(),
                allowlistedArtifactCount,
                scannedArtifactCount,
                findings,
                coverageGaps);
    }

    private static List<Finding> scanInternal(List<Path> roots, List<Path> allowlist, boolean broadScan)
            throws IOException {
        if (roots == null || roots.isEmpty()) return List.of();
        Set<Path> allowed = normalizedAllowlist(allowlist);
        List<Finding> findings = new ArrayList<>();
        for (Path root : roots) {
            if (root == null) continue;
            Path normalizedRoot = normalize(root);
            try {
                NoFollowPathPolicy.GuardedRoot files =
                        NoFollowPathPolicy.bindExistingDirectory(normalizedRoot);
                for (NoFollowPathPolicy.TreeEntry entry : files.walk(normalizedRoot)) {
                    Path path = entry.path();
                    if (entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE
                            && !isUnderSkippedDirectory(
                                    normalizedRoot, path, broadScan)
                            && !allowed.contains(path)
                            && looksTextLike(path)) {
                        findings.addAll(scanFile(files, path));
                    }
                }
            } catch (NoFollowPathPolicy.Violation notDirectory) {
                if (notDirectory.reason() == NoFollowPathPolicy.Reason.MISSING) continue;
                if (notDirectory.reason() != NoFollowPathPolicy.Reason.NOT_DIRECTORY) {
                    throw notDirectory;
                }
                NoFollowPathPolicy.requireUnlinkedRegularFile(normalizedRoot);
                if (!isUnderSkippedDirectory(
                            normalizedRoot, normalizedRoot, broadScan)
                        && !allowed.contains(normalizedRoot)
                        && looksTextLike(normalizedRoot)) {
                    findings.addAll(scanFile(null, normalizedRoot));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static List<Finding> scanFile(
            NoFollowPathPolicy.GuardedRoot owner,
            Path path
    ) throws IOException {
        if (owner == null) NoFollowPathPolicy.requireUnlinkedRegularFile(path);
        else owner.requireRegularFile(path);
        if (Files.size(path) > MAX_TEXT_FILE_BYTES) return List.of();
        String text;
        try {
            byte[] bytes = owner == null
                    ? NoFollowPathPolicy.readAllBytes(path)
                    : owner.readAllBytes(path);
            text = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
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

    private static boolean containsKnownArtifactCanaryBytes(byte[] bytes) {
        // Every raw canary recognized by the current policy is made only from
        // ASCII code points. ASCII has the same byte representation in UTF-8;
        // ISO-8859-1 provides a lossless one-byte-to-one-char view of arbitrary
        // captured bytes so binary and malformed UTF-8 artifacts remain covered.
        return containsKnownArtifactCanary(new String(bytes, StandardCharsets.ISO_8859_1));
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

    private static Map<String, CapturedArtifact> capturedArtifacts(
            Path captureRoot,
            Map<Path, byte[]> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return Map.of();
        Map<String, CapturedArtifact> captured = new TreeMap<>();
        for (Map.Entry<Path, byte[]> entry : artifacts.entrySet()) {
            Path path = Objects.requireNonNull(entry.getKey(), "captured artifact path")
                    .toAbsolutePath().normalize();
            if (!path.startsWith(captureRoot) || path.equals(captureRoot)) {
                throw new IllegalArgumentException(
                        "captured artifact path is outside the capture root");
            }
            String relative = requireSafeRelativePath(
                    captureRoot.relativize(path).toString().replace('\\', '/'));
            CapturedArtifact prior = captured.put(relative,
                    new CapturedArtifact(path, relative,
                            entry.getValue() == null ? null : entry.getValue().clone()));
            if (prior != null) {
                throw new IllegalArgumentException(
                        "captured artifact paths collide after normalization");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(captured));
    }

    private static List<CapturedFinding> canonicalFindings(List<CapturedFinding> findings) {
        List<CapturedFinding> provided = findings == null ? List.of() : List.copyOf(findings);
        List<CapturedFinding> canonical = provided.stream()
                .distinct()
                .sorted(Comparator.comparing(CapturedFinding::relativePath)
                        .thenComparing(finding -> finding.reason().name()))
                .toList();
        if (!provided.equals(canonical)) {
            throw new IllegalArgumentException(
                    "captured canary findings must be unique and canonically ordered");
        }
        return canonical;
    }

    private static List<CoverageGap> canonicalGaps(List<CoverageGap> gaps) {
        List<CoverageGap> provided = gaps == null ? List.of() : List.copyOf(gaps);
        List<CoverageGap> canonical = provided.stream()
                .distinct()
                .sorted(Comparator.comparing(CoverageGap::relativePath)
                        .thenComparing(gap -> gap.reason().name()))
                .toList();
        if (!provided.equals(canonical)) {
            throw new IllegalArgumentException(
                    "captured canary coverage gaps must be unique and canonically ordered");
        }
        return canonical;
    }

    private static String requireSafeRelativePath(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.startsWith("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(
                    "captured canary path must be a canonical safe relative path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)
                    || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "captured canary path must be a canonical safe relative path");
            }
        }
        return value;
    }

    private static boolean isUnderSkippedDirectory(
            Path scanRoot,
            Path path,
            boolean broadScan) {
        Path relative;
        try {
            relative = scanRoot.equals(path)
                    ? Path.of(path.getFileName() == null
                            ? ""
                            : path.getFileName().toString())
                    : scanRoot.relativize(path);
        } catch (IllegalArgumentException unrelated) {
            return true;
        }
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (ALWAYS_SKIPPED_DIRECTORY_NAMES.contains(name)) return true;
            if (broadScan && BROAD_SCAN_SKIPPED_DIRECTORY_NAMES.contains(name)) return true;
        }
        String rootName = scanRoot.getFileName() == null
                ? ""
                : scanRoot.getFileName().toString();
        String normalized = (rootName + "/" + relative)
                .replace('\\', '/').toLowerCase(Locale.ROOT);
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
