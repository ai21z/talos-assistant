package dev.talos.runtime.policy;

import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.ToolError;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies whether required current-turn workspace evidence was actually gathered. */
public final class EvidenceObligationVerifier {
    public static final String MISSING_EVIDENCE_PREFIX =
            "[Evidence incomplete: required workspace evidence was not gathered in this turn.]";

    private static final Set<String> EVIDENCE_TOOLS = Set.of(
            "talos.list_dir",
            "talos.read_file",
            "talos.grep",
            "talos.retrieve",
            "talos.run_command"
    );
    private static final Set<String> CONTENT_INSPECTION_TOOLS = Set.of(
            "talos.read_file",
            "talos.grep",
            "talos.retrieve"
    );
    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile(
            "(?is)<script\\b[^>]*\\bsrc\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))");

    private EvidenceObligationVerifier() {}

    public enum Status {
        SATISFIED,
        UNSATISFIED,
        BLOCKED
    }

    /**
     * The verdict for a current-turn evidence obligation. earlierReadSucceeded records that a read of a required
     * target returned content earlier in the turn, even when that read no longer counts as current evidence.
     */
    public record Result(Status status, String message, boolean earlierReadSucceeded) {
        public Result(Status status, String message) {
            this(status, message, false);
        }

        public static Result satisfied(String message) {
            return new Result(Status.SATISFIED, message);
        }

        public static Result unsatisfied(String message) {
            return new Result(Status.UNSATISFIED, message);
        }

        public static Result blocked(String message) {
            return new Result(Status.BLOCKED, message);
        }
    }

    public static Result verify(
            EvidenceObligation obligation,
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return verify(obligation, expectedTargets, outcomes, null);
    }

    public static Result verify(
            EvidenceObligation obligation,
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace
    ) {
        return verify(obligation, expectedTargets, outcomes, workspace, Set.of());
    }

    /** Explicit reads are additional obligations; inferred web assets remain governed by linkage. */
    public static Result verify(
            EvidenceObligation obligation,
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace,
            Set<String> explicitReadTargets
    ) {
        return verify(obligation, expectedTargets, outcomes, workspace, explicitReadTargets, workspace);
    }

    /**
     * The workspace is used to discover evidence on disk. The identity root only decides whether
     * a target and a tool path name the same workspace file, so an observed assessment can match
     * spellings without reading the disk for discovery.
     */
    public static Result verify(
            EvidenceObligation obligation,
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace,
            Set<String> explicitReadTargets,
            Path identityRoot
    ) {
        EvidenceObligation safeObligation = obligation == null ? EvidenceObligation.NONE : obligation;
        Set<String> targets = expectedTargets == null ? Set.of() : expectedTargets;
        List<ToolCallLoop.ToolOutcome> safeOutcomes = outcomes == null ? List.of() : outcomes;
        return switch (safeObligation) {
            case NONE -> Result.satisfied("No workspace evidence was required.");
            case LIST_DIRECTORY_ONLY -> verifyListDirectoryOnly(safeOutcomes);
            case READ_TARGET_REQUIRED -> verifyReadTargets(targets, safeOutcomes, true, identityRoot);
            case DOCUMENT_COMPARISON_REQUIRED -> verifyDocumentComparison(workspace);
            case PATH_EXISTENCE_EVIDENCE_REQUIRED -> verifyPathExistenceTargets(targets, safeOutcomes, identityRoot);
            case PROTECTED_READ_APPROVAL_REQUIRED -> verifyProtectedRead(targets, safeOutcomes, identityRoot);
            case FILE_CONTENT_LOOKUP_REQUIRED -> verifyFileContentLookup(safeOutcomes);
            case PROJECT_SUMMARY_EVIDENCE_REQUIRED -> verifyProjectSummaryEvidence(safeOutcomes, workspace);
            case STATIC_WEB_DIAGNOSIS_REQUIRED -> verifyStaticWebDiagnosisWithExplicitReads(
                    targets, explicitReadTargets, safeOutcomes, workspace, identityRoot);
            case WORKSPACE_INSPECTION_REQUIRED, VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    verifyAnyReadOnlyEvidence(safeOutcomes);
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED ->
                    verifyUnsupportedCapability(targets, safeOutcomes, identityRoot);
        };
    }

