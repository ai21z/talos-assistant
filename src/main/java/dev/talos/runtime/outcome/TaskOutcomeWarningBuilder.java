package dev.talos.runtime.outcome;

import dev.talos.runtime.verification.TaskVerificationStatus;

import java.util.ArrayList;
import java.util.List;

public final class TaskOutcomeWarningBuilder {
    private TaskOutcomeWarningBuilder() {
    }

    public record ToolLoopFacts(
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean readOnlyDeniedMutation,
            boolean failedActionObligation,
            boolean commandFailed,
            boolean commandDenied,
            boolean unsupportedCommandOutputClaim,
            boolean invalidMutation,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean unsupportedDocumentCapabilityLimited,
            boolean staticWebImportGroundedOverride,
            boolean webDiagnosticGroundedOverride,
            boolean selectorGroundedOverride,
            boolean readOnlyToolLimitWithoutRuntimeAnswer,
            TaskVerificationStatus verificationStatus,
            boolean missingEvidence,
            boolean approvedProtectedReadPostcondition
    ) {
        public ToolLoopFacts {
            verificationStatus = verificationStatus == null
                    ? TaskVerificationStatus.NOT_RUN
                    : verificationStatus;
        }
    }

    public record NoToolFacts(
            boolean noToolMutationReplaced,
            boolean failedActionObligation,
            boolean ungrounded,
            boolean malformedProtocolDebrisReplaced,
            boolean localAccessCapabilityCorrected,
            boolean missingEvidence
    ) {
    }

    public static List<TruthWarning> toolLoopWarnings(ToolLoopFacts facts) {
        if (facts == null) return List.of();
        List<TruthWarning> warnings = new ArrayList<>();
        if (facts.deniedMutation()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.DENIED_MUTATION,
                    facts.readOnlyDeniedMutation()
                            ? "A mutating tool call was blocked by the read-only task contract."
                            : "A mutating tool call was denied by approval."));
        }
        if (facts.failedActionObligation()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FAILED_ACTION_OBLIGATION,
                    "A required tool action was not performed after retry."));
        }
        if (facts.commandFailed()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.COMMAND_FAILED,
                    "A requested verification command failed or timed out."));
        }
        if (facts.commandDenied()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.COMMAND_DENIED,
                    "A requested verification command was not run because approval or policy blocked it."));
        }
        if (facts.unsupportedCommandOutputClaim()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.UNSUPPORTED_COMMAND_OUTPUT_CLAIM,
                    "The answer asserted command/tool output without a successful talos.run_command "
                            + "or matching talos.read_file outcome."));
        }
        if (facts.deniedProtectedRead()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.DENIED_PROTECTED_READ,
                    "A protected read was blocked because approval was denied."));
        }
        if (facts.invalidMutation()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.INVALID_MUTATION_ARGUMENTS,
                    "A mutating tool call had invalid arguments and no file changed."));
        }
        if (facts.partialMutation()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.PARTIAL_MUTATION,
                    "At least one mutating tool call succeeded and at least one failed."));
        }
        if (facts.falseMutationClaim()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FALSE_MUTATION_CLAIM,
                    "The answer claimed a mutation without a successful mutating tool outcome."));
        }
        if (facts.inspectUnderCompleted()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.INSPECT_UNDER_COMPLETION,
                    "The answer sounded complete after an inspection-only tool path."));
        }
        if (facts.unsupportedDocumentCapabilityLimited()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE,
                    "Unsupported binary document reads were corrected to capability-based wording."));
        }
        if (facts.selectorGroundedOverride()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.SELECTOR_GROUNDED_OVERRIDE,
                    "Selector/linkage analysis was corrected from workspace evidence."));
        }
        if (facts.staticWebImportGroundedOverride() || facts.webDiagnosticGroundedOverride()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.WEB_DIAGNOSTIC_GROUNDED_OVERRIDE,
                    "Read-only web diagnostics were corrected from static workspace evidence."));
        }
        if (facts.readOnlyToolLimitWithoutRuntimeAnswer()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.READ_ONLY_TOOL_LOOP_LIMIT,
                    "The read-only tool-call limit was reached before a complete grounded answer was produced."));
        }
        if (facts.verificationStatus() == TaskVerificationStatus.FAILED) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STATIC_VERIFICATION_FAILED,
                    "Static post-apply verification failed."));
        } else if (facts.verificationStatus() == TaskVerificationStatus.UNAVAILABLE) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STATIC_VERIFICATION_UNAVAILABLE,
                    "Static post-apply verification could not complete."));
        }
        if (facts.missingEvidence()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MISSING_EVIDENCE,
                    "Required workspace evidence was not gathered in this turn."));
        }
        if (facts.approvedProtectedReadPostcondition()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.APPROVED_PROTECTED_READ_POSTCONDITION,
                    "A generic model refusal after an approved protected read was replaced with current read evidence."));
        }
        return List.copyOf(warnings);
    }

    public static List<TruthWarning> noToolWarnings(NoToolFacts facts) {
        if (facts == null) return List.of();
        List<TruthWarning> warnings = new ArrayList<>();
        if (facts.noToolMutationReplaced()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED,
                    "A streaming no-tool mutation narrative was blocked."));
        }
        if (facts.failedActionObligation()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FAILED_ACTION_OBLIGATION,
                    "The required tool calls were not issued, so the requested action did not run."));
        }
        if (facts.ungrounded()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED,
                    "A streaming no-tool answer made workspace-evidence claims without tool grounding."));
        }
        if (facts.malformedProtocolDebrisReplaced()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MALFORMED_TOOL_PROTOCOL_DEBRIS_REPLACED,
                    "Malformed tool protocol debris was replaced with a no-action notice."));
        }
        if (facts.localAccessCapabilityCorrected()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED,
                    "A no-tool answer denied local workspace access despite Talos read tools."));
        }
        if (facts.missingEvidence()) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MISSING_EVIDENCE,
                    "Required workspace evidence was not gathered in this turn."));
        }
        return List.copyOf(warnings);
    }
}
