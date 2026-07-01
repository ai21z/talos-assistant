package dev.talos.runtime.verification;

import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.VerificationStatus;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies generic post-mutation target readability before task-specific static checks run. */
final class MutationTargetReadbackVerifier {

    private MutationTargetReadbackVerifier() {}

    static Result verify(Path root, List<ToolCallLoop.ToolOutcome> successfulMutations) {
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> mutationTargets = new LinkedHashSet<>();
        List<WorkspaceOperationPlan> workspaceOperationPlans = new ArrayList<>();

        if (successfulMutations != null) {
            for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
                // T752: explicit null guard - a null outcome previously reached
                // line-42 only via the blank-pathHint correlation, which static
                // analysis flagged as an NPE candidate in the readback
                // verifier. Behavior preserved: a null entry still records the
                // generic no-target-path problem.
                if (outcome == null) {
                    problems.add("tool succeeded but did not expose a target path.");
                    continue;
                }
                WorkspaceOperationPlan workspaceOperationPlan = outcome.workspaceOperationPlan();
                if (workspaceOperationPlan != null && !workspaceOperationPlan.pathEffects().isEmpty()) {
                    workspaceOperationPlans.add(workspaceOperationPlan);
                    continue;
                }
                String pathHint = normalizePath(outcome.pathHint());
                if (pathHint.isBlank()) {
                    problems.add(outcome.toolName() + " succeeded but did not expose a target path.");
                    continue;
                }
                mutationTargets.add(pathHint);
                verifyTarget(root, pathHint, outcome.fileVerificationStatus(), facts, problems);
            }
        }

        return new Result(facts, problems, mutationTargets, workspaceOperationPlans);
    }

    record Result(
            List<String> facts,
            List<String> problems,
            Set<String> mutationTargets,
            List<WorkspaceOperationPlan> workspaceOperationPlans
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            mutationTargets = mutationTargets == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(mutationTargets));
            workspaceOperationPlans = workspaceOperationPlans == null
                    ? List.of()
                    : List.copyOf(workspaceOperationPlans);
        }
    }

    private static void verifyTarget(
            Path root,
            String pathHint,
            VerificationStatus fileVerificationStatus,
            List<String> facts,
            List<String> problems
    ) {
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": target path is invalid (" + e.getMessage() + ")");
            return;
        }
        if (!target.startsWith(root)) {
            problems.add(pathHint + ": target path resolves outside the workspace.");
            return;
        }
        if (!Files.isRegularFile(target)) {
            problems.add(pathHint + ": mutated target is not a readable file after apply.");
            return;
        }
        String content;
        try {
            content = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": mutated target could not be read after apply (" + e.getMessage() + ")");
            return;
        }
        if (content.isBlank()) {
            problems.add(pathHint + ": mutated target is empty after apply.");
            return;
        }
        if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(content)) {
            problems.add(pathHint + ": mutated target contains only a template placeholder.");
            return;
        }
        if (fileVerificationStatus != null && !fileVerificationStatus.acceptable()) {
            problems.add(pathHint + ": file-level verification reported " + fileVerificationStatus.label() + ".");
            return;
        }
        facts.add(pathHint + ": mutated target exists and is readable.");
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