    private static Result verifyDocumentComparison(Path workspace) {
        var contract = dev.talos.runtime.TurnTaskContractCapture.get();
        var comparison = dev.talos.runtime.task.DocumentComparisonRequest.from(contract);
        if (comparison.active() && comparison.unresolved().isEmpty()
                && dev.talos.runtime.TurnAuditCapture.comparisonEvidenceComplete(contract, workspace))
            return Result.satisfied("Both ordered snapshots were compared in the current turn.");
        return Result.unsatisfied("A complete current-turn ordered comparison was not retained.");
    }

    private static Result verifyStaticWebDiagnosisWithExplicitReads(
            Set<String> expectedTargets, Set<String> explicitReadTargets,
            List<ToolCallLoop.ToolOutcome> outcomes, Path workspace, Path identityRoot
    ) {
        Result web = verifyStaticWebDiagnosis(expectedTargets, outcomes, workspace, identityRoot);
        if (explicitReadTargets == null || explicitReadTargets.isEmpty()) return web;
        Result explicit = aggregateTargetResults(explicitReadTargets,
                target -> verifyReadTarget(target, outcomes, true, identityRoot),
                "All explicit static-web diagnosis sources were read.");
        if (explicit.status() == Status.BLOCKED) return explicit;
        if (web.status() == Status.BLOCKED) return web;
        return explicit.status() == Status.SATISFIED ? web : explicit;
    }

    public static List<String> missingLinkedScriptReadTargets(
            Path workspace,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        Set<String> linkedScripts = linkedExistingScriptTargets(workspace, outcomes);
        if (linkedScripts.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String target : linkedScripts) {
            Result result = verifySuccessfulReadTarget(target, outcomes, workspace);
            if (result.status() != Status.SATISFIED) {
                missing.add(target);
            }
        }
        return List.copyOf(missing);
    }

