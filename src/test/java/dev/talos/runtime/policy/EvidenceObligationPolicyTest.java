package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceObligationPolicyTest {
    private static final Path WORKSPACE = Path.of("").toAbsolutePath();

    @Test
    void explicitTextReadRequiresReadingExpectedTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Read README.md and summarize it.");

        assertEquals(
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void protectedReadTargetRequiresApproval() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Read .env and tell me the keys.");

        assertEquals(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void simpleDirectoryListingIsListOnly() {
        TaskContract contract = TaskContractResolver.fromUserRequest("List the files here.");

        assertEquals(
                EvidenceObligation.LIST_DIRECTORY_ONLY,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void workspaceExplainRequiresWorkspaceInspection() {
        TaskContract contract = TaskContractResolver.fromUserRequest("What is this project?");

        assertEquals(
                EvidenceObligation.WORKSPACE_INSPECTION_REQUIRED,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void staticWebDiagnosisRequiresStaticWebDiagnosisEvidence() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Check whether this website has mismatches between HTML classes/IDs "
                        + "and selectors used in CSS or JavaScript. Do not change anything yet.");

        assertEquals(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void unsupportedDocumentTargetRequiresCapabilityCheck() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Read report.docx and summarize it.");

        assertEquals(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, WORKSPACE));
    }

    @Test
    void noWorkspaceSmallTalkHasNoEvidenceObligation() {
        TaskContract contract = new TaskContract(
                TaskType.SMALL_TALK,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "hello");

        assertEquals(
                EvidenceObligation.NONE,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.RESPOND, null));
    }

    @Test
    void parseFallsBackToNoneForBlankOrUnknownValues() {
        assertEquals(EvidenceObligation.NONE, EvidenceObligationPolicy.parse(null));
        assertEquals(EvidenceObligation.NONE, EvidenceObligationPolicy.parse("  "));
        assertEquals(EvidenceObligation.NONE, EvidenceObligationPolicy.parse("NOPE"));
    }
}
