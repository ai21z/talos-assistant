package dev.talos.runtime.phase;

import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasePolicyTest {

    @Test
    void inspectAllowsReadSearchAndRetrieveButNotMutate() {
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.INSPECT,
                PhasePolicy.categorize("talos.read_file", ToolRiskLevel.READ_ONLY)));
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.INSPECT,
                PhasePolicy.categorize("talos.grep", ToolRiskLevel.READ_ONLY)));
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.INSPECT,
                PhasePolicy.categorize("talos.retrieve", ToolRiskLevel.READ_ONLY)));
        assertFalse(PhasePolicy.allows(
                ExecutionPhase.INSPECT,
                PhasePolicy.categorize("talos.write_file", ToolRiskLevel.WRITE)));
    }

    @Test
    void applyKeepsMutatingToolsEligibleForApprovalPath() {
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.APPLY,
                PhasePolicy.categorize("talos.write_file", ToolRiskLevel.WRITE)));
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.APPLY,
                PhasePolicy.categorize("talos.edit_file", ToolRiskLevel.WRITE)));
    }

    @Test
    void verifyBlocksFurtherMutationButKeepsReadToolsAvailable() {
        assertTrue(PhasePolicy.allows(
                ExecutionPhase.VERIFY,
                PhasePolicy.categorize("talos.read_file", ToolRiskLevel.READ_ONLY)));
        assertFalse(PhasePolicy.allows(
                ExecutionPhase.VERIFY,
                PhasePolicy.categorize("talos.edit_file", ToolRiskLevel.WRITE)));
    }
}
