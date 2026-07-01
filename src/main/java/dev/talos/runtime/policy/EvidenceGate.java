package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.core.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure evidence-obligation policy for current-turn handoff decisions.
 *
 * <p>This class decides whether an existing turn plan requires a read-evidence
 * handoff and which targets can be read. It does not call the model or execute
 * tools; callers own orchestration.
 */
public final class EvidenceGate {
    private EvidenceGate() {}

    public static EvidenceObligation selectObligation(CurrentTurnPlan plan, Path workspace) {
        return selectObligation(plan, workspace, null);
    }

    public static EvidenceObligation selectObligation(CurrentTurnPlan plan, Path workspace, Config cfg) {
        if (plan == null) return EvidenceObligation.NONE;
        TaskContract contract = plan.taskContract();
        if (contract == null) return EvidenceObligation.NONE;
        EvidenceObligation recorded = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        EvidenceObligation derived = EvidenceObligationPolicy.derive(
                contract,
                phase(plan),
                workspace,
                cfg);
        return derived == EvidenceObligation.NONE ? recorded : derived;
    }

    public static boolean requiresReadEvidenceHandoff(EvidenceObligation obligation) {
        return obligation == EvidenceObligation.READ_TARGET_REQUIRED
                || obligation == EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED
                || obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED
                || obligation == EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED;
    }

    public static List<String> handoffTargets(
            TaskContract contract,
            EvidenceObligation obligation,
            Path workspace
    ) {
        return handoffTargets(contract, obligation, workspace, null);
    }

    public static List<String> handoffTargets(
            TaskContract contract,
            EvidenceObligation obligation,
            Path workspace,
            Config cfg
    ) {
        List<String> evidenceTargets = evidenceTargets(contract);
        if (contract == null || workspace == null || evidenceTargets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String target : evidenceTargets) {
            if (target == null || target.isBlank()) continue;
            boolean protectedTarget = ProtectedPathPolicy.classify(workspace, target).protectedPath();
            if (obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED) {
                targets.add(target);
            } else if (obligation == EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED
                    && isUnsupportedExpectedTarget(target, cfg)) {
                targets.add(target);
            } else if ((obligation == EvidenceObligation.READ_TARGET_REQUIRED
                    || obligation == EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED) && !protectedTarget) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    /**
     * T900: read-evidence targets minus inferred conventional static-web satellites
     * ({@code style.css} / {@code script.js}) that do NOT exist on disk and were NOT
     * named by the user. You cannot read a file that is not there, so requiring it as
     * read evidence falsely blocks a read-only turn (observed live: plan-mode
     * "redesign this page" on a single-file index.html demanded reading nonexistent
     * style.css/script.js).
     *
     * <p>This is deliberately narrow and trust-preserving: the conventional triplet is
     * only ever projected when the user named no files, so a satellite that is present
     * on disk OR explicitly named is always kept (still required). Non-satellite
     * targets are never touched. Create-from-scratch is unaffected because it is a
     * mutation contract, not a read-evidence one.
     */
    public static Set<String> withoutAbsentInferredStaticWebSatellites(
            TaskContract contract, Set<String> targets, Path workspace) {
        if (targets == null || targets.isEmpty() || workspace == null || contract == null) {
            return targets == null ? Set.of() : targets;
        }
        String lowerRequest = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String target : targets) {
            if (!isAbsentInferredStaticWebSatellite(target, lowerRequest, workspace)) {
                kept.add(target);
            }
        }
        return Set.copyOf(kept);
    }

    private static boolean isAbsentInferredStaticWebSatellite(
            String target, String lowerRequest, Path workspace) {
        if (target == null || target.isBlank()) return false;
        String normalized = target.replace('\\', '/').toLowerCase(Locale.ROOT);
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!basename.equals("style.css") && !basename.equals("script.js")) {
            return false; // only the conventional inferred satellites are eligible
        }
        if (containsWord(lowerRequest, basename)) {
            return false; // user named it explicitly -> keep requiring it
        }
        return !targetExistsOnDisk(workspace, target); // drop only when absent on disk
    }

