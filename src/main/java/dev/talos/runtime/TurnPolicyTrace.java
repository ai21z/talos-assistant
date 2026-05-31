package dev.talos.runtime;

import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;

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
        List<String> blocks,
        String classificationReason,
        List<RolefulTarget> rolefulTargets
) {
    public record RolefulTarget(
            String path,
            String role,
            String source,
            String reason,
            String sourceText,
            double confidence
    ) {
        public RolefulTarget {
            path = blankDefault(path, "");
            role = blankDefault(role, "");
            source = blankDefault(source, "");
            reason = blankDefault(reason, "");
            sourceText = sourceText == null ? "" : sourceText;
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                confidence = 0.0;
            }
        }

        static RolefulTarget from(TargetRef ref) {
            if (ref == null) return new RolefulTarget("", "", "", "", "", 0.0);
            var derivation = ref.derivation();
            return new RolefulTarget(
                    ref.path(),
                    ref.role().name(),
                    derivation.source().name(),
                    derivation.reason(),
                    derivation.sourceText(),
                    derivation.confidence());
        }
    }

    public TurnPolicyTrace(
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
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                "",
                List.of());
    }

    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                List.of());
    }

    public TurnPolicyTrace {
        taskType = blankDefault(taskType, "UNKNOWN");
        expectedTargets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        forbiddenTargets = forbiddenTargets == null ? List.of() : List.copyOf(forbiddenTargets);
        initialPhase = blankDefault(initialPhase, "unknown");
        finalPhase = blankDefault(finalPhase, initialPhase);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        classificationReason = blankDefault(classificationReason, "");
        rolefulTargets = rolefulTargets == null ? List.of() : List.copyOf(rolefulTargets);
    }

    public static TurnPolicyTrace empty() {
        return new TurnPolicyTrace("UNKNOWN", false, false,
                List.of(), List.of(), "unknown", "unknown",
                List.of(), List.of(), List.of(), "", List.of());
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
        TaskIntent intent = TaskContractResolver.intentFromUserRequest(contract.originalUserRequest());
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
                List.of(),
                contract.classificationReason(),
                rolefulTargetsFrom(intent));
    }

    public TurnPolicyTrace withInitialPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, phase, finalPhase, nativeTools, promptTools, blocks,
                classificationReason, rolefulTargets);
    }

    public TurnPolicyTrace withFinalPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, phase, nativeTools, promptTools, blocks,
                classificationReason, rolefulTargets);
    }

    public TurnPolicyTrace withNativeTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, tools, promptTools, blocks,
                classificationReason, rolefulTargets);
    }

    public TurnPolicyTrace withPromptTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, nativeTools, tools, blocks,
                classificationReason, rolefulTargets);
    }

    public TurnPolicyTrace withBlocks(List<String> newBlocks) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase,
                nativeTools, promptTools, newBlocks, classificationReason, rolefulTargets);
    }

    public boolean hasPolicyData() {
        return !"UNKNOWN".equals(taskType)
                || !"unknown".equals(initialPhase)
                || !nativeTools.isEmpty()
                || !promptTools.isEmpty()
                || !blocks.isEmpty()
                || !classificationReason.isBlank();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<RolefulTarget> rolefulTargetsFrom(TaskIntent intent) {
        if (intent == null || intent.targets().targets().isEmpty()) return List.of();
        return intent.targets().targets().stream()
                .map(RolefulTarget::from)
                .toList();
    }
}
