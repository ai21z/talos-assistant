package dev.talos.runtime.turn;

import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.core.Config;

import java.nio.file.Path;
import java.util.List;

/** Immutable runtime-owned current-turn facts captured before retries can drift. */
public record CurrentTurnPlan(
        TaskContract taskContract,
        String originalUserRequest,
        ExecutionPhase phaseInitial,
        ExecutionPhase phaseFinal,
        ActionObligation actionObligation,
        List<TaskExpectation> taskExpectations,
        List<String> nativeTools,
        List<String> promptTools,
        List<String> blockedTools,
        String evidenceObligation,
        String outputObligation,
        String activeTaskContext,
        String artifactGoal,
        String verifierProfile
) {
    public static final String NONE_OR_NOT_DERIVED = "NONE_OR_NOT_DERIVED";
    public static final String NOT_DERIVED = "NOT_DERIVED";

    public CurrentTurnPlan {
        taskContract = taskContract == null ? TaskContract.unknown("") : taskContract;
        originalUserRequest = originalUserRequest == null
                ? taskContract.originalUserRequest()
                : originalUserRequest;
        phaseInitial = phaseInitial == null
                ? defaultPhase(taskContract)
                : phaseInitial;
        phaseFinal = phaseFinal == null ? phaseInitial : phaseFinal;
        actionObligation = actionObligation == null
                ? ActionObligationPolicy.derive(taskContract, phaseInitial)
                : actionObligation;
        taskExpectations = taskExpectations == null ? List.of() : List.copyOf(taskExpectations);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        evidenceObligation = evidenceObligation == null ? NONE_OR_NOT_DERIVED : evidenceObligation;
        outputObligation = outputObligation == null ? NOT_DERIVED : outputObligation;
        activeTaskContext = activeTaskContext == null ? NONE_OR_NOT_DERIVED : activeTaskContext;
        artifactGoal = artifactGoal == null ? NONE_OR_NOT_DERIVED : artifactGoal;
        verifierProfile = verifierProfile == null ? NONE_OR_NOT_DERIVED : verifierProfile;
    }

    public static CurrentTurnPlan create(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools
    ) {
        return create(
                contract,
                phase,
                nativeTools,
                promptTools,
                blockedTools,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED);
    }

    public static CurrentTurnPlan create(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools,
            Config cfg
    ) {
        return create(
                contract,
                phase,
                nativeTools,
                promptTools,
                blockedTools,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                NONE_OR_NOT_DERIVED,
                cfg);
    }

    public static CurrentTurnPlan create(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools,
            String activeTaskContext,
            String artifactGoal,
            String verifierProfile
    ) {
        return create(
                contract,
                phase,
                nativeTools,
                promptTools,
                blockedTools,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                null);
    }

    public static CurrentTurnPlan create(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools,
            String activeTaskContext,
            String artifactGoal,
            String verifierProfile,
            Config cfg
    ) {
        TaskContract safeContract = contract == null ? TaskContract.unknown("") : contract;
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(safeContract);
        return new CurrentTurnPlan(
                safeContract,
                safeContract.originalUserRequest(),
                phase,
                null,
                null,
                expectations,
                nativeTools,
                promptTools,
                blockedTools,
                EvidenceObligationPolicy.derive(safeContract, phase, Path.of("").toAbsolutePath(), cfg).name(),
                NOT_DERIVED,
                activeTaskContext,
                artifactGoal,
                verifierProfile);
    }

    public static CurrentTurnPlan compatibility(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools
    ) {
        return create(contract, phase, nativeTools, promptTools, blockedTools);
    }

    public static ExecutionPhase defaultPhaseFor(TaskContract contract) {
        if (contract == null) return ExecutionPhase.INSPECT;
        if (contract.mutationAllowed()) return ExecutionPhase.APPLY;
        if (contract.verificationRequired()) return ExecutionPhase.VERIFY;
        return ExecutionPhase.INSPECT;
    }

    private static ExecutionPhase defaultPhase(TaskContract contract) {
        return defaultPhaseFor(contract);
    }
}
