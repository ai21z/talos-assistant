package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class ExpectedTargetProgressAccounting {

    private ExpectedTargetProgressAccounting() {}

    static List<String> remainingExpectedMutationTargets(LoopState state) {
        if (state == null || state.messages == null) return List.of();
        TaskContract contract = WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(state.messages),
                state.workspace);
        if (contract == null || !contract.mutationAllowed()) {
            return List.of();
        }
        if (!RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty()
                || !state.staticWebFullRewriteRequiredTargets.isEmpty()) {
            return List.of();
        }
        String latestUserRequest = ToolCallSupport.latestUserRequestIn(state.messages);
        Set<String> expectedTargets = contract.expectedTargets().isEmpty()
                ? TaskContractResolver.extractExpectedTargets(latestUserRequest)
                : contract.expectedTargets();
        if (expectedTargets.isEmpty()) {
            return List.of();
        }
        Set<String> satisfiedTargets = new java.util.HashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            addSatisfiedExpectedTargetKeys(satisfiedTargets, outcome);
        }
        expectedTargets = hardExpectedTargetsForProgress(
                expectedTargets,
                contract,
                latestUserRequest,
                state.workspace,
                satisfiedTargets);
        java.util.LinkedHashMap<String, String> expectedDisplayByKey = new java.util.LinkedHashMap<>();
        for (String target : expectedTargets) {
            String display = ToolCallSupport.normalizePath(target);
            String key = normalizeExpectedTargetKey(display);
            if (!key.isBlank()) {
                expectedDisplayByKey.putIfAbsent(key, display);
            }
        }
        return expectedDisplayByKey.entrySet().stream()
                .filter(entry -> !satisfiedTargets.contains(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .sorted()
                .toList();
    }

    static String displayExpectedTargetForKey(List<String> targets, String key) {
        if (targets == null || targets.isEmpty() || key == null || key.isBlank()) return "";
        for (String target : targets) {
            String display = ToolCallSupport.normalizePath(target);
            if (!display.isBlank() && key.equals(normalizeExpectedTargetKey(display))) {
                return display;
            }
        }
        return "";
    }

    private static Set<String> hardExpectedTargetsForProgress(
            Set<String> expectedTargets,
            TaskContract contract,
            String latestUserRequest,
            Path workspace,
            Set<String> satisfiedTargets
    ) {
        if (!shouldTreatAbsentInferredSatellitesAsOptional(
                expectedTargets,
                contract,
                latestUserRequest,
                workspace,
                satisfiedTargets)) {
            return expectedTargets;
        }

        LinkedHashSet<String> hardTargets = new LinkedHashSet<>();
        boolean changed = false;
        for (String target : expectedTargets) {
            if (isAbsentUnnamedConventionalStaticWebSatellite(workspace, latestUserRequest, target)) {
                changed = true;
                continue;
            }
            hardTargets.add(target);
        }
        return changed ? hardTargets : expectedTargets;
    }

    private static boolean shouldTreatAbsentInferredSatellitesAsOptional(
            Set<String> expectedTargets,
            TaskContract contract,
            String latestUserRequest,
            Path workspace,
            Set<String> satisfiedTargets
    ) {
        if (contract == null || contract.type() == TaskType.FILE_CREATE) return false;
        if (expectedTargets == null || expectedTargets.isEmpty()) return false;
        if (workspace == null || latestUserRequest == null || latestUserRequest.isBlank()) return false;
        if (satisfiedTargets == null || !satisfiedTargets.contains("index.html")) return false;
        if (!containsTarget(expectedTargets, "index.html")) return false;
        if (!containsAnyConventionalStaticWebSatellite(expectedTargets)) return false;
        if (!looksLikeExistingStaticWebRedesign(latestUserRequest)) return false;
        return existingSingleFileStaticWebPage(workspace);
    }

    private static boolean isAbsentUnnamedConventionalStaticWebSatellite(
            Path workspace,
            String latestUserRequest,
            String target
    ) {
        return isConventionalStaticWebSatellite(target)
                && !requestMentionsTarget(latestUserRequest, target)
                && !targetExists(workspace, target);
    }

    private static boolean containsAnyConventionalStaticWebSatellite(Set<String> targets) {
        if (targets == null || targets.isEmpty()) return false;
        for (String target : targets) {
            if (isConventionalStaticWebSatellite(target)) return true;
        }
        return false;
    }

    private static boolean isConventionalStaticWebSatellite(String target) {
        String normalized = normalizeExpectedTargetKey(target);
        return normalized.equals("style.css")
                || normalized.equals("styles.css")
                || normalized.equals("script.js")
                || normalized.equals("scripts.js");
    }

    private static boolean requestMentionsTarget(String latestUserRequest, String target) {
        String request = latestUserRequest == null ? "" : latestUserRequest.toLowerCase(Locale.ROOT);
        String normalizedTarget = normalizeExpectedTargetKey(target);
        return !normalizedTarget.isBlank() && request.contains(normalizedTarget);
    }

    private static boolean targetExists(Path workspace, String target) {
        if (workspace == null || target == null || target.isBlank()) return false;
        try {
            Path root = workspace.toAbsolutePath().normalize();
            Path path = workspace.resolve(ToolCallSupport.normalizePath(target)).toAbsolutePath().normalize();
            return path.startsWith(root) && Files.exists(path);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean existingSingleFileStaticWebPage(Path workspace) {
        if (workspace == null) return false;
        try {
            if (!Files.isRegularFile(workspace.resolve("index.html"))) return false;
            try (Stream<Path> entries = Files.list(workspace)) {
                return entries
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
                        .map(name -> name.toLowerCase(Locale.ROOT))
                        .noneMatch(name -> name.endsWith(".css") || name.endsWith(".js"));
            }
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean looksLikeExistingStaticWebRedesign(String latestUserRequest) {
        if (latestUserRequest == null || latestUserRequest.isBlank()) return false;
        String lower = latestUserRequest.toLowerCase(Locale.ROOT);
        boolean existingSurface = lower.contains("page")
                || lower.contains("site")
                || lower.contains("website")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("frontend")
                || lower.contains("index.html");
        boolean redesign = lower.contains("redesign")
                || lower.contains("rewrite")
                || lower.contains("restyle")
                || lower.contains("improve")
                || lower.contains("better")
                || lower.contains("polish")
                || lower.contains("fix the files")
                || lower.contains("fix it");
        boolean staticWebAssets = (lower.contains("style")
                || lower.contains("styling")
                || lower.contains("css")
                || lower.contains("design")
                || lower.contains("modern"))
                && (lower.contains("javascript")
                || lower.contains("script")
                || lower.contains("interaction")
                || lower.contains("interactive"));
        return (existingSurface && redesign) || (redesign && staticWebAssets);
    }

    static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static boolean containsTarget(Set<String> targets, String expected) {
        if (targets == null || targets.isEmpty()) return false;
        String normalizedExpected = normalizeExpectedTargetKey(expected);
        for (String target : targets) {
            if (normalizeExpectedTargetKey(target).equals(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private static void addSatisfiedExpectedTargetKeys(
            Set<String> satisfiedTargets,
            ToolCallLoop.ToolOutcome outcome
    ) {
        if (satisfiedTargets == null || outcome == null) return;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan != null && !plan.pathEffects().isEmpty()) {
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                addExpectedTargetPathKeys(satisfiedTargets, effect.path());
            }
            return;
        }
        addExpectedTargetPathKeys(satisfiedTargets, outcome.pathHint());
    }

    private static void addExpectedTargetPathKeys(Set<String> satisfiedTargets, String path) {
        String normalized = normalizeExpectedTargetKey(path);
        if (normalized.isBlank()) return;
        satisfiedTargets.add(normalized);
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            satisfiedTargets.add(normalized.substring(slash + 1));
        }
    }
}
