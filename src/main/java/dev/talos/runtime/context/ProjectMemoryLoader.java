package dev.talos.runtime.context;

import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextItemSource;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextPrivacyClass;
import dev.talos.core.context.ExecutionBoundary;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.task.TaskContract;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Loads visible, bounded, read-only Markdown project memory for a turn. */
public final class ProjectMemoryLoader {
    private final ProjectMemoryLimits limits;

    public ProjectMemoryLoader(ProjectMemoryLimits limits) {
        this.limits = limits == null ? ProjectMemoryLimits.defaults() : limits;
    }

    public ProjectMemoryContext load(ProjectMemoryRequest request) {
        ProjectMemoryPolicy.Decision policy = ProjectMemoryPolicy.decide(request);
        if (!policy.load()) {
            recordSuppressed(policy.reason(), request);
            return ProjectMemoryContext.suppressed(policy.reason());
        }

        Path workspace = absolute(request.workspace());
        Path userHome = absolute(request.userHome());
        List<Candidate> candidates = discover(workspace, userHome, request.taskContract());
        List<ProjectMemorySource> viable = new ArrayList<>();
        List<ProjectMemoryDecision> decisions = new ArrayList<>();

        for (Candidate candidate : candidates) {
            ReadDecision read = readCandidate(candidate, workspace, userHome);
            if (read.source() != null) {
                viable.add(read.source());
            } else if (read.decision() != null && !"NOT_FOUND".equals(read.decision().decisionReason())) {
                decisions.add(read.decision());
                recordDecision(candidate, read.decision());
            }
        }

        Budgeted budgeted = applyBudget(viable);
        for (ProjectMemorySource source : budgeted.included()) {
            ProjectMemoryDecision decision = source.decision("INCLUDED_IN_MODEL_PROMPT", "LOADED");
            decisions.add(decision);
            recordDecision(source, decision);
        }
        for (ProjectMemorySource dropped : budgeted.dropped()) {
            ProjectMemoryDecision decision = dropped.decision(
                    "WITHHELD_FROM_MODEL",
                    "BUDGET_DROPPED_LEAST_SPECIFIC");
            decisions.add(decision);
            recordDecision(dropped, decision);
        }

        if (budgeted.included().isEmpty()) {
            return ProjectMemoryContext.empty("NO_INCLUDED_MEMORY", decisions);
        }
        return new ProjectMemoryContext(ProjectMemoryStatus.LOADED, policy.reason(), budgeted.included(), decisions);
    }

    private List<Candidate> discover(Path workspace, Path userHome, TaskContract contract) {
        LinkedHashMap<String, Candidate> out = new LinkedHashMap<>();
        addUserGlobalCandidates(out, userHome);
        addRootCandidates(out, repoRoot(workspace), workspace, true);
        addRootCandidates(out, workspace, workspace, false);
        addDirectoryLocalCandidates(out, workspace, contract);
        return List.copyOf(out.values());
    }

