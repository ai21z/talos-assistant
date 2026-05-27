package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.ToolAliasPolicy;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies exact edit replacement evidence as a non-web static fallback. */
final class ExactEditReplacementVerifier {

    private ExactEditReplacementVerifier() {}

    static Result verify(Path root, List<ToolCallLoop.ToolOutcome> outcomes) {
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (outcomes == null || outcomes.isEmpty()) {
            return new Result(false, false, false, facts, problems);
        }

        boolean verifiedAny = false;
        boolean hasProblem = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (!hasExactEditEvidence(outcome)) {
                continue;
            }
            verifiedAny = true;
            String pathHint = normalizePath(outcome.pathHint());
            Path target = resolveWorkspaceFile(root, pathHint);
            if (target == null || !Files.isRegularFile(target)) {
                problems.add(pathHint + ": exact edit replacement target is not readable after apply.");
                hasProblem = true;
                continue;
            }
            String content;
            try {
                content = Files.readString(target);
            } catch (Exception e) {
                problems.add(pathHint + ": exact edit replacement target could not be read after apply ("
                        + e.getMessage() + ")");
                hasProblem = true;
                continue;
            }

            ToolMutationEvidence evidence = outcome.mutationEvidence();
            String oldString = evidence.oldString();
            String newString = evidence.newString();
            if (!newString.isEmpty() && !content.contains(newString)) {
                problems.add(pathHint + ": exact edit replacement text was not observed after apply.");
                hasProblem = true;
                continue;
            }
            if (!oldString.isEmpty()
                    && (newString.isEmpty() || !newString.contains(oldString))
                    && content.contains(oldString)) {
                problems.add(pathHint + ": exact edit replacement old text remained after apply.");
                hasProblem = true;
                continue;
            }
            facts.add(pathHint + ": exact edit replacement observed in post-apply file.");
        }

        return new Result(
                verifiedAny,
                verifiedAny && allSuccessfulMutationsHaveExactEditEvidence(outcomes),
                hasProblem,
                facts,
                problems);
    }

    record Result(
            boolean verifiedAny,
            boolean coversAllSuccessfulMutations,
            boolean hasProblem,
            List<String> facts,
            List<String> problems
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    private static boolean allSuccessfulMutationsHaveExactEditEvidence(List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            if (!hasExactEditEvidence(outcome)) return false;
        }
        return true;
    }

    private static boolean hasExactEditEvidence(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null
                && outcome.success()
                && "edit_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                && outcome.mutationEvidence() != null
                && outcome.mutationEvidence().exactEditReplacement();
    }

    private static Path resolveWorkspaceFile(Path root, String path) {
        if (root == null) return null;
        try {
            Path resolved = root.resolve(normalizePath(path)).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (InvalidPathException e) {
            return null;
        }
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
