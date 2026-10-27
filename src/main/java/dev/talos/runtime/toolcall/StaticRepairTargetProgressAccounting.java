package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.repair.RepairPolicy;

import java.util.List;
import java.util.Set;

final class StaticRepairTargetProgressAccounting {

    private StaticRepairTargetProgressAccounting() {
    }

    static boolean hasStaticRepairContext(LoopState state) {
        return state != null && !RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages).isEmpty();
    }

    static List<String> remainingFullRewriteRepairTargets(LoopState state) {
        if (state == null) return List.of();
        Set<String> required = new java.util.LinkedHashSet<>(
                RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages));
        required.addAll(state.staticWebFullRewriteRequiredTargets);
        if (required.isEmpty()) return List.of();
        Set<String> successfullyMutated = new java.util.HashSet<>();
        List<String> mutatedPaths = new java.util.ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            String path = ToolCallSupport.normalizePath(outcome.pathHint());
            if (!path.isBlank()) successfullyMutated.add(path);
            mutatedPaths.addAll(ExpectedTargetProgressAccounting.mutatedPaths(outcome));
        }
        return required.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .filter(path -> !successfullyMutated.contains(path))
                .filter(path -> !ExpectedTargetProgressAccounting.mutatedUnderAnotherSpelling(
                        state.workspace, path, mutatedPaths))
                .sorted()
                .toList();
    }
}
