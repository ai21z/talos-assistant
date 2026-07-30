package dev.talos.runtime;

import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
            path = TargetRef.normalizePath(path);
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("Roleful target role must be a named TargetRole");
            }
            try {
                TargetRole.valueOf(role);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown roleful target role: " + role,
                        e);
            }
            source = blankDefault(source, "");
            reason = blankDefault(reason, "");
            sourceText = sourceText == null ? "" : sourceText;
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                confidence = 0.0;
            }
        }

        static RolefulTarget from(TargetRef ref) {
            if (ref == null) {
                throw new IllegalArgumentException("TargetRef must not be null");
            }
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
        rolefulTargets = validatedRolefulTargets(rolefulTargets);
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
                normalizedPaths(contract.expectedTargets()).stream().toList(),
                normalizedPaths(contract.forbiddenTargets()).stream().toList(),
                initialPhase,
                initialPhase,
                nativeTools,
                promptTools,
                List.of(),
                contract.classificationReason(),
                rolefulTargetsFrom(intent, contract));
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

    private static boolean mutationTargetRole(String role) {
        return "MUST_MUTATE".equals(role)
                || "MUST_MUTATE_AFTER_SOURCE_EVIDENCE".equals(role)
                || "OUTPUT_DESTINATION".equals(role);
    }

    private static String expectedTargetRole(
            TaskContract contract,
            Set<String> activeSourceEvidence,
            String path
    ) {
        if (contract != null && !contract.mutationAllowed()) {
            return activeSourceEvidence.contains(path)
                    || !contract.verificationRequired()
                    ? "MUST_READ"
                    : "VERIFY_ONLY";
        }
        if (activeSourceEvidence.contains(path)) {
            return "MUST_MUTATE_AFTER_SOURCE_EVIDENCE";
        }
        return "MUST_MUTATE";
    }

    private static List<RolefulTarget> rolefulTargetsFrom(TaskIntent intent, TaskContract contract) {
        LinkedHashMap<String, RolefulTarget> out = new LinkedHashMap<>();
        Set<String> activeExpected =
                contract == null ? Set.of() : normalizedPaths(contract.expectedTargets());
        Set<String> activeForbidden =
                contract == null ? Set.of() : normalizedPaths(contract.forbiddenTargets());
        Set<String> activeSourceEvidence =
                contract == null ? Set.of() : normalizedPaths(contract.sourceEvidenceTargets());
        if (intent != null && !intent.targets().targets().isEmpty()) {
            for (TargetRef ref : intent.targets().targets()) {
                if (ref == null) continue;
                if (activeForbidden.contains(ref.path())) {
                    mergeRolefulTarget(
                            out,
                            ref.role() == TargetRole.FORBIDDEN
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(
                                            ref.path(),
                                            TargetRole.FORBIDDEN.name()));
                    continue;
                }
                if (activeExpected.contains(ref.path())) {
                    String role = expectedTargetRole(contract, activeSourceEvidence, ref.path());
                    mergeRolefulTarget(
                            out,
                            role.equals(ref.role().name())
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(ref.path(), role));
                    continue;
                }
                if (activeSourceEvidence.contains(ref.path())) {
                    mergeRolefulTarget(
                            out,
                            ref.role() == TargetRole.SOURCE_EVIDENCE
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(
                                            ref.path(),
                                            TargetRole.SOURCE_EVIDENCE.name()));
                    continue;
                }
                String role = ref.role().name();
                if (mutationTargetRole(role)) {
                    continue;
                }
                if ("FORBIDDEN".equals(role)) {
                    continue;
                }
                mergeRolefulTarget(out, RolefulTarget.from(ref));
            }
        }
        for (String expected : activeExpected.stream().sorted().toList()) {
            if (activeForbidden.contains(expected)) {
                continue;
            }
            String expectedRole =
                    expectedTargetRole(contract, activeSourceEvidence, expected);
            mergeRolefulTarget(out, activeContractProjection(expected, expectedRole));
        }
        for (String source : activeSourceEvidence.stream().sorted().toList()) {
            if (activeExpected.contains(source) || activeForbidden.contains(source)) {
                continue;
            }
            mergeRolefulTarget(out, activeContractProjection(source, "SOURCE_EVIDENCE"));
        }
        for (String forbidden : activeForbidden.stream().sorted().toList()) {
            mergeRolefulTarget(out, new RolefulTarget(
                    forbidden,
                    "FORBIDDEN",
                    "RUNTIME_DEFAULT",
                    "active-contract-projection",
                    "",
                    1.0));
        }
        return out.values().stream()
                .sorted(Comparator
                        .comparing(RolefulTarget::path)
                        .thenComparing(RolefulTarget::role))
                .toList();
    }

    private static void mergeRolefulTarget(
            LinkedHashMap<String, RolefulTarget> targets,
            RolefulTarget candidate
    ) {
        RolefulTarget existing = targets.get(candidate.path());
        if (existing == null) {
            targets.put(candidate.path(), candidate);
            return;
        }
        TargetRole existingRole = TargetRole.valueOf(existing.role());
        TargetRole candidateRole = TargetRole.valueOf(candidate.role());
        if (candidateRole.strongerThan(existingRole)) {
            targets.put(candidate.path(), candidate);
        }
    }

    private static RolefulTarget activeContractProjection(String path, String role) {
        return new RolefulTarget(
                path,
                role,
                "RUNTIME_DEFAULT",
                "active-contract-projection",
                "",
                1.0);
    }

    private static Set<String> normalizedPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        TreeSet<String> sorted = new TreeSet<>();
        for (String path : paths) {
            sorted.add(TargetRef.normalizePath(path));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static List<RolefulTarget> validatedRolefulTargets(List<RolefulTarget> targets) {
        if (targets == null || targets.isEmpty()) return List.of();
        LinkedHashMap<String, RolefulTarget> byPath = new LinkedHashMap<>();
        for (RolefulTarget target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("Roleful target entry must not be null");
            }
            RolefulTarget prior = byPath.putIfAbsent(target.path(), target);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Duplicate or conflicting roleful target path: " + target.path());
            }
        }
        return List.copyOf(byPath.values());
    }
}
