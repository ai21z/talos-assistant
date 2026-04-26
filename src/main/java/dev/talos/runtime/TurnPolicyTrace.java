package dev.talos.runtime;

import dev.talos.runtime.task.TaskContract;

import java.util.List;

/**
 * Structured current-turn policy metadata persisted with the turn audit.
 *
 * <p>This is intentionally compact: it explains the task contract, phase, and
 * tool surface that shaped the turn without storing raw prompts or large traces.
 */
public record TurnPolicyTrace(
        String taskType,
        boolean mutationAllowed,
        boolean verificationRequired,
        List<String> expectedTargets,
        List<String> forbiddenTargets,
        String initialPhase,
        String finalPhase,
        List<String> nativeTools,
        List<String> promptTools,
        List<String> blocks
) {
    public TurnPolicyTrace {
        taskType = blankDefault(taskType, "UNKNOWN");
        expectedTargets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        forbiddenTargets = forbiddenTargets == null ? List.of() : List.copyOf(forbiddenTargets);
        initialPhase = blankDefault(initialPhase, "unknown");
        finalPhase = blankDefault(finalPhase, initialPhase);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static TurnPolicyTrace empty() {
        return new TurnPolicyTrace("UNKNOWN", false, false,
                List.of(), List.of(), "unknown", "unknown",
                List.of(), List.of(), List.of());
    }

    public static TurnPolicyTrace from(
            TaskContract contract,
            String initialPhase,
            List<String> nativeTools,
            List<String> promptTools
    ) {
        if (contract == null) return empty().withInitialPhase(initialPhase)
                .withNativeTools(nativeTools)
                .withPromptTools(promptTools);
        return new TurnPolicyTrace(
                contract.type().name(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                contract.expectedTargets().stream().sorted().toList(),
                contract.forbiddenTargets().stream().sorted().toList(),
                initialPhase,
                initialPhase,
                nativeTools,
                promptTools,
                List.of());
    }

    public TurnPolicyTrace withInitialPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, phase, finalPhase, nativeTools, promptTools, blocks);
    }

    public TurnPolicyTrace withFinalPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, phase, nativeTools, promptTools, blocks);
    }

    public TurnPolicyTrace withNativeTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, tools, promptTools, blocks);
    }

    public TurnPolicyTrace withPromptTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, nativeTools, tools, blocks);
    }

    public TurnPolicyTrace withBlocks(List<String> newBlocks) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase,
                nativeTools, promptTools, newBlocks);
    }

    public boolean hasPolicyData() {
        return !"UNKNOWN".equals(taskType)
                || !"unknown".equals(initialPhase)
                || !nativeTools.isEmpty()
                || !promptTools.isEmpty()
                || !blocks.isEmpty();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
