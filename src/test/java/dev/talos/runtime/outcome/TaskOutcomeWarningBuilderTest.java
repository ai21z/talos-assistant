package dev.talos.runtime.outcome;

import dev.talos.runtime.verification.TaskVerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskOutcomeWarningBuilderTest {

    @Test
    void toolLoopWarningsPreserveOrderAndMessagesForFailureFacts() {
        List<TruthWarning> warnings = TaskOutcomeWarningBuilder.toolLoopWarnings(
                new TaskOutcomeWarningBuilder.ToolLoopFacts(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        TaskVerificationStatus.FAILED,
                        true,
                        true));

        assertEquals(List.of(
                new TruthWarning(
                        TruthWarningType.DENIED_MUTATION,
                        "A mutating tool call was blocked by the read-only task contract."),
                new TruthWarning(
                        TruthWarningType.FAILED_ACTION_OBLIGATION,
                        "A required tool action was not performed after retry."),
                new TruthWarning(
                        TruthWarningType.COMMAND_FAILED,
                        "A requested verification command failed or timed out."),
                new TruthWarning(
                        TruthWarningType.COMMAND_DENIED,
                        "A requested verification command was not run because approval or policy blocked it."),
                new TruthWarning(
                        TruthWarningType.UNSUPPORTED_COMMAND_OUTPUT_CLAIM,
                        "The answer asserted command/tool output without a successful talos.run_command "
                                + "or matching talos.read_file outcome."),
                new TruthWarning(
                        TruthWarningType.DENIED_PROTECTED_READ,
                        "A protected read was blocked because approval was denied."),
                new TruthWarning(
                        TruthWarningType.INVALID_MUTATION_ARGUMENTS,
                        "A mutating tool call had invalid arguments and no file changed."),
                new TruthWarning(
                        TruthWarningType.PARTIAL_MUTATION,
                        "At least one mutating tool call succeeded and at least one failed."),
                new TruthWarning(
                        TruthWarningType.FALSE_MUTATION_CLAIM,
                        "The answer claimed a mutation without a successful mutating tool outcome."),
                new TruthWarning(
                        TruthWarningType.INSPECT_UNDER_COMPLETION,
                        "The answer sounded complete after an inspection-only tool path."),
                new TruthWarning(
                        TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE,
                        "Unsupported binary document reads were corrected to capability-based wording."),
                new TruthWarning(
                        TruthWarningType.SELECTOR_GROUNDED_OVERRIDE,
                        "Selector/linkage analysis was corrected from workspace evidence."),
                new TruthWarning(
                        TruthWarningType.WEB_DIAGNOSTIC_GROUNDED_OVERRIDE,
                        "Read-only web diagnostics were corrected from static workspace evidence."),
                new TruthWarning(
                        TruthWarningType.READ_ONLY_TOOL_LOOP_LIMIT,
                        "The read-only tool-call limit was reached before a complete grounded answer was produced."),
                new TruthWarning(
                        TruthWarningType.STATIC_VERIFICATION_FAILED,
                        "Static post-apply verification failed."),
                new TruthWarning(
                        TruthWarningType.MISSING_EVIDENCE,
                        "Required workspace evidence was not gathered in this turn."),
                new TruthWarning(
                        TruthWarningType.APPROVED_PROTECTED_READ_POSTCONDITION,
                        "A generic model refusal after an approved protected read was replaced with current read evidence.")
        ), warnings);
    }

    @Test
    void toolLoopWarningsUseApprovalDeniedMutationMessageAndUnavailableVerification() {
        List<TruthWarning> warnings = TaskOutcomeWarningBuilder.toolLoopWarnings(
                new TaskOutcomeWarningBuilder.ToolLoopFacts(
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        TaskVerificationStatus.UNAVAILABLE,
                        false,
                        false));

        assertEquals(List.of(
                new TruthWarning(
                        TruthWarningType.DENIED_MUTATION,
                        "A mutating tool call was denied by approval."),
                new TruthWarning(
                        TruthWarningType.STATIC_VERIFICATION_UNAVAILABLE,
                        "Static post-apply verification could not complete.")
        ), warnings);
    }

    @Test
    void noToolWarningsPreserveOrderAndMessages() {
        List<TruthWarning> warnings = TaskOutcomeWarningBuilder.noToolWarnings(
                new TaskOutcomeWarningBuilder.NoToolFacts(
                        true,
                        true,
                        true,
                        true,
                        true,
                        true));

        assertEquals(List.of(
                new TruthWarning(
                        TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED,
                        "A streaming no-tool mutation narrative was blocked."),
                new TruthWarning(
                        TruthWarningType.FAILED_ACTION_OBLIGATION,
                        "The required tool calls were not issued, so the requested action did not run."),
                new TruthWarning(
                        TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED,
                        "A streaming no-tool answer made workspace-evidence claims without tool grounding."),
                new TruthWarning(
                        TruthWarningType.MALFORMED_TOOL_PROTOCOL_DEBRIS_REPLACED,
                        "Malformed tool protocol debris was replaced with a no-action notice."),
                new TruthWarning(
                        TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED,
                        "A no-tool answer denied local workspace access despite Talos read tools."),
                new TruthWarning(
                        TruthWarningType.MISSING_EVIDENCE,
                        "Required workspace evidence was not gathered in this turn.")
        ), warnings);
    }
}
