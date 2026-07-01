package dev.talos.runtime.verification;

import dev.talos.runtime.capability.ArtifactOperation;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Separates task-blocking static-web findings from contextual out-of-scope findings. */
final class StaticWebProblemScope {
    static final String CONTEXTUAL_PREFIX = "Contextual static-web finding outside this turn: ";

    private StaticWebProblemScope() {}

    static Result classify(
            TaskContract contract,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> candidateProblems
    ) {
        List<String> safeProblems = candidateProblems == null ? List.of() : candidateProblems;
        if (safeProblems.isEmpty() || !canScope(contract, profile, mutatedPaths)) {
            return new Result(safeProblems, List.of());
        }
        String target = onlyExpectedTarget(contract);
        TargetKind targetKind = TargetKind.from(target);
        if (targetKind == TargetKind.OTHER) {
            return new Result(safeProblems, List.of());
        }

        List<String> blocking = new ArrayList<>();
        List<String> contextual = new ArrayList<>();
        for (String problem : safeProblems) {
            if (blocksTarget(problem, target, targetKind)) {
                blocking.add(problem);
            } else {
                contextual.add(CONTEXTUAL_PREFIX + problem);
            }
        }
        return new Result(blocking, contextual);
    }

    static boolean isContextualFact(String fact) {
        return fact != null && fact.startsWith(CONTEXTUAL_PREFIX);
    }

    private static boolean canScope(TaskContract contract, CapabilityProfile profile, Set<String> mutatedPaths) {
        if (contract == null || profile == null || !profile.staticWeb()) return false;
        if (profile.operation() != ArtifactOperation.EDIT && profile.operation() != ArtifactOperation.REPAIR) {
            return false;
        }
        if (StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile)) return false;
        if (!profile.targetSurface().allowsFunctionalPartial()) return false;
        String target = onlyExpectedTarget(contract);
        if (target.isBlank() || !StaticWebCapabilityProfile.isSmallWebFile(target)) return false;
        return containsPath(mutatedPaths, target);
    }

    private static String onlyExpectedTarget(TaskContract contract) {
        if (contract == null || contract.expectedTargets().size() != 1) return "";
        for (String target : contract.expectedTargets()) {
            return normalize(target);
        }
        return "";
    }

    private static boolean containsPath(Set<String> paths, String target) {
        if (paths == null || paths.isEmpty() || target == null || target.isBlank()) return false;
        String normalizedTarget = normalize(target);
        for (String path : paths) {
            if (normalize(path).equalsIgnoreCase(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocksTarget(String problem, String target, TargetKind targetKind) {
        if (problem == null || problem.isBlank()) return false;
        String lower = problem.toLowerCase(Locale.ROOT);
        String normalizedTarget = normalize(target).toLowerCase(Locale.ROOT);
        if (!normalizedTarget.isBlank()
                && (lower.contains("`" + normalizedTarget + "`")
                || lower.startsWith(normalizedTarget + ":"))) {
            return true;
        }
        return switch (targetKind) {
            case CSS -> blocksCssTarget(lower);
            case JAVASCRIPT -> blocksJavaScriptTarget(lower);
            case OTHER -> true;
        };
    }

    private static boolean blocksCssTarget(String lower) {
        if (lower.contains("css") || lower.contains("stylesheet")) return true;
        if (lower.startsWith("html does not link css file")) return true;
        if (lower.startsWith("html references missing css file")) return true;
        return lower.startsWith("css references ")
                || lower.startsWith("css likely uses ");
    }

    private static boolean blocksJavaScriptTarget(String lower) {
        if (lower.contains("javascript") || lower.contains("script.js") || lower.contains("scripts.js")) return true;
        if (lower.startsWith("html does not link a javascript file")) return true;
        if (lower.startsWith("html does not link javascript file")) return true;
        if (lower.startsWith("html references missing javascript file")) return true;
        return lower.startsWith("javascript references ")
                || lower.contains("button click handler")
                || lower.contains("javascript behavior");
    }

    private static String normalize(String path) {
        return path == null ? "" : path.strip().replace('\\', '/');
    }

    record Result(
            List<String> blockingProblems,
            List<String> contextualFacts
    ) {
        Result {
            blockingProblems = blockingProblems == null ? List.of() : List.copyOf(blockingProblems);
            contextualFacts = contextualFacts == null ? List.of() : List.copyOf(contextualFacts);
        }
    }

    private enum TargetKind {
        CSS,
        JAVASCRIPT,
        OTHER;

        static TargetKind from(String target) {
            String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".css")) return CSS;
            if (lower.endsWith(".js") || lower.endsWith(".jsx")
                    || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
                return JAVASCRIPT;
            }
            return OTHER;
        }
    }
}
