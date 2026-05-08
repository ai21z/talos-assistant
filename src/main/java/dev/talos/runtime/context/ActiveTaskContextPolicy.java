package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ActiveTaskContextPolicy {

    private static final Set<String> DEICTIC_APPLY_PHRASES = Set.of(
            "make those changes",
            "apply those changes",
            "go ahead and apply",
            "go ahead and apply those changes",
            "apply it",
            "make the changes",
            "do it now",
            "yes, apply it"
    );
    private static final Pattern DEICTIC_PROPOSAL_APPLY = Pattern.compile(
            "^apply\\s+(?:(?:that|the)\\s+)?(?:[a-z0-9._/-]+\\s+)?proposal(?:\\s+now)?$"
    );

    private static final Set<ActiveTaskContext.Kind> CONSUMABLE_KINDS = Set.of(
            ActiveTaskContext.Kind.PROPOSED_CHANGES,
            ActiveTaskContext.Kind.VERIFIER_FINDINGS,
            ActiveTaskContext.Kind.DENIED_MUTATION,
            ActiveTaskContext.Kind.PARTIAL_MUTATION
    );

    private static final Set<String> SUPPRESSION_PHRASES = Set.of(
            "don't inspect",
            "do not inspect",
            "don't read",
            "do not read",
            "no workspace",
            "only chatting",
            "just chatting",
            "privacy"
    );

    private ActiveTaskContextPolicy() {}

    public record Decision(
            TaskContract taskContract,
            ActiveTaskContext planContext,
            ArtifactGoal artifactGoal,
            ActiveTaskContext memoryContext,
            boolean consumed) {

        public Decision {
            taskContract = taskContract == null ? TaskContract.unknown("") : taskContract;
            planContext = planContext == null ? ActiveTaskContext.none() : planContext;
            artifactGoal = artifactGoal == null ? ArtifactGoal.none() : artifactGoal;
            memoryContext = memoryContext == null ? planContext : memoryContext;
        }
    }

    public static Decision evaluate(
            String userRequest,
            TaskContract rawContract,
            ActiveTaskContext savedContext,
            ArtifactGoal savedGoal,
            int currentUserTurnNumber) {
        TaskContract current = rawContract == null ? TaskContract.unknown(userRequest) : rawContract;

        if (savedContext == null || savedContext.state() != ActiveTaskContext.State.ACTIVE) {
            return new Decision(current, ActiveTaskContext.none(), ArtifactGoal.none(), ActiveTaskContext.none(), false);
        }

        if (suppressesContext(userRequest, current)) {
            if (!savedContext.activeAt(currentUserTurnNumber)) {
                return new Decision(
                        current,
                        ActiveTaskContext.none(),
                        ArtifactGoal.none(),
                        ActiveTaskContext.none(),
                        false);
            }
            return new Decision(
                    current,
                    savedContext.suppressed("current request does not require workspace context"),
                    ArtifactGoal.none(),
                    savedContext,
                    false);
        }

        if (!savedContext.activeAt(currentUserTurnNumber)) {
            return new Decision(
                    current,
                    savedContext.expired("expired after active-context turn limit"),
                    ArtifactGoal.none(),
                    ActiveTaskContext.none(),
                    false);
        }

        if (explicitTargetsDifferFromSavedTargets(current, savedContext.targets())) {
            return new Decision(
                    current,
                    savedContext.cleared("current request names a different explicit target"),
                    ArtifactGoal.none(),
                    ActiveTaskContext.none(),
                    false);
        }

        if (isNarrowDeicticApply(userRequest) && savedContext.hasTargets() && isConsumable(savedContext.kind())) {
            return new Decision(
                    contextualizedContract(userRequest, savedContext),
                    savedContext,
                    savedGoal,
                    savedContext,
                    true);
        }

        return new Decision(current, ActiveTaskContext.none(), ArtifactGoal.none(), savedContext, false);
    }

    private static boolean suppressesContext(String userRequest, TaskContract contract) {
        if (contract != null && contract.type() == TaskType.SMALL_TALK) return true;
        String lower = normalized(userRequest);
        if (lower.startsWith("/")) return true;
        for (String phrase : SUPPRESSION_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        return false;
    }

    private static boolean explicitTargetsDifferFromSavedTargets(TaskContract contract, List<String> savedTargets) {
        if (contract == null || contract.expectedTargets().isEmpty()) return false;
        Set<String> saved = normalizedTargets(savedTargets);
        Set<String> explicit = new LinkedHashSet<>();
        for (String target : contract.expectedTargets()) {
            String value = normalizedTarget(target);
            if (!value.isBlank()) explicit.add(value);
        }
        return !explicit.equals(saved);
    }

    private static boolean isNarrowDeicticApply(String userRequest) {
        String lower = normalized(userRequest).replaceAll("[.!?]+$", "");
        return DEICTIC_APPLY_PHRASES.contains(lower)
                || DEICTIC_PROPOSAL_APPLY.matcher(lower).matches();
    }

    private static boolean isConsumable(ActiveTaskContext.Kind kind) {
        return CONSUMABLE_KINDS.contains(kind);
    }

    private static TaskContract contextualizedContract(String userRequest, ActiveTaskContext context) {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                new LinkedHashSet<>(context.targets()),
                Set.of(),
                contextualizedRequest(userRequest, context));
    }

    private static String contextualizedRequest(String userRequest, ActiveTaskContext context) {
        StringBuilder out = new StringBuilder();
        out.append("Active task context: ");
        String summary = contextSummary(context);
        if (!summary.isBlank()) {
            out.append(summary);
        } else {
            out.append(context.renderForPlan());
        }
        String followUp = userRequest == null ? "" : userRequest.strip();
        if (!followUp.isBlank()) {
            out.append("\n\nFollow-up: ").append(followUp);
        }
        return out.toString();
    }

    private static String contextSummary(ActiveTaskContext context) {
        if (!context.proposalSummary().isBlank()) return context.proposalSummary();
        if (!context.verifierFindings().isEmpty()) return String.join("; ", context.verifierFindings());
        if (!context.blockedReason().isBlank()) return context.blockedReason();
        if (!context.previousOutcomeStatus().isBlank()) return context.previousOutcomeStatus();
        return "";
    }

    private static Set<String> normalizedTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String target : targets) {
            String value = normalizedTarget(target);
            if (!value.isBlank()) normalized.add(value);
        }
        return normalized;
    }

    private static String normalizedTarget(String target) {
        if (target == null) return "";
        String normalized = target.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalized(String userRequest) {
        return userRequest == null
                ? ""
                : userRequest.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
