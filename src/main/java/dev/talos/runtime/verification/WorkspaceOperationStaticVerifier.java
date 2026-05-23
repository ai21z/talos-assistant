package dev.talos.runtime.verification;

import dev.talos.runtime.workspace.WorkspaceOperationPlan;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies deterministic postconditions from workspace operation plans. */
final class WorkspaceOperationStaticVerifier {

    private WorkspaceOperationStaticVerifier() {}

    static Result verify(Path root, List<WorkspaceOperationPlan> plans) {
        WorkspaceOperationAccumulator accumulator = new WorkspaceOperationAccumulator();
        if (plans != null) {
            for (WorkspaceOperationPlan plan : plans) {
                accumulateWorkspaceOperation(accumulator, plan);
            }
        }
        return verifyWorkspaceOperations(root, accumulator);
    }

    private static void accumulateWorkspaceOperation(
            WorkspaceOperationAccumulator accumulator,
            WorkspaceOperationPlan plan
    ) {
        if (accumulator == null || plan == null) return;
        for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
            String path = normalizePath(effect.path());
            if (path.isBlank()) continue;
            WorkspaceOperationPlan.OperationKind kind = effect.operationKind() == null
                    ? plan.operationKind()
                    : effect.operationKind();
            WorkspaceOperationPlan.PathRole role = effect.role();

            switch (kind) {
                case CREATE_DIRECTORY -> putExists(
                        accumulator, path, true, true, "directory exists");
                case COPY_PATH -> {
                    if (role == WorkspaceOperationPlan.PathRole.SOURCE) {
                        accumulator.expectedTargetExemptions().add(path);
                        putExists(accumulator, path, false, false, "copy source exists");
                    } else {
                        putExists(accumulator, path, false, true, "copy destination exists");
                    }
                }
                case MOVE_PATH -> {
                    if (role == WorkspaceOperationPlan.PathRole.SOURCE) {
                        accumulator.expectedTargetExemptions().add(path);
                        putAbsent(accumulator, path, "move source absent");
                    } else {
                        putExists(accumulator, path, false, true, "move destination exists");
                    }
                }
                case RENAME_PATH -> {
                    if (role == WorkspaceOperationPlan.PathRole.SOURCE) {
                        accumulator.expectedTargetExemptions().add(path);
                        putAbsent(accumulator, path, "rename source absent");
                    } else {
                        putExists(accumulator, path, false, true, "rename destination exists");
                    }
                }
                case DELETE_PATH -> {
                    accumulator.expectedTargetExemptions().add(path);
                    putAbsent(accumulator, path, "deleted target absent");
                }
                case WRITE_FILE, BATCH_APPLY -> {
                    if (role == WorkspaceOperationPlan.PathRole.SOURCE) {
                        accumulator.expectedTargetExemptions().add(path);
                        putExists(accumulator, path, false, false, "workspace operation source exists");
                    } else if (role == WorkspaceOperationPlan.PathRole.DELETED) {
                        accumulator.expectedTargetExemptions().add(path);
                        putAbsent(accumulator, path, "workspace operation target absent");
                    } else {
                        putExists(accumulator, path, false, true, "workspace operation target exists");
                    }
                }
            }
        }
    }

    private static Result verifyWorkspaceOperations(
            Path root,
            WorkspaceOperationAccumulator accumulator
    ) {
        if (accumulator == null || accumulator.expectations().isEmpty()) {
            return new Result(List.of(), List.of(), Set.of(), Set.of(), Set.of());
        }
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> mutationTargets = new LinkedHashSet<>();
        Set<String> expectedTargetAliases = new LinkedHashSet<>();
        for (WorkspacePathExpectation expectation : accumulator.expectations().values()) {
            verifyWorkspacePathExpectation(root, expectation, facts, problems);
            if (expectation.shouldExist() && expectation.mutationTarget()) {
                mutationTargets.add(expectation.path());
                String basename = basename(expectation.path());
                if (!basename.isBlank() && !basename.equals(expectation.path())) {
                    expectedTargetAliases.add(basename);
                }
            }
            if (!expectation.shouldExist()) {
                accumulator.expectedTargetExemptions().add(expectation.path());
            }
        }
        return new Result(
                facts,
                problems,
                mutationTargets,
                accumulator.expectedTargetExemptions(),
                expectedTargetAliases);
    }

    private static void putExists(
            WorkspaceOperationAccumulator accumulator,
            String path,
            boolean directory,
            boolean mutationTarget,
            String factPrefix
    ) {
        accumulator.expectations().put(
                path,
                new WorkspacePathExpectation(path, true, directory, mutationTarget, factPrefix));
    }

    private static void putAbsent(
            WorkspaceOperationAccumulator accumulator,
            String path,
            String factPrefix
    ) {
        accumulator.expectations().put(path, new WorkspacePathExpectation(path, false, false, false, factPrefix));
    }

    private static void verifyWorkspacePathExpectation(
            Path root,
            WorkspacePathExpectation expectation,
            List<String> facts,
            List<String> problems
    ) {
        Path target;
        try {
            target = root.resolve(expectation.path()).normalize();
        } catch (InvalidPathException e) {
            problems.add(expectation.path() + ": workspace operation path is invalid (" + e.getMessage() + ")");
            return;
        }
        if (!target.startsWith(root)) {
            problems.add(expectation.path() + ": workspace operation path resolves outside the workspace.");
            return;
        }

        if (expectation.shouldExist()) {
            if (!Files.exists(target)) {
                problems.add(expectation.factPrefix() + " failed: " + expectation.path() + " is missing.");
                return;
            }
            if (expectation.directory() && !Files.isDirectory(target)) {
                problems.add(expectation.factPrefix() + " failed: " + expectation.path()
                        + " is not a directory.");
                return;
            }
            facts.add(expectation.factPrefix() + ": " + expectation.path() + ".");
            return;
        }

        if (Files.exists(target)) {
            problems.add(expectation.factPrefix() + " failed: " + expectation.path() + " still exists.");
        } else {
            facts.add(expectation.factPrefix() + ": " + expectation.path() + ".");
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

    private static String basename(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    record Result(
            List<String> facts,
            List<String> problems,
            Set<String> mutationTargets,
            Set<String> expectedTargetExemptions,
            Set<String> expectedTargetAliases
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            mutationTargets = mutationTargets == null ? Set.of() : Set.copyOf(mutationTargets);
            expectedTargetExemptions = expectedTargetExemptions == null
                    ? Set.of()
                    : Set.copyOf(expectedTargetExemptions);
            expectedTargetAliases = expectedTargetAliases == null ? Set.of() : Set.copyOf(expectedTargetAliases);
        }
    }

    private record WorkspacePathExpectation(
            String path,
            boolean shouldExist,
            boolean directory,
            boolean mutationTarget,
            String factPrefix
    ) {}

    private record WorkspaceOperationAccumulator(
            Map<String, WorkspacePathExpectation> expectations,
            Set<String> expectedTargetExemptions
    ) {
        private WorkspaceOperationAccumulator() {
            this(new LinkedHashMap<>(), new LinkedHashSet<>());
        }
    }
}