    private void addUserGlobalCandidates(Map<String, Candidate> out, Path userHome) {
        Path talosHome = userHome.resolve(".talos");
        addCandidate(out, new Candidate(
                ProjectMemoryTier.USER_GLOBAL,
                ProjectMemoryTrust.USER_OWNED,
                talosHome.resolve("TALOS.md"),
                displayUserPath(userHome, talosHome.resolve("TALOS.md"))));
        Path memoryDir = talosHome.resolve("memory");
        if (!Files.isDirectory(memoryDir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> stream = Files.list(memoryDir)) {
            stream.filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(limits.maxUserMemoryFiles())
                    .forEach(path -> addCandidate(out, new Candidate(
                            ProjectMemoryTier.USER_GLOBAL,
                            ProjectMemoryTrust.USER_OWNED,
                            path,
                            displayUserPath(userHome, path))));
        } catch (Exception ignored) {
            // Unreadable directories are ignored; individual memory files are optional context.
        }
    }

    private void addRootCandidates(
            Map<String, Candidate> out,
            Path root,
            Path workspace,
            boolean repoTier
    ) {
        if (root == null) return;
        boolean sameAsWorkspace = sameNormalized(root, workspace);
        if (repoTier) {
            addCandidate(out, new Candidate(
                    ProjectMemoryTier.REPO_ROOT,
                    ProjectMemoryTrust.WORKSPACE_PROVIDED,
                    root.resolve("TALOS.md"),
                    displayWorkspacePath(workspace, root.resolve("TALOS.md"))));
            if (!sameAsWorkspace) {
                addCandidate(out, new Candidate(
                        ProjectMemoryTier.REPO_ROOT,
                        ProjectMemoryTrust.WORKSPACE_PROVIDED,
                        root.resolve(".talos").resolve("rules.md"),
                        displayWorkspacePath(workspace, root.resolve(".talos").resolve("rules.md"))));
            }
            return;
        }
        addCandidate(out, new Candidate(
                ProjectMemoryTier.WORKSPACE_ROOT,
                ProjectMemoryTrust.WORKSPACE_PROVIDED,
                root.resolve("TALOS.md"),
                displayWorkspacePath(workspace, root.resolve("TALOS.md"))));
        addCandidate(out, new Candidate(
                ProjectMemoryTier.WORKSPACE_ROOT,
                ProjectMemoryTrust.WORKSPACE_PROVIDED,
                root.resolve(".talos").resolve("rules.md"),
                displayWorkspacePath(workspace, root.resolve(".talos").resolve("rules.md"))));
    }

    private void addDirectoryLocalCandidates(Map<String, Candidate> out, Path workspace, TaskContract contract) {
        if (contract == null) return;
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.addAll(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        for (String raw : targets) {
            Path target = workspace.resolve(raw == null ? "" : raw).normalize();
            Path dir = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent();
            while (dir != null && dir.startsWith(workspace) && !sameNormalized(dir, workspace)) {
                addCandidate(out, new Candidate(
                        ProjectMemoryTier.DIRECTORY_LOCAL,
                        ProjectMemoryTrust.WORKSPACE_PROVIDED,
                        dir.resolve("TALOS.md"),
                        displayWorkspacePath(workspace, dir.resolve("TALOS.md"))));
                addCandidate(out, new Candidate(
                        ProjectMemoryTier.DIRECTORY_LOCAL,
                        ProjectMemoryTrust.WORKSPACE_PROVIDED,
                        dir.resolve(".talos").resolve("rules.md"),
                        displayWorkspacePath(workspace, dir.resolve(".talos").resolve("rules.md"))));
                dir = dir.getParent();
            }
        }
    }

    private void addCandidate(Map<String, Candidate> out, Candidate candidate) {
        if (candidate == null || candidate.path() == null) return;
        String key = realKey(candidate.path());
        out.putIfAbsent(key, candidate);
    }

    private ReadDecision readCandidate(Candidate candidate, Path workspace, Path userHome) {
        if (!Files.exists(candidate.path(), LinkOption.NOFOLLOW_LINKS)) {
            return ReadDecision.skip(candidate.decision("WITHHELD_FROM_MODEL", "NOT_FOUND"));
        }
        if (!candidateInsideTrustBoundary(candidate, workspace, userHome)) {
            return ReadDecision.skip(candidate.decision("REFUSED_UNSUPPORTED_BOUNDARY", "PATH_ESCAPE"));
        }
        if (candidate.trust() == ProjectMemoryTrust.WORKSPACE_PROVIDED
                && !isCanonicalTalosMemoryFile(candidate.path())
                && ProtectedContentPolicy.isProtectedPath(workspace, candidate.path())) {
            return ReadDecision.skip(candidate.decision("EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY", "PROTECTED_PATH"));
        }
        if (!Files.isRegularFile(candidate.path(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(candidate.path())) {
            return ReadDecision.skip(candidate.decision("REFUSED_UNSUPPORTED_BOUNDARY", "NOT_REGULAR_FILE"));
        }
        try {
            byte[] bytes = readBounded(candidate.path(), limits.maxBytesPerFile() + 1);
            boolean truncated = bytes.length > limits.maxBytesPerFile();
            if (truncated) {
                bytes = java.util.Arrays.copyOf(bytes, limits.maxBytesPerFile());
            }
            String decoded = decodeUtf8(bytes);
            TextSlice slice = slice(decoded);
            String sanitized = ProtectedContentPolicy.sanitizeText(slice.text());
            if (sanitized.isBlank()) {
                return ReadDecision.skip(candidate.decision("WITHHELD_FROM_MODEL", "BLANK_AFTER_SANITIZATION"));
            }
            truncated = truncated || slice.truncated();
            ProjectMemorySource source = new ProjectMemorySource(
                    candidate.tier(),
                    candidate.trust(),
                    candidate.pathHint(),
                    sanitized,
                    hash(sanitized),
                    sanitized.length(),
                    sanitized.getBytes(StandardCharsets.UTF_8).length,
                    lineCount(sanitized),
                    estimateTokens(sanitized),
                    truncated);
            return ReadDecision.include(source);
        } catch (CharacterCodingException e) {
            return ReadDecision.skip(candidate.decision("REFUSED_UNSUPPORTED_BOUNDARY", "NON_UTF8_TEXT"));
        } catch (Exception e) {
            return ReadDecision.skip(candidate.decision("WITHHELD_FROM_MODEL", "READ_FAILED"));
        }
    }

    /**
     * The loader's own canonical memory file ({@code <dir>/.talos/rules.md})
     * is the product's designated model-facing memory surface. T788 made the
     * workspace {@code .talos} directory a protected CONTROL segment so the
     * model cannot WRITE it with an ordinary approval (memory injection via
     * a tool write now escalates through the protected-path flow) — but
     * Talos itself still reads exactly this file into the prompt as
     * untrusted context; that is its purpose. Nothing else under
     * {@code .talos} is exempt: {@code .talos/profiles.yaml} (verification
     * profiles) must never flow into a prompt.
     */
    private static boolean isCanonicalTalosMemoryFile(Path path) {
        if (path == null || path.getFileName() == null) return false;
        Path parent = path.getParent();
        return parent != null && parent.getFileName() != null
                && "rules.md".equalsIgnoreCase(path.getFileName().toString())
                && ".talos".equalsIgnoreCase(parent.getFileName().toString());
    }

    private boolean candidateInsideTrustBoundary(Candidate candidate, Path workspace, Path userHome) {
        try {
            if (candidate.trust() == ProjectMemoryTrust.USER_OWNED) {
                Path talosHome = userHome.resolve(".talos").toAbsolutePath().normalize().toRealPath();
                Path real = candidate.path().toRealPath();
                return real.startsWith(talosHome);
            }
            return new Sandbox(workspace, Map.of()).allowedPath(candidate.path());
        } catch (Exception e) {
            return false;
        }
    }

    private Budgeted applyBudget(List<ProjectMemorySource> viable) {
        List<ProjectMemorySource> retention = viable.stream()
                .sorted(Comparator
                        .comparingInt((ProjectMemorySource source) -> retentionOrder(source.tier()))
                        .thenComparing(ProjectMemorySource::pathHint))
                .toList();
        List<ProjectMemorySource> included = new ArrayList<>();
        List<ProjectMemorySource> dropped = new ArrayList<>();
        int chars = 0;
        for (ProjectMemorySource source : retention) {
            boolean fitsFile = included.size() < limits.maxFiles();
            boolean fitsChars = chars + source.chars() <= limits.totalChars();
            if (fitsFile && fitsChars) {
                included.add(source);
                chars += source.chars();
            } else {
                dropped.add(source);
            }
        }
        List<ProjectMemorySource> renderOrder = included.stream()
                .sorted(Comparator
                        .comparingInt((ProjectMemorySource source) -> renderOrder(source.tier()))
                        .thenComparing(ProjectMemorySource::pathHint))
                .toList();
        return new Budgeted(renderOrder, dropped);
    }

    private static Path repoRoot(Path workspace) {
        Path cursor = workspace;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private TextSlice slice(String text) {
        String safe = text == null ? "" : text;
        boolean truncated = false;
        List<String> lines = safe.lines().limit(limits.maxLinesPerFile() + 1L).toList();
        if (lines.size() > limits.maxLinesPerFile()) {
            truncated = true;
            safe = String.join("\n", lines.subList(0, limits.maxLinesPerFile()));
        }
        if (safe.length() > limits.maxCharsPerFile()) {
            truncated = true;
            safe = safe.substring(0, limits.maxCharsPerFile());
        }
        return new TextSlice(safe.strip(), truncated);
    }

    private static byte[] readBounded(Path path, int limit) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(Math.max(1, limit));
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes == null ? new byte[0] : bytes))
                .toString();
    }

    private static void recordSuppressed(String reason, ProjectMemoryRequest request) {
        ContextItem item = ContextItem.fromText(
                ContextItemSource.PROJECT_MEMORY,
                ExecutionBoundary.LOCAL_WORKSPACE,
                ContextPrivacyClass.NORMAL,
                "project-memory",
                "",
                0);
        ContextLedgerCapture.record(item, ContextDecision.withheldFromModel(reason));
    }

    private static void recordDecision(Candidate candidate, ProjectMemoryDecision decision) {
        ContextItem item = ContextItem.fromText(
                ContextItemSource.PROJECT_MEMORY,
                boundary(candidate.trust()),
                ContextPrivacyClass.NORMAL,
                candidate.pathHint(),
                "",
                0);
        ContextLedgerCapture.record(item, contextDecision(decision));
    }

    private static void recordDecision(ProjectMemorySource source, ProjectMemoryDecision decision) {
        ContextItem item = ContextItem.fromText(
                ContextItemSource.PROJECT_MEMORY,
                boundary(source.trust()),
                ContextPrivacyClass.NORMAL,
                source.pathHint(),
                source.content(),
                source.estimatedTokens());
        ContextLedgerCapture.record(item, contextDecision(decision));
    }

    private static ContextDecision contextDecision(ProjectMemoryDecision decision) {
        String reason = decision == null ? "UNSPECIFIED" : decision.decisionReason();
        String action = decision == null ? "" : decision.action();
        return switch (action) {
            case "INCLUDED_IN_MODEL_PROMPT" -> ContextDecision.includedInModel(reason);
            case "REFUSED_UNSUPPORTED_BOUNDARY" -> ContextDecision.refusedUnsupportedBoundary(reason);
            case "EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY" -> ContextDecision.excludedByPrivacyOrTrustPolicy(reason);
            default -> ContextDecision.withheldFromModel(reason);
        };
    }

    private static ExecutionBoundary boundary(ProjectMemoryTrust trust) {
        return trust == ProjectMemoryTrust.USER_OWNED
                ? ExecutionBoundary.LOCAL_USER_CONFIGURATION
                : ExecutionBoundary.LOCAL_WORKSPACE;
    }

    private static int retentionOrder(ProjectMemoryTier tier) {
        return switch (tier == null ? ProjectMemoryTier.WORKSPACE_ROOT : tier) {
            case DIRECTORY_LOCAL -> 0;
            case WORKSPACE_ROOT -> 1;
            case REPO_ROOT -> 2;
            case USER_GLOBAL -> 3;
        };
    }

    private static int renderOrder(ProjectMemoryTier tier) {
        return switch (tier == null ? ProjectMemoryTier.WORKSPACE_ROOT : tier) {
            case USER_GLOBAL -> 0;
            case REPO_ROOT -> 1;
            case WORKSPACE_ROOT -> 2;
            case DIRECTORY_LOCAL -> 3;
        };
    }

    private static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil((text == null ? 0 : text.length()) / 4.0));
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) text.chars().filter(ch -> ch == '\n').count() + 1;
    }

    private static Path absolute(Path path) {
        return (path == null ? Path.of(".") : path).toAbsolutePath().normalize();
    }

    private static boolean sameNormalized(Path left, Path right) {
        return absolute(left).equals(absolute(right));
    }

    private static String displayWorkspacePath(Path workspace, Path path) {
        try {
            Path relative = absolute(workspace).relativize(absolute(path));
            String rendered = relative.toString().replace('\\', '/');
            return rendered.isBlank() ? "." : rendered;
        } catch (Exception e) {
            return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
        }
    }

    private static String displayUserPath(Path userHome, Path path) {
        try {
            Path relative = absolute(userHome).relativize(absolute(path));
            return "%USERPROFILE%/" + relative.toString().replace('\\', '/');
        } catch (Exception e) {
            return "%USERPROFILE%/.talos/" + (path == null || path.getFileName() == null
                    ? ""
                    : path.getFileName().toString());
        }
    }

    private static String realKey(Path path) {
        try {
            return path.toRealPath().toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return absolute(path).toString().toLowerCase(Locale.ROOT);
        }
    }

    private static String hash(String value) {
        String safe = Objects.requireNonNullElse(value, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(safe.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    private record Candidate(
            ProjectMemoryTier tier,
            ProjectMemoryTrust trust,
            Path path,
            String pathHint
    ) {
        ProjectMemoryDecision decision(String action, String reason) {
            return new ProjectMemoryDecision(tier, trust, pathHint, action, reason, "", 0, 0, 0, 0, false);
        }
    }

    private record ReadDecision(ProjectMemorySource source, ProjectMemoryDecision decision) {
        static ReadDecision include(ProjectMemorySource source) {
            return new ReadDecision(source, null);
        }

        static ReadDecision skip(ProjectMemoryDecision decision) {
            return new ReadDecision(null, decision);
        }
    }

    private record Budgeted(List<ProjectMemorySource> included, List<ProjectMemorySource> dropped) {}

    private record TextSlice(String text, boolean truncated) {}
}
