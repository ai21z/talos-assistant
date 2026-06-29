package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies mode-level capability ceilings before phase and native-tool planning. */
public final class CapabilityPosturePolicy {
    private static final Pattern READ_ONLY_CREATE_OUTPUT_SPAN = Pattern.compile(
            "(?i)\\b(?:plan\\s+(?:how\\s+)?(?:you\\s+would\\s+)?(?:to\\s+)?"
                    + "|how\\s+(?:would\\s+you\\s+|to\\s+)?)?"
                    + "(?:add|create|write|save|generate|make|build)\\b\\s+(.{0,220})");

    private CapabilityPosturePolicy() {}

    public record EffectiveTurn(
            TaskContract taskContract,
            ExecutionPhase phase,
            boolean forceNativeSurfaceRecompute
    ) {}

    public static EffectiveTurn apply(CapabilityPosture posture, TaskContract contract) {
        CapabilityPosture safePosture = posture == null ? CapabilityPosture.AGENT : posture;
        if (!safePosture.readOnly()) {
            return new EffectiveTurn(contract, CurrentTurnPlan.defaultPhaseFor(contract), false);
        }
        return new EffectiveTurn(readOnly(contract), ExecutionPhase.INSPECT, true);
    }

    private static TaskContract readOnly(TaskContract contract) {
        if (contract == null) return TaskContract.unknown("");
        Set<String> expectedTargets = readOnlyExpectedTargets(contract);
        boolean suppressedMutationOutputs = !contract.expectedTargets().isEmpty()
                && expectedTargets.isEmpty()
                && readOnlyCreateOutputContext(contract);
        return new TaskContract(
                contract.type(),
                contract.mutationRequested(),
                false,
                suppressedMutationOutputs ? false : contract.verificationRequired(),
                expectedTargets,
                contract.sourceEvidenceTargets(),
                contract.forbiddenTargets(),
                contract.originalUserRequest(),
                readOnlyReason(contract.classificationReason()),
                contract.staticWebRequirements());
    }

    private static Set<String> readOnlyExpectedTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return Set.of();
        if (contract.type() == TaskType.FILE_CREATE) return Set.of();
        Set<String> outputTargets = readOnlyCreateOutputTargets(contract);
        if (outputTargets.isEmpty()) return contract.expectedTargets();
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        Set<String> normalizedOutputs = normalizedTargets(outputTargets);
        for (String target : contract.expectedTargets()) {
            if (!normalizedOutputs.contains(normalize(target))) {
                keep.add(target);
            }
        }
        return Set.copyOf(keep);
    }

    private static boolean readOnlyCreateOutputContext(TaskContract contract) {
        return contract != null
                && (contract.type() == TaskType.FILE_CREATE
                || !readOnlyCreateOutputTargets(contract).isEmpty());
    }

    private static Set<String> readOnlyCreateOutputTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return Set.of();
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher matcher = READ_ONLY_CREATE_OUTPUT_SPAN.matcher(request);
        while (matcher.find()) {
            String span = outputFragment(matcher.group(1));
            Set<String> spanTargets = TaskContractResolver.extractExpectedTargets(span);
            if (spanTargets.isEmpty()) continue;
            Set<String> normalizedSpanTargets = normalizedTargets(spanTargets);
            for (String target : contract.expectedTargets()) {
                if (normalizedSpanTargets.contains(normalize(target))) {
                    out.add(target);
                }
            }
        }
        return Set.copyOf(out);
    }

    private static String outputFragment(String span) {
        if (span == null || span.isBlank()) return "";
        String fragment = span.stripLeading();
        String lower = fragment.toLowerCase(Locale.ROOT);
        int end = fragment.length();
        for (String marker : Set.of(" from ", " based on ", " using ", " according to ", ";")) {
            int index = lower.indexOf(marker);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        String[] sentence = fragment.substring(0, end).split("(?<=[.!?])\\s+", 2);
        return sentence.length == 0 ? fragment.substring(0, end) : sentence[0];
    }

    private static Set<String> normalizedTargets(Set<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String target : targets) {
            String normalized = normalize(target);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return Set.copyOf(out);
    }

    private static String normalize(String target) {
        return target == null ? "" : target.replace('\\', '/').strip().toLowerCase(Locale.ROOT);
    }

    private static String readOnlyReason(String reason) {
        if (reason == null || reason.isBlank()) return "capability-posture-read-only";
        if (reason.contains("capability-posture-read-only")) return reason;
        return reason + "; capability-posture-read-only";
    }
}
