package dev.talos.runtime.policy;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolAliasPolicy;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.ToolError;

import java.nio.file.Files;
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

    public record Result(Status status, String message) {
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
        EvidenceObligation safeObligation = obligation == null ? EvidenceObligation.NONE : obligation;
        Set<String> targets = expectedTargets == null ? Set.of() : expectedTargets;
        List<ToolCallLoop.ToolOutcome> safeOutcomes = outcomes == null ? List.of() : outcomes;
        return switch (safeObligation) {
            case NONE -> Result.satisfied("No workspace evidence was required.");
            case LIST_DIRECTORY_ONLY -> verifyListDirectoryOnly(safeOutcomes);
            case READ_TARGET_REQUIRED -> verifyReadTargets(targets, safeOutcomes, false);
            case PROTECTED_READ_APPROVAL_REQUIRED -> verifyProtectedRead(targets, safeOutcomes);
            case STATIC_WEB_DIAGNOSIS_REQUIRED -> verifyStaticWebDiagnosis(targets, safeOutcomes, workspace);
            case WORKSPACE_INSPECTION_REQUIRED, VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    verifyAnyReadOnlyEvidence(safeOutcomes);
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED -> verifyUnsupportedCapability(targets, safeOutcomes);
        };
    }

    public static List<String> missingLinkedScriptReadTargets(
            Path workspace,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        Set<String> linkedScripts = linkedExistingScriptTargets(workspace, outcomes);
        if (linkedScripts.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String target : linkedScripts) {
            Result result = verifySuccessfulReadTarget(target, outcomes);
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
            if ("talos.list_dir".equals(toolName)) {
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
            Path workspace
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("Static web diagnosis evidence was not gathered.");
        }

        Set<String> indexTargets = staticIndexTargets(expectedTargets);
        if (!indexTargets.isEmpty()) {
            Result indexResult = aggregateTargetResults(
                    indexTargets,
                    target -> verifySuccessfulReadTarget(target, outcomes),
                    "Static web diagnosis read index.html.");
            if (indexResult.status() == Status.BLOCKED) {
                return indexResult;
            }
            if (indexResult.status() != Status.SATISFIED) {
                return Result.unsatisfied("Static web diagnosis requires reading index.html.");
            }
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes);
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
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes);
            if (linkedScriptResult.status() != Status.SATISFIED) {
                return linkedScriptResult;
            }
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }

        if (hasStaticWebContentInspection(outcomes)) {
            Result linkedScriptResult = verifyLinkedScriptsFromReadIndexes(workspace, outcomes);
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
            boolean requireSuccess
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("No tool evidence was gathered.");
        }
        return aggregateTargetResults(
                expectedTargets,
                target -> verifyReadTarget(target, outcomes, requireSuccess),
                "Required read evidence was gathered.");
    }

    private static Result verifyProtectedRead(Set<String> expectedTargets, List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied(
                    "Protected read was not attempted; no approval prompt ran and no protected content was read.");
        }
        return verifyReadTargets(expectedTargets, outcomes, true);
    }

    private static Result verifyReadTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes,
            boolean requireSuccess
    ) {
        String expected = normalizePath(expectedTarget);
        boolean matchedTarget = false;
        boolean successfulRead = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!expected.equals(normalizePath(outcome.pathHint()))) continue;
            matchedTarget = true;
            if (outcome.denied()) {
                return Result.blocked("Required read was blocked by approval.");
            }
            if (outcome.success()) {
                successfulRead = true;
            }
        }
        if (matchedTarget && (!requireSuccess || successfulRead)) {
            return Result.satisfied("Required read evidence was gathered.");
        }
        if (matchedTarget && requireSuccess) {
            return Result.unsatisfied("Required successful read evidence was not gathered.");
        }
        return Result.unsatisfied("Required read evidence was not gathered for " + expectedTarget + ".");
    }

    private static Result verifySuccessfulReadTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        String expected = normalizePath(expectedTarget);
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!expected.equals(normalizePath(outcome.pathHint()))) continue;
            if (outcome.denied()) {
                return Result.blocked("Static web diagnosis read was blocked by approval.");
            }
            if (!outcome.success()) {
                return Result.unsatisfied("Static web diagnosis required successful read evidence.");
            }
            return Result.satisfied("Static web diagnosis read index.html.");
        }
        return Result.unsatisfied("Static web diagnosis requires reading index.html.");
    }

    private static Result verifySuccessfulIndexRead(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!isIndexHtmlTarget(outcome.pathHint())) continue;
            if (outcome.denied()) {
                return Result.blocked("Static web diagnosis read was blocked by approval.");
            }
            if (!outcome.success()) {
                return Result.unsatisfied("Static web diagnosis required successful index.html read evidence.");
            }
            return Result.satisfied("Static web diagnosis read index.html.");
        }
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
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        Set<String> linkedScripts = linkedExistingScriptTargets(workspace, outcomes);
        if (linkedScripts.isEmpty()) {
            return Result.satisfied("Static web diagnosis evidence was gathered.");
        }
        Result scriptResult = aggregateTargetResults(
                linkedScripts,
                target -> verifySuccessfulReadTarget(target, outcomes),
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
        Path ws;
        try {
            ws = workspace.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success() || outcome.denied()) continue;
            if (!isIndexHtmlTarget(outcome.pathHint())) continue;
            Path index = resolveWorkspacePath(ws, outcome.pathHint());
            if (index == null || !Files.isRegularFile(index)) continue;
            String html;
            try {
                html = Files.readString(index);
            } catch (Exception ignored) {
                continue;
            }
            Matcher matcher = SCRIPT_SRC_PATTERN.matcher(html);
            while (matcher.find()) {
                String src = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
                Path linked = resolveLinkedSource(ws, index, src);
                if (linked == null || !Files.isRegularFile(linked)) continue;
                out.add(normalizePath(ws.relativize(linked).toString()).replace('\\', '/'));
            }
        }
        return out.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(out);
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
            if (EVIDENCE_TOOLS.contains(canonicalToolName(outcome.toolName()))) {
                return Result.satisfied("Read-only workspace evidence was gathered.");
            }
        }
        return Result.unsatisfied("Read-only workspace evidence was not gathered.");
    }

    private static Result verifyUnsupportedCapability(
            Set<String> expectedTargets,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        if (outcomes.isEmpty()) {
            return Result.unsatisfied("Unsupported capability evidence was not gathered.");
        }
        if (expectedTargets.isEmpty()) {
            return Result.unsatisfied("Unsupported capability target was not identified.");
        }
        return aggregateTargetResults(
                expectedTargets,
                target -> verifyUnsupportedCapabilityTarget(target, outcomes),
                "Unsupported capability evidence was gathered.");
    }

    private static Result verifyUnsupportedCapabilityTarget(
            String expectedTarget,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        String expected = normalizePath(expectedTarget);
        boolean unsupportedTarget = UnsupportedDocumentFormats.isUnsupported(Path.of(expectedTarget));
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!expected.equals(normalizePath(outcome.pathHint()))) continue;
            if (outcome.denied()) {
                return Result.blocked("Unsupported capability check was blocked by approval.");
            }
            if (unsupportedTarget) {
                return ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())
                        ? Result.satisfied("Unsupported capability evidence was gathered.")
                        : Result.unsatisfied("Unsupported target was read without an unsupported-format result.");
            }
            return Result.satisfied("Normal read evidence was gathered for non-unsupported target.");
        }
        return Result.unsatisfied("Unsupported capability evidence was not gathered for " + expectedTarget + ".");
    }

    private static Result aggregateTargetResults(
            Set<String> expectedTargets,
            Function<String, Result> verifier,
            String satisfiedMessage
    ) {
        Result firstBlocked = null;
        Result firstUnsatisfied = null;
        for (String target : expectedTargets) {
            Result result = verifier.apply(target);
            if (result.status() == Status.BLOCKED && firstBlocked == null) {
                firstBlocked = result;
            } else if (result.status() == Status.UNSATISFIED && firstUnsatisfied == null) {
                firstUnsatisfied = result;
            }
        }
        if (firstBlocked != null) return firstBlocked;
        if (firstUnsatisfied != null) return firstUnsatisfied;
        return Result.satisfied(satisfiedMessage);
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

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
