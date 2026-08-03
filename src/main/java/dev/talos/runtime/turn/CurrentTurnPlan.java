package dev.talos.runtime.turn;

import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.CapabilityProfileRegistry;
import dev.talos.runtime.capability.VerifierProfile;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.core.Config;

import java.nio.file.Path;
import java.util.LinkedHashSet;
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
        String verifierProfile,
        RequestObligation requestObligation,
        WorkspaceOperationAuthorityPlan workspaceOperationPlan,
        List<String> contentMutationPredecessorPaths
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
        List<TaskExpectation> suppliedTaskExpectations =
                taskExpectations == null ? List.of() : List.copyOf(taskExpectations);
        List<TaskExpectation> compiledTaskExpectations =
                TaskExpectationResolver.resolve(taskContract);
        if (!suppliedTaskExpectations.isEmpty()
                && !suppliedTaskExpectations.equals(compiledTaskExpectations)) {
            throw new IllegalArgumentException(
                    "Supplied task expectations differ from runtime compilation");
        }
        taskExpectations = compiledTaskExpectations;
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blockedTools = blockedTools == null ? List.of() : List.copyOf(blockedTools);
        RequestObligation compiledRequestObligation =
                RequestObligationCompiler.compile(taskContract);
        if (requestObligation != null
                && !requestObligation.equals(compiledRequestObligation)) {
            throw new IllegalArgumentException(
                    "Supplied request obligation differs from the runtime-compiled obligation");
        }
        requestObligation = compiledRequestObligation;
        workspaceOperationPlan = workspaceOperationPlan == null
                ? WorkspaceOperationAuthorityPlan.none(originalUserRequest)
                : workspaceOperationPlan;
        if (!workspaceOperationPlan.bindsRequest(originalUserRequest)) {
            throw new IllegalArgumentException(
                    "Supplied workspace operation plan differs from the current request");
        }
        contentMutationPredecessorPaths = contentMutationPredecessorPaths == null
                ? List.of()
                : contentMutationPredecessorPaths.stream()
                        .filter(path -> path != null && !path.isBlank())
                        .map(CurrentTurnPlan::normalizePath)
                        .distinct()
                        .toList();
        evidenceObligation = evidenceObligation == null ? NONE_OR_NOT_DERIVED : evidenceObligation;
        outputObligation = outputObligation == null ? NOT_DERIVED : outputObligation;
        activeTaskContext = activeTaskContext == null ? NONE_OR_NOT_DERIVED : activeTaskContext;
        artifactGoal = artifactGoal == null ? NONE_OR_NOT_DERIVED : artifactGoal;
        verifierProfile = verifierProfile == null ? NONE_OR_NOT_DERIVED : verifierProfile;
    }

    /** Exact provider-visible authority across native and text-tool surfaces. */
    public List<String> visibleTools() {
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        visible.addAll(nativeTools);
        visible.addAll(promptTools);
        visible.removeAll(blockedTools);
        return List.copyOf(visible);
    }

    /**
     * Compatibility constructor retained while callers migrate to the shared
     * request-obligation component.
     */
    public CurrentTurnPlan(
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
        this(
                taskContract,
                originalUserRequest,
                phaseInitial,
                phaseFinal,
                actionObligation,
                taskExpectations,
                nativeTools,
                promptTools,
                blockedTools,
                evidenceObligation,
                outputObligation,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                null);
    }

    /** Compatibility constructor for callers supplying request obligation only. */
    public CurrentTurnPlan(
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
            String verifierProfile,
            RequestObligation requestObligation
    ) {
        this(
                taskContract,
                originalUserRequest,
                phaseInitial,
                phaseFinal,
                actionObligation,
                taskExpectations,
                nativeTools,
                promptTools,
                blockedTools,
                evidenceObligation,
                outputObligation,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                requestObligation,
                null,
                List.of());
    }

    /** Compatibility constructor for plans created before predecessor capture. */
    public CurrentTurnPlan(
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
            String verifierProfile,
            RequestObligation requestObligation,
            WorkspaceOperationAuthorityPlan workspaceOperationPlan
    ) {
        this(
                taskContract,
                originalUserRequest,
                phaseInitial,
                phaseFinal,
                actionObligation,
                taskExpectations,
                nativeTools,
                promptTools,
                blockedTools,
                evidenceObligation,
                outputObligation,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                requestObligation,
                workspaceOperationPlan,
                List.of());
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
                derivedVerifierProfile(contract));
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
                derivedVerifierProfile(contract),
                cfg);
    }

    public static CurrentTurnPlan createWithActionObligation(
            TaskContract contract,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools,
            String activeTaskContext,
            String artifactGoal,
            String verifierProfile,
            ActionObligation actionObligation,
            Config cfg
    ) {
        TaskContract safeContract =
                contract == null ? TaskContract.unknown("") : contract;
        List<TaskExpectation> expectations =
                TaskExpectationResolver.resolve(safeContract);
        return new CurrentTurnPlan(
                safeContract,
                safeContract.originalUserRequest(),
                phase,
                null,
                actionObligation,
                expectations,
                nativeTools,
                promptTools,
                blockedTools,
                EvidenceObligationPolicy.derive(
                        safeContract,
                        phase,
                        Path.of("").toAbsolutePath(),
                        cfg).name(),
                NOT_DERIVED,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                null,
                null,
                List.of());
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

    public static CurrentTurnPlan createWithActionObligation(
            TaskResolution resolution,
            ExecutionPhase phase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blockedTools,
            String activeTaskContext,
            String artifactGoal,
            String verifierProfile,
            ActionObligation actionObligation,
            Config cfg
    ) {
        TaskResolution safeResolution = resolution == null
                ? TaskResolution.contractOnly(TaskContract.unknown(""))
                : resolution;
        TaskContract contract = safeResolution.contract();
        return new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                phase,
                null,
                actionObligation,
                TaskExpectationResolver.resolve(contract),
                nativeTools,
                promptTools,
                blockedTools,
                EvidenceObligationPolicy.derive(
                        contract,
                        phase,
                        Path.of("").toAbsolutePath(),
                        cfg).name(),
                NOT_DERIVED,
                activeTaskContext,
                artifactGoal,
                verifierProfile,
                null,
                safeResolution.workspaceOperationPlan(),
                safeResolution.contentMutationPredecessorPaths());
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
                verifierProfile,
                null);
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

    private static String normalizePath(String value) {
        String normalized = value.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public static String derivedVerifierProfile(TaskContract contract) {
        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);
        if (profile == null || profile.verifierProfile() == VerifierProfile.NONE) {
            return NONE_OR_NOT_DERIVED;
        }
        return profile.verifierProfile().name();
    }

    public String canonicalRequestObligationHash() {
        return requestObligation.canonicalSha256();
    }

    private static ExecutionPhase defaultPhase(TaskContract contract) {
        return defaultPhaseFor(contract);
    }
}
