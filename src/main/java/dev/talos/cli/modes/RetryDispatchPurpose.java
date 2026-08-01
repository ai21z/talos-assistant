package dev.talos.cli.modes;

import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;

import java.util.List;

/**
 * Typed purpose for a bounded provider retry.
 *
 * <p>The retry prose is not an authority source. Each purpose projects only
 * the request controls needed for that retry while retaining the immutable
 * current-user contract, targets, and provenance.
 */
enum RetryDispatchPurpose {
    POST_TOOL_SYNTHESIS(
            ActionObligation.NONE,
            false,
            ToolSurface.NONE,
            null),
    INSPECT_COMPLETENESS(
            ActionObligation.INSPECT_REQUIRED,
            true,
            ToolSurface.READ_ONLY,
            ExecutionPhase.INSPECT),
    READ_ONLY_INSPECTION(
            ActionObligation.INSPECT_REQUIRED,
            true,
            ToolSurface.READ_ONLY,
            ExecutionPhase.INSPECT),
    NO_TOOL_GROUNDING(
            ActionObligation.INSPECT_REQUIRED,
            true,
            ToolSurface.READ_ONLY,
            ExecutionPhase.INSPECT),
    MISSING_MUTATION(
            null,
            true,
            ToolSurface.PRESERVE,
            null);

    private final ActionObligation actionObligation;
    private final boolean preserveEvidenceObligation;
    private final ToolSurface toolSurface;
    private final ExecutionPhase phaseOverride;

    RetryDispatchPurpose(
            ActionObligation actionObligation,
            boolean preserveEvidenceObligation,
            ToolSurface toolSurface,
            ExecutionPhase phaseOverride
    ) {
        this.actionObligation = actionObligation;
        this.preserveEvidenceObligation = preserveEvidenceObligation;
        this.toolSurface = toolSurface;
        this.phaseOverride = phaseOverride;
    }

    CurrentTurnPlan project(CurrentTurnPlan capturedPlan) {
        if (capturedPlan == null) {
            throw new IllegalArgumentException(
                    "captured retry plan is required");
        }
        ExecutionPhase projectedInitialPhase =
                phaseOverride == null
                        ? capturedPlan.phaseInitial()
                        : phaseOverride;
        ExecutionPhase projectedFinalPhase =
                phaseOverride == null
                        ? capturedPlan.phaseFinal()
                        : phaseOverride;
        return new CurrentTurnPlan(
                capturedPlan.taskContract(),
                capturedPlan.originalUserRequest(),
                projectedInitialPhase,
                projectedFinalPhase,
                actionObligation == null
                        ? capturedPlan.actionObligation()
                        : actionObligation,
                capturedPlan.taskExpectations(),
                projectTools(capturedPlan.nativeTools()),
                projectTools(capturedPlan.promptTools()),
                capturedPlan.blockedTools(),
                preserveEvidenceObligation
                        ? capturedPlan.evidenceObligation()
                        : EvidenceObligation.NONE.name(),
                capturedPlan.outputObligation(),
                capturedPlan.activeTaskContext(),
                capturedPlan.artifactGoal(),
                capturedPlan.verifierProfile(),
                capturedPlan.requestObligation());
    }

    String debugTag() {
        return "retry-purpose:" + name();
    }

    private List<String> projectTools(List<String> capturedTools) {
        List<String> safe =
                capturedTools == null ? List.of() : capturedTools;
        return switch (toolSurface) {
            case NONE -> List.of();
            case PRESERVE -> safe;
            case READ_ONLY -> safe.stream()
                    .filter(ToolCallSupport::isReadOnlyTool)
                    .toList();
        };
    }

    private enum ToolSurface {
        NONE,
        READ_ONLY,
        PRESERVE
    }
}
