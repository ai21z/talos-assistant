package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.ToolAliasPolicy;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Selects and verifies one bounded top-level anchor for project summary claims. */
public final class ProjectSummaryEvidencePolicy {
    private static final List<String> ANCHOR_PRIORITY = List.of(
            "README.md",
            "README",
            "README.txt",
            "README.rst"
    );
    private static final Set<String> SOURCE_BEARING_TOOLS = Set.of(
            "talos.read_file",
            "talos.grep",
            "talos.retrieve"
    );

    private ProjectSummaryEvidencePolicy() {
    }

    public enum SelectionStatus {
        FOUND,
        ABSENT,
        UNAVAILABLE
    }

    public record AnchorSelection(SelectionStatus status, String anchor) {
        public AnchorSelection {
            status = status == null ? SelectionStatus.UNAVAILABLE : status;
            anchor = anchor == null ? "" : anchor;
        }
    }

    public static Optional<String> preferredAnchor(Path workspace) {
        AnchorSelection selection = selectAnchor(workspace);
        return selection.status() == SelectionStatus.FOUND
                ? Optional.of(selection.anchor())
                : Optional.empty();
    }

    public static AnchorSelection selectAnchor(Path workspace) {
        if (workspace == null) return new AnchorSelection(SelectionStatus.UNAVAILABLE, "");
        Path root;
        try {
            root = workspace.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return new AnchorSelection(SelectionStatus.UNAVAILABLE, "");
            }
        } catch (RuntimeException e) {
            return new AnchorSelection(SelectionStatus.UNAVAILABLE, "");
        }

        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isAnchorCandidate(path.getFileName().toString()))
                    .forEach(entries::add);
        } catch (Exception e) {
            return new AnchorSelection(SelectionStatus.UNAVAILABLE, "");
        }
        entries.sort(Comparator.comparing(
                path -> path.getFileName().toString(),
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));

        for (String candidate : ANCHOR_PRIORITY) {
            Optional<String> exact = entries.stream()
                    .map(path -> path.getFileName().toString())
                    .filter(candidate::equals)
                    .findFirst();
            if (exact.isPresent()) {
                return new AnchorSelection(SelectionStatus.FOUND, exact.get());
            }

            Optional<String> caseInsensitive = entries.stream()
                    .map(path -> path.getFileName().toString())
                    .filter(name -> candidate.equalsIgnoreCase(name))
                    .findFirst();
            if (caseInsensitive.isPresent()) {
                return new AnchorSelection(SelectionStatus.FOUND, caseInsensitive.get());
            }
        }
        return new AnchorSelection(SelectionStatus.ABSENT, "");
    }

    private static boolean isAnchorCandidate(String name) {
        if (name == null || name.isBlank()) return false;
        for (String candidate : ANCHOR_PRIORITY) {
            if (candidate.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean hasSourceEvidence(
            Path workspace,
            String anchor,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        if (anchor == null || anchor.isBlank() || outcomes == null || outcomes.isEmpty()) {
            return false;
        }
        String expected = normalizePath(anchor);
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success() || outcome.denied()) continue;
            String toolName = canonicalToolName(outcome.toolName());
            if (!SOURCE_BEARING_TOOLS.contains(toolName)) continue;
            if ("talos.read_file".equals(toolName)
                    && sameWorkspaceFile(workspace, expected, normalizePath(outcome.pathHint()))) {
                return true;
            }
            for (String sourcePath : outcome.sourcePaths()) {
                if (sameWorkspaceFile(workspace, expected, normalizePath(sourcePath))) return true;
            }
        }
        return false;
    }

    public static List<String> missingAnchorReadTargets(
            Path workspace,
        List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        Optional<String> anchor = preferredAnchor(workspace);
        if (anchor.isEmpty() || hasSourceEvidence(workspace, anchor.get(), outcomes)) return List.of();
        return List.of(anchor.get());
    }

    private static String normalizePath(String value) {
        String normalized = ToolCallSupport.normalizePath(value).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static boolean sameWorkspaceFile(Path workspace, String expected, String observed) {
        if (workspace == null || expected.isBlank() || observed.isBlank()) return false;
        Path root;
        Path expectedPath;
        Path observedPath;
        try {
            root = workspace.toAbsolutePath().normalize();
            expectedPath = resolveInside(root, expected);
            observedPath = resolveInside(root, observed);
        } catch (RuntimeException e) {
            return false;
        }
        if (expectedPath == null || observedPath == null || Files.isSymbolicLink(observedPath)) return false;
        try {
            return Files.isSameFile(expectedPath, observedPath);
        } catch (Exception e) {
            return false;
        }
    }

    private static Path resolveInside(Path root, String candidate) {
        Path raw = Path.of(candidate);
        Path resolved = raw.isAbsolute() ? raw : root.resolve(raw);
        resolved = resolved.toAbsolutePath().normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted()
                && decision.canonicalToolName() != null
                && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
    }
}