    private static boolean targetExistsOnDisk(Path workspace, String target) {
        try {
            Path base = workspace.toAbsolutePath().normalize();
            Path resolved = base.resolve(target).toAbsolutePath().normalize();
            return resolved.startsWith(base) && Files.exists(resolved);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean hasOnlyUnsupportedExpectedTargets(TaskContract contract) {
        return hasOnlyUnsupportedExpectedTargets(contract, null);
    }

    public static boolean hasOnlyUnsupportedExpectedTargets(TaskContract contract, Config cfg) {
        List<String> evidenceTargets = evidenceTargets(contract);
        if (contract == null || evidenceTargets.isEmpty()) return false;
        boolean sawTarget = false;
        for (String target : evidenceTargets) {
            if (target == null || target.isBlank()) continue;
            sawTarget = true;
            if (!isUnsupportedExpectedTarget(target, cfg)) return false;
        }
        return sawTarget;
    }

    public static List<String> protectedExpectedTargets(TaskContract contract, Path workspace) {
        List<String> evidenceTargets = evidenceTargets(contract);
        if (contract == null || workspace == null || evidenceTargets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String target : evidenceTargets) {
            if (target == null || target.isBlank()) continue;
            if (ProtectedPathPolicy.classify(workspace, target).protectedPath()) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    public static boolean hasExplicitProtectedReadIntent(TaskContract contract, List<String> targets) {
        if (contract == null || targets == null || targets.isEmpty()) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lowerRequest = request.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String target : targets) {
            if (targetHasExplicitReadIntent(lowerRequest, target)) {
                return true;
            }
        }
        return false;
    }

    static List<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return List.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) {
            return List.copyOf(contract.sourceEvidenceTargets());
        }
        return List.copyOf(contract.expectedTargets());
    }

    static boolean isUnsupportedExpectedTarget(String target) {
        return isUnsupportedExpectedTarget(target, null);
    }

    static boolean isUnsupportedExpectedTarget(String target, Config cfg) {
        if (target == null || target.isBlank()) return false;
        try {
            return EvidenceObligationPolicy.requiresUnsupportedCapabilityCheck(Path.of(target), cfg);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ExecutionPhase phase(CurrentTurnPlan plan) {
        return plan.phaseInitial() == null ? ExecutionPhase.INSPECT : plan.phaseInitial();
    }

    private static boolean targetHasExplicitReadIntent(String lowerRequest, String target) {
        if (lowerRequest == null || lowerRequest.isBlank() || target == null || target.isBlank()) {
            return false;
        }
        String normalizedTarget = target.toLowerCase(Locale.ROOT).replace('\\', '/');
        int from = 0;
        while (from < lowerRequest.length()) {
            int index = lowerRequest.indexOf(normalizedTarget, from);
            if (index < 0) return false;
            int beforeStart = Math.max(0, index - 80);
            int afterEnd = Math.min(lowerRequest.length(), index + normalizedTarget.length() + 80);
            String before = lowerRequest.substring(beforeStart, index);
            String after = lowerRequest.substring(index + normalizedTarget.length(), afterEnd);
            if (!hasLocalTargetNegation(before)
                    && (hasReadIntentMarker(before) || hasReadIntentMarker(after))) {
                return true;
            }
            from = index + normalizedTarget.length();
        }
        return false;
    }

    private static boolean hasLocalTargetNegation(String value) {
        if (value == null || value.isBlank()) return false;
        return value.contains("do not want")
                || value.contains("do not need")
                || value.contains("don't want")
                || value.contains("don't need")
                || value.contains("dont want")
                || value.contains("dont need")
                || value.contains("not want")
                || value.contains("not the")
                || value.contains("without ")
                || value.contains("exclude")
                || value.contains("skip")
                || value.contains("avoid")
                || value.contains("not ");
    }

    private static boolean hasReadIntentMarker(String value) {
        if (value == null || value.isBlank()) return false;
        return containsWord(value, "read")
                || containsWord(value, "open")
                || containsWord(value, "inspect")
                || containsWord(value, "show")
                || containsWord(value, "display")
                || containsWord(value, "summarize")
                || containsWord(value, "print")
                || containsWord(value, "cat")
                || value.contains("tell me")
                || value.contains("value inside")
                || value.contains("what does")
                || value.contains("what is in")
                || value.contains("content")
                || value.contains("contents");
    }

    private static boolean containsWord(String value, String word) {
        if (value == null || word == null || word.isBlank()) return false;
        int from = 0;
        while (from < value.length()) {
            int index = value.indexOf(word, from);
            if (index < 0) return false;
            int before = index - 1;
            int after = index + word.length();
            boolean leftBoundary = before < 0 || !isWordChar(value.charAt(before));
            boolean rightBoundary = after >= value.length() || !isWordChar(value.charAt(after));
            if (leftBoundary && rightBoundary) return true;
            from = index + word.length();
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }
}
