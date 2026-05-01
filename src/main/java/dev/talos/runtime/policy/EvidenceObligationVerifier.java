package dev.talos.runtime.policy;

import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.tools.ToolError;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Verifies whether required current-turn workspace evidence was actually gathered. */
public final class EvidenceObligationVerifier {
    public static final String MISSING_EVIDENCE_PREFIX =
            "[Evidence incomplete: required workspace evidence was not gathered in this turn.]";

    private static final Set<String> EVIDENCE_TOOLS = Set.of(
            "talos.list_dir",
            "talos.read_file",
            "talos.grep",
            "talos.retrieve"
    );
    private static final Set<String> CONTENT_INSPECTION_TOOLS = Set.of(
            "talos.read_file",
            "talos.grep",
            "talos.retrieve"
    );

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
        EvidenceObligation safeObligation = obligation == null ? EvidenceObligation.NONE : obligation;
        Set<String> targets = expectedTargets == null ? Set.of() : expectedTargets;
        List<ToolCallLoop.ToolOutcome> safeOutcomes = outcomes == null ? List.of() : outcomes;
        return switch (safeObligation) {
            case NONE -> Result.satisfied("No workspace evidence was required.");
            case LIST_DIRECTORY_ONLY -> verifyListDirectoryOnly(safeOutcomes);
            case READ_TARGET_REQUIRED -> verifyReadTargets(targets, safeOutcomes, false);
            case PROTECTED_READ_APPROVAL_REQUIRED -> verifyProtectedRead(targets, safeOutcomes);
            case WORKSPACE_INSPECTION_REQUIRED, VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    verifyAnyReadOnlyEvidence(safeOutcomes);
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED -> verifyUnsupportedCapability(targets, safeOutcomes);
        };
    }

    private static Result verifyListDirectoryOnly(List<ToolCallLoop.ToolOutcome> outcomes) {
        boolean listedDirectory = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            String toolName = outcome.toolName();
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
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!"talos.read_file".equals(outcome.toolName())) continue;
            if (!expected.equals(normalizePath(outcome.pathHint()))) continue;
            if (outcome.denied()) {
                return Result.blocked("Required read was blocked by approval.");
            }
            if (requireSuccess && !outcome.success()) {
                return Result.unsatisfied("Required successful read evidence was not gathered.");
            }
            return Result.satisfied("Required read evidence was gathered.");
        }
        return Result.unsatisfied("Required read evidence was not gathered for " + expectedTarget + ".");
    }

    private static Result verifyAnyReadOnlyEvidence(List<ToolCallLoop.ToolOutcome> outcomes) {
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (EVIDENCE_TOOLS.contains(outcome.toolName())) {
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
            if (!"talos.read_file".equals(outcome.toolName())) continue;
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
}
