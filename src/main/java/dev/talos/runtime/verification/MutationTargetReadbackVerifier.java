package dev.talos.runtime.verification;

import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.VerificationStatus;

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

    static Result verify(
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations
    ) {
        return verify(root, successfulMutations, null);
    }

    static Result verify(
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            WorkspaceOperationAuthorityPlan expectedWorkspaceOperation
    ) {
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> mutationTargets = new LinkedHashSet<>();
        List<WorkspaceOperationPlan> workspaceOperationPlans = new ArrayList<>();
        List<String> workspaceOperationToolNames = new ArrayList<>();

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
                boolean workspaceOperation =
                        WorkspaceOperationPlanner.isWorkspaceOperationTool(
                                outcome.toolName())
                                || workspaceOperationPlan != null;
                if (workspaceOperation) {
                    if (workspaceOperationPlan == null
                            || workspaceOperationPlan.pathEffects().isEmpty()) {
                        problems.add(
                                outcome.toolName()
                                        + " succeeded without a structured workspace-operation plan.");
                    } else {
                        workspaceOperationPlans.add(workspaceOperationPlan);
                        workspaceOperationToolNames.add(outcome.toolName());
                    }
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

        requireWorkspaceOperationIdentity(
                expectedWorkspaceOperation,
                workspaceOperationPlans,
                workspaceOperationToolNames,
                problems);

        return new Result(facts, problems, mutationTargets, workspaceOperationPlans);
    }

    private static void requireWorkspaceOperationIdentity(
            WorkspaceOperationAuthorityPlan expected,
            List<WorkspaceOperationPlan> actual,
            List<String> actualToolNames,
            List<String> problems
    ) {
        if (expected == null) return;
        if (expected.state()
                != WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE) {
            if (!actual.isEmpty()) {
                problems.add(
                        "workspace operation succeeded without current-turn typed authority.");
            }
            return;
        }
        if (actual.size() != 1) {
            problems.add(
                    "typed workspace operation expected exactly one successful structured outcome; found "
                            + actual.size() + ".");
            return;
        }
        WorkspaceOperationPlan observed = actual.getFirst();
        String observedTool = actualToolNames == null
                        || actualToolNames.size() != actual.size()
                ? ""
                : actualToolNames.getFirst();
        String expectedTool = expected.authorizedOperations()
                .getFirst().toolName();
        String expectedOperationId = expected.authorizedOperations()
                .getFirst().operationId();
        WorkspaceOperationPlan expectedPlan = WorkspaceOperationPlanner
                .authorityPlan(expected)
                .orElseThrow(() -> new IllegalArgumentException(
                        "authorized workspace operation lacks a runtime plan"));
        if (!expectedTool.equals(observedTool)
                || !expected.planId().equals(observed.authorityPlanId())
                || !expectedOperationId.equals(observed.operationId())
                || !expectedPlan.equals(observed)) {
            problems.add(
                    "workspace operation outcome differs from current-turn typed authority.");
        }
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
        if (!StaticWebFileAccess.isRegularFile(root, target)) {
            problems.add(pathHint + ": mutated target is not a readable file after apply.");
            return;
        }
        String content;
        try {
            content = StaticWebFileAccess.readString(root, target);
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