    private static Result verifyListDirectoryOnly(List<ToolCallLoop.ToolOutcome> outcomes) {
        boolean listedDirectory = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            String toolName = canonicalToolName(outcome.toolName());
            if ("talos.list_dir".equals(toolName) && outcome.success() && !outcome.denied()) {
                listedDirectory = true;
            }
            if (CONTENT_INSPECTION_TOOLS.contains(toolName)) {
                return Result.unsatisfied("Directory-list evidence included content inspection.");
            }
        }
        return listedDirectory
                ? Result.satisfied("Directory listing evidence was gathered.")
                : Result.unsatisfied("Directory listing evidence was not gathered.");
    }

    private static Result verifyStaticWebDiagnosis(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace,
            Path identityRoot
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("Static web diagnosis evidence was not gathered.");
        }

        Set<String> indexTargets = staticIndexTargets(expectedTargets);
        if (!indexTargets.isEmpty()) {
            Result indexResult = aggregateTargetResults(
                    indexTargets,
                    target -> verifySuccessfulReadTarget(target, outcomes, identityRoot),
                    "Static web diagnosis read index.html.");
            if (indexResult.status() == Status.BLOCKED) {
                return indexResult;
            }
            if (indexResult.status() != Status.SATISFIED) {
                return Result.unsatisfied("Static web diagnosis requires reading index.html.");
            }
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes, identityRoot);
            if (linkedScriptResult.status() != Status.SATISFIED) {
                return linkedScriptResult;
            }
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }

        if (listDirShowsIndexHtml(outcomes)) {
            Result indexResult = verifySuccessfulIndexRead(outcomes);
            if (indexResult.status() != Status.SATISFIED) {
                return indexResult;
            }
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes, identityRoot);
            if (linkedScriptResult.status() != Status.SATISFIED) {
                return linkedScriptResult;
            }
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }

        if (hasStaticWebContentInspection(outcomes)) {
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes, identityRoot);
            if (linkedScriptResult.status() != Status.SATISFIED) {
                return linkedScriptResult;
            }
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }
        return Result.unsatisfied("Static web diagnosis requires reading relevant HTML, CSS, or JavaScript.");
    }

    private static Result verifyReadTargets(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            boolean requireSuccess,
            Path identityRoot
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("No tool evidence was gathered.");
        }
        Set<String> effectiveTargets = requireSuccess
                ? withoutUnsupportedTargetsCoveredByTextFallback(expectedTargets, outcomes, identityRoot)
                : expectedTargets;
        return aggregateTargetResults(
                effectiveTargets,
                target -> verifyReadTarget(target, outcomes, requireSuccess, identityRoot),
                "Required read evidence was gathered.");
    }

    static Set<String> withoutUnsupportedTargetsCoveredByTextFallback(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return withoutUnsupportedTargetsCoveredByTextFallback(expectedTargets, outcomes, null);
    }

    static Set<String> withoutUnsupportedTargetsCoveredByTextFallback(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        if (expectedTargets == null || expectedTargets.isEmpty() || outcomes == null || outcomes.isEmpty()) {
            return expectedTargets == null ? Set.of() : expectedTargets;
        }
        Set<String> normalizedExpected = new LinkedHashSet<>();
        for (String target : expectedTargets) {
            normalizedExpected.add(normalizePath(target));
        }
        PathMatch match = PathMatch.of(identityRoot);
        Set<String> out = new LinkedHashSet<>();
        for (String target : expectedTargets) {
            String normalized = normalizePath(target);
            if (unsupportedReadFailed(normalized, outcomes, match)
                    && successfulNamedTextFallbackRead(normalized, normalizedExpected, outcomes, match)) {
                continue;
            }
            out.add(target);
        }
        return out;
    }

    private static boolean unsupportedReadFailed(
            String normalizedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            PathMatch match
    ) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!match.matchesNormalized(normalizedTarget, outcome.pathHint())) continue;
            if (ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) return true;
        }
        return false;
    }

    private static boolean successfulNamedTextFallbackRead(
            String unsupportedTarget,
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            PathMatch match
    ) {
        Set<String> candidates = sameStemTextFallbacks(unsupportedTarget);
        candidates.retainAll(expectedTargets);
        if (candidates.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success() || outcome.denied()) continue;
            for (String candidate : candidates) {
                if (match.matchesNormalized(candidate, outcome.pathHint())) return true;
            }
        }
        return false;
    }

    private static Set<String> sameStemTextFallbacks(String unsupportedTarget) {
        String normalized = normalizePath(unsupportedTarget);
        String parent = parentDirectory(normalized);
        String stem = childStem(normalized);
        if (stem.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        addFallback(out, parent, stem + ".txt");
        addFallback(out, parent, "extracted_" + stem + ".txt");
        return out;
    }

    private static void addFallback(Set<String> out, String parent, String child) {
        if (".".equals(parent) || parent.isBlank()) {
            out.add(child);
        } else {
            out.add(parent + "/" + child);
        }
    }

    private static Result verifyProtectedRead(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied(
                    "Protected read was not attempted; no approval prompt ran and no protected content was read.");
        }
        return verifyReadTargets(expectedTargets, outcomes, true, identityRoot);
    }

    private static Result verifyPathExistenceTargets(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("Path existence evidence was not gathered.");
        }
        Result firstBlocked = null;
        Result firstUnsatisfied = null;
        for (String target : expectedTargets) {
            Result result = verifyPathExistenceTarget(target, outcomes, identityRoot);
            if (result.status() == Status.SATISFIED) {
                return Result.satisfied("Path existence evidence was gathered.");
            }
            if (result.status() == Status.BLOCKED && firstBlocked == null) {
                firstBlocked = result;
            } else if (result.status() == Status.UNSATISFIED && firstUnsatisfied == null) {
                firstUnsatisfied = result;
            }
        }
        if (firstBlocked != null) return firstBlocked;
        if (firstUnsatisfied != null) return firstUnsatisfied;
        return Result.unsatisfied("Path existence evidence was not gathered.");
    }

    private static Result verifyPathExistenceTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        return judgedAcrossSpellings(identityRoot,
                match -> pathExistenceResult(expectedTarget, outcomes, match));
    }

    private static Result pathExistenceResult(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            PathMatch match
    ) {
        String expected = normalizePath(expectedTarget);
        Result read = decide(expectedTarget, "talos.read_file", outcomes, match, outcome -> {
            if (outcome.denied()) {
                return Result.blocked("Path existence read was blocked by approval.");
            }
            if (outcome.success()) {
                return Result.satisfied("Path existence evidence was gathered.");
            }
            if (ToolError.NOT_FOUND.equals(outcome.errorCode())) {
                return Result.satisfied("Path existence evidence was gathered.");
            }
            return Result.unsatisfied("Path existence read did not succeed for " + expectedTarget + ".");
        });
        if (read != null && read.status() == Status.SATISFIED) return read;
        String expectedParent = parentDirectory(expected);
        // A target directly under a leading slash has no workspace parent, though its parent text reads ".".
        boolean parentHasIdentity = expected.lastIndexOf('/') != 0;
        boolean listingDenied = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.list_dir".equals(canonicalToolName(outcome.toolName()))) continue;
            if (outcome.denied()) {
                listingDenied = true;
                continue;
            }
            if (!outcome.success()) continue;
            if (match.matchesDirectory(expectedParent, outcome.pathHint(), parentHasIdentity)
                    && directoryListingShowsTarget(outcome.summary(), expected)) {
                return new Result(Status.SATISFIED, "Path existence evidence was gathered.",
                        read != null && read.earlierReadSucceeded());
            }
        }
        if (read != null) return read;
        if (listingDenied) {
            return Result.blocked("Path existence directory listing was blocked by approval.");
        }
        return Result.unsatisfied("Path existence evidence was not gathered for " + expectedTarget + ".");
    }

    private static boolean directoryListingShowsTarget(String listing, String expectedTarget) {
        String child = childName(expectedTarget);
        if (child.isBlank()) return false;
        String expected = normalizePath(expectedTarget);
        String safeListing = listing == null ? "" : listing;
        for (String rawLine : safeListing.split("\\R")) {
            String line = normalizeDirectoryListingLine(rawLine);
            if (line.isBlank()) continue;
            if (child.equals(line) || expected.equals(line)) {
                return true;
            }
        }
        return false;
    }

    private static Result verifyReadTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            boolean requireSuccess,
            Path identityRoot
    ) {
        return judgedAcrossSpellings(identityRoot,
                match -> readTargetResult(expectedTarget, outcomes, requireSuccess, match));
    }

    private static Result readTargetResult(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            boolean requireSuccess,
            PathMatch match
    ) {
        Result read = decide(expectedTarget, "talos.read_file", outcomes, match, outcome -> {
            if (outcome.denied()) {
                return Result.blocked("Required read was blocked by approval.");
            }
            if (outcome.success() || !requireSuccess) {
                return Result.satisfied("Required read evidence was gathered.");
            }
            return Result.unsatisfied("Required successful read evidence was not gathered.");
        });
        return read != null
                ? read
                : Result.unsatisfied("Required read evidence was not gathered for " + expectedTarget + ".");
    }

    private static Result verifySuccessfulReadTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        return judgedAcrossSpellings(identityRoot, match -> {
            Result read = decide(expectedTarget, "talos.read_file", outcomes, match, outcome -> {
                if (outcome.denied()) {
                    return Result.blocked("Static web diagnosis read was blocked by approval.");
                }
                if (!outcome.success()) {
                    return Result.unsatisfied("Static web diagnosis required successful read evidence.");
                }
                return Result.satisfied("Static web diagnosis read index.html.");
            });
            return read != null ? read : Result.unsatisfied("Static web diagnosis requires reading index.html.");
        });
    }

    private static Result verifySuccessfulIndexRead(List<ToolCallLoop.ToolOutcome> outcomes) {
        Result blocked = null;
        Result failed = null;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!isIndexHtmlTarget(outcome.pathHint())) continue;
            if (outcome.denied()) {
                if (blocked == null) blocked = Result.blocked("Static web diagnosis read was blocked by approval.");
                continue;
            }
            if (!outcome.success()) {
                if (failed == null) {
                    failed = Result.unsatisfied("Static web diagnosis required successful index.html read evidence.");
                }
                continue;
            }
            return Result.satisfied("Static web diagnosis read index.html.");
        }
        if (blocked != null) return blocked;
        if (failed != null) return failed;
        return Result.unsatisfied("Static web diagnosis requires reading index.html when it is present.");
    }

    private static Set<String> staticIndexTargets(Set<String> expectedTargets) {
        if (expectedTargets == null || expectedTargets.isEmpty()) return Set.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String target : expectedTargets) {
            if (isIndexHtmlTarget(target)) out.add(target);
        }
        return out.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(out);
    }

    private static boolean listDirShowsIndexHtml(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.list_dir".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success()) continue;
            String output = outcome.summary() == null ? "" : outcome.summary();
            for (String line : output.split("\\R")) {
                if (isIndexHtmlTarget(line.strip())) return true;
            }
        }
        return false;
    }

    private static boolean hasStaticWebContentInspection(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            String toolName = canonicalToolName(outcome.toolName());
            if (!CONTENT_INSPECTION_TOOLS.contains(toolName)) continue;
            if (outcome.denied() || !outcome.success()) continue;
            if ("talos.read_file".equals(toolName) && !isStaticWebTarget(outcome.pathHint())) continue;
            return true;
        }
        return false;
    }

    private static Result verifyLinkedScriptsFromReadIndexes(
            Path workspace,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        Set<String> linkedScripts = linkedExistingScriptTargets(workspace, outcomes);
        if (linkedScripts.isEmpty()) {
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }
        Result scriptResult = aggregateTargetResults(
                linkedScripts,
                target -> verifySuccessfulReadTarget(target, outcomes, identityRoot),
                "Static web diagnosis linked script evidence was gathered.");
        if (scriptResult.status() == Status.SATISFIED) {
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }
        if (scriptResult.status() == Status.BLOCKED) {
            return Result.blocked("Static web diagnosis linked script read was blocked by approval.");
        }
        return Result.unsatisfied("Static web diagnosis requires reading linked script source target(s): "
                + String.join(", ", linkedScripts) + ".");
    }

    private static Set<String> linkedExistingScriptTargets(
            Path workspace,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        if (workspace == null || outcomes == null || outcomes.isEmpty()) return Set.of();
        NoFollowPathPolicy.GuardedRoot workspaceFiles;
        Path ws;
        try {
            workspaceFiles = NoFollowPathPolicy.bindExistingDirectory(workspace);
            ws = workspaceFiles.path();
        } catch (Exception e) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success() || outcome.denied()) continue;
            if (!isIndexHtmlTarget(outcome.pathHint())) continue;
            Path index = resolveWorkspacePath(ws, outcome.pathHint());
            if (!isRegularWorkspaceFile(workspaceFiles, index)) continue;
            String html;
            try {
                html = workspaceFiles.readString(index, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                continue;
            }
            Matcher matcher = SCRIPT_SRC_PATTERN.matcher(html);
            while (matcher.find()) {
                String src = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
                Path linked = resolveLinkedSource(ws, index, src);
                if (!isRegularWorkspaceFile(workspaceFiles, linked)) continue;
                out.add(normalizePath(ws.relativize(linked).toString()).replace('\\', '/'));
            }
        }
        return out.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(out);
    }

    private static boolean isRegularWorkspaceFile(
            NoFollowPathPolicy.GuardedRoot workspaceFiles,
            Path path) {
        if (workspaceFiles == null || path == null) return false;
        try {
            return workspaceFiles.inspect(path)
                    == NoFollowPathPolicy.EntryType.REGULAR_FILE;
        } catch (Exception rejected) {
            return false;
        }
    }

    private static Path resolveWorkspacePath(Path workspace, String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return null;
        try {
            Path candidate = Path.of(pathHint);
            Path resolved = candidate.isAbsolute()
                    ? candidate
                    : workspace.resolve(candidate);
            resolved = resolved.toAbsolutePath().normalize();
            return resolved.startsWith(workspace) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path resolveLinkedSource(Path workspace, Path index, String src) {
        String cleaned = cleanLinkedSource(src);
        if (cleaned.isBlank()) return null;
        try {
            Path resolved = cleaned.startsWith("/")
                    ? workspace.resolve(cleaned.replaceFirst("^/+", ""))
                    : index.getParent().resolve(cleaned);
            resolved = resolved.toAbsolutePath().normalize();
            return resolved.startsWith(workspace) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cleanLinkedSource(String src) {
        if (src == null) return "";
        String cleaned = src.strip().replace('\\', '/');
        if (cleaned.isBlank()
                || cleaned.startsWith("#")
                || cleaned.startsWith("//")
                || cleaned.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
                || cleaned.toLowerCase(java.util.Locale.ROOT).startsWith("https://")
                || cleaned.toLowerCase(java.util.Locale.ROOT).startsWith("data:")
                || cleaned.toLowerCase(java.util.Locale.ROOT).startsWith("javascript:")) {
            return "";
        }
        int fragment = cleaned.indexOf('#');
        if (fragment >= 0) cleaned = cleaned.substring(0, fragment);
        int query = cleaned.indexOf('?');
        if (query >= 0) cleaned = cleaned.substring(0, query);
        return cleaned.strip();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static boolean isStaticWebTarget(String path) {
        String normalized = normalizePath(path).toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".html")
                || normalized.endsWith(".htm")
                || normalized.endsWith(".css")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx");
    }

    private static boolean isIndexHtmlTarget(String path) {
        String normalized = normalizePath(path).toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("index.html") || normalized.endsWith("/index.html");
    }

    private static Result verifyAnyReadOnlyEvidence(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            String toolName = canonicalToolName(outcome.toolName());
            if ("talos.run_command".equals(toolName)) {
                return Result.satisfied("Command outcome evidence was gathered.");
            }
            if (EVIDENCE_TOOLS.contains(toolName) && outcome.success() && !outcome.denied()) {
                return Result.satisfied("Read-only workspace evidence was gathered.");
            }
        }
        return Result.unsatisfied("Read-only workspace evidence was not gathered.");
    }

    private static Result verifyFileContentLookup(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            String toolName = canonicalToolName(outcome.toolName());
            if (!CONTENT_INSPECTION_TOOLS.contains(toolName)
                    || !outcome.success()
                    || outcome.denied()) {
                continue;
            }
            if (!outcome.sourcePaths().isEmpty()) {
                return Result.satisfied("Source-bearing file-content evidence was gathered.");
            }
            if ("talos.read_file".equals(toolName) && !normalizePath(outcome.pathHint()).isBlank()) {
                return Result.satisfied("Source-bearing file-content evidence was gathered.");
            }
        }
        return Result.unsatisfied("Source-bearing file-content evidence was not gathered.");
    }

    private static Result verifyProjectSummaryEvidence(
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace
    ) {
        ProjectSummaryEvidencePolicy.AnchorSelection selection =
                ProjectSummaryEvidencePolicy.selectAnchor(workspace);
        if (selection.status() == ProjectSummaryEvidencePolicy.SelectionStatus.UNAVAILABLE) {
            if (workspace == null) {
                return verifyProjectSummaryFallback(outcomes);
            }
            return Result.unsatisfied("Project summary anchor availability could not be checked.");
        }
        if (selection.status() == ProjectSummaryEvidencePolicy.SelectionStatus.ABSENT) {
            return verifyProjectSummaryFallback(outcomes);
        }
        String anchor = selection.anchor();
        if (ProjectSummaryEvidencePolicy.hasSourceEvidence(workspace, anchor, outcomes)) {
            return Result.satisfied("Project summary anchor evidence was gathered from " + anchor + ".");
        }
        return Result.unsatisfied(
                "Project summary requires source-bearing evidence from " + anchor + ".");
    }

    private static Result verifyProjectSummaryFallback(List<ToolCallLoop.ToolOutcome> outcomes) {
        Result fallback = verifyFileContentLookup(outcomes);
        return fallback.status() == Status.SATISFIED
                ? Result.satisfied("Project summary content evidence was gathered.")
                : Result.unsatisfied("Project summary requires source-bearing workspace content evidence.");
    }

    private static Result verifyUnsupportedCapability(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("Unsupported capability evidence was not gathered.");
        }
        if (expectedTargets.isEmpty()) {
            return Result.unsatisfied("Unsupported capability target was not identified.");
        }
        return aggregateTargetResults(
                expectedTargets,
                target -> verifyUnsupportedCapabilityTarget(target, outcomes, identityRoot),
                "Unsupported capability evidence was gathered.");
    }

    private static Result verifyUnsupportedCapabilityTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path identityRoot
    ) {
        boolean unsupportedTarget = EvidenceObligationPolicy.requiresUnsupportedCapabilityCheck(Path.of(expectedTarget));
        return judgedAcrossSpellings(identityRoot, match -> {
            Result read = decide(expectedTarget, "talos.read_file", outcomes, match, outcome -> {
                if (outcome.denied()) {
                    return Result.blocked("Unsupported capability check was blocked by approval.");
                }
                if (unsupportedTarget) {
                    return ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())
                            ? Result.satisfied("Unsupported capability evidence was gathered.")
                            : Result.unsatisfied("Unsupported target was read without an unsupported-format result.");
                }
                return Result.satisfied("Normal read evidence was gathered for non-unsupported target.");
            });
            return read != null
                    ? read
                    : Result.unsatisfied("Unsupported capability evidence was not gathered for " + expectedTarget + ".");
        });
    }

    /** Every spelling of the target is judged together, so equivalent spellings give equivalent results. */
    private static Result judgedAcrossSpellings(Path identityRoot, Function<PathMatch, Result> verifier) {
        return verifier.apply(PathMatch.of(identityRoot));
    }

    /**
     * Every outcome naming the target, under any spelling, is weighed by the read-outcome contract. A satisfying
     * outcome counts while no later successful write changed the same file. Without one, a denial blocks, and
     * otherwise the first other result stands. A successful read is recorded as having happened in every case.
     * Null means that no outcome named the target.
     */
    private static Result decide(
            String expectedTarget,
            String toolName,
            List<ToolCallLoop.ToolOutcome> outcomes,
            PathMatch match,
            Function<ToolCallLoop.ToolOutcome, Result> perOutcome
    ) {
        Result satisfied = null;
        Result blocked = null;
        Result other = null;
        boolean earlierReadSucceeded = false;
        boolean matched = false;
        for (int index = 0; index < outcomes.size(); index++) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(index);
            if (!toolName.equals(canonicalToolName(outcome.toolName()))) continue;
            if (!match.matches(expectedTarget, outcome.pathHint())) continue;
            matched = true;
            if (outcome.success() && !outcome.denied()) earlierReadSucceeded = true;
            Result result = perOutcome.apply(outcome);
            if (result.status() == Status.SATISFIED && changedLater(expectedTarget, outcomes, index, match)) {
                result = Result.unsatisfied(
                        "Read evidence for " + expectedTarget + " is no longer current, because the file was "
                                + "changed later in this turn.");
            }
            if (result.status() == Status.SATISFIED) {
                if (satisfied == null) satisfied = result;
            } else if (result.status() == Status.BLOCKED) {
                if (blocked == null) blocked = result;
            } else if (other == null) {
                other = result;
            }
        }
        if (!matched) return null;
        Result chosen = satisfied != null ? satisfied : blocked != null ? blocked : other;
        return earlierReadSucceeded
                ? new Result(chosen.status(), chosen.message(), true)
                : chosen;
    }

    /** True when a later successful mutation in the turn wrote the same file, so an earlier read is stale. */
    private static boolean changedLater(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            int index,
            PathMatch match
    ) {
        for (int later = index + 1; later < outcomes.size(); later++) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(later);
            if (!outcome.success() || !outcome.mutating()) continue;
            if (match.mayName(expectedTarget, outcome.pathHint())) return true;
            var plan = outcome.workspaceOperationPlan();
            if (plan == null) continue;
            for (var effect : plan.pathEffects()) {
                if (match.mayName(expectedTarget, effect.path())) return true;
            }
        }
        return false;
    }

    /**
     * A target matches a tool path when the normalized text is equal, or, with an identity root,
     * when both name the same workspace file.
     */
    private record PathMatch(Path identityRoot) {
        private static final PathMatch TEXT = new PathMatch(null);

        static PathMatch of(Path identityRoot) {
            return identityRoot == null ? TEXT : new PathMatch(identityRoot);
        }

        boolean matches(String target, String pathHint) {
            return normalizePath(target).equals(normalizePath(pathHint))
                    || sameFile(target, pathHint);
        }

        /** The target was normalized already, and normalizing it again could change its text. */
        boolean matchesNormalized(String normalizedTarget, String pathHint) {
            return normalizedTarget.equals(normalizePath(pathHint))
                    || sameFile(normalizedTarget, pathHint);
        }

        boolean matchesDirectory(String directory, String pathHint, boolean directoryHasIdentity) {
            return directory.equals(normalizeDirectory(pathHint))
                    || directoryHasIdentity && sameFile(directory, pathHint.isBlank() ? "." : pathHint);
        }

        /** Text or a possible same file, for staleness, where an unproven match must count as a change. */
        boolean mayName(String target, String pathHint) {
            return normalizePath(target).equals(normalizePath(pathHint))
                    || identityRoot != null && WorkspacePathIdentity.mayMatch(identityRoot, target, pathHint);
        }

        private boolean sameFile(String target, String pathHint) {
            return identityRoot != null && WorkspacePathIdentity.matches(identityRoot, target, pathHint);
        }
    }

    private static Result aggregateTargetResults(
            Set<String> expectedTargets,
            Function<String, Result> verifier,
            String satisfiedMessage
    ) {
        Result firstBlocked = null;
        Result firstUnsatisfied = null;
        boolean anyReadSucceeded = false;
        for (String target : expectedTargets) {
            Result result = verifier.apply(target);
            anyReadSucceeded |= result.earlierReadSucceeded();
            if (result.status() == Status.BLOCKED && firstBlocked == null) {
                firstBlocked = result;
            } else if (result.status() == Status.UNSATISFIED && firstUnsatisfied == null) {
                firstUnsatisfied = result;
            }
        }
        Result chosen = firstBlocked != null ? firstBlocked
                : firstUnsatisfied != null ? firstUnsatisfied
                : Result.satisfied(satisfiedMessage);
        return new Result(chosen.status(), chosen.message(), anyReadSucceeded);
    }

    private static String normalizePath(String path) {
        String normalized = ToolCallSupport.normalizePath(path).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeDirectory(String path) {
        String normalized = normalizePath(path);
        return normalized.isBlank() ? "." : normalized;
    }

    private static String parentDirectory(String normalizedPath) {
        String normalized = normalizePath(normalizedPath);
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) return ".";
        String parent = normalized.substring(0, slash);
        return parent.isBlank() ? "." : parent;
    }

    private static String childName(String normalizedPath) {
        String normalized = normalizePath(normalizedPath);
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String childStem(String normalizedPath) {
        String child = childName(normalizedPath);
        int dot = child.lastIndexOf('.');
        return dot > 0 ? child.substring(0, dot) : child;
    }

    private static String normalizeDirectoryListingLine(String line) {
        String normalized = normalizePath(line == null ? "" : line.strip());
        while (normalized.startsWith("- ")) {
            normalized = normalized.substring(2).strip();
        }
        while (normalized.startsWith("* ")) {
            normalized = normalized.substring(2).strip();
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
