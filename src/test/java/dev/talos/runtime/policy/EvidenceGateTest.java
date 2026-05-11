package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceGateTest {

    @Test
    void selectedObligationPrefersRecordedPlanValue(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.SMALL_TALK,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "hello");
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                EvidenceObligation.READ_TARGET_REQUIRED.name(),
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceGate.selectObligation(plan, workspace));
    }

    @Test
    void readTargetHandoffSkipsProtectedTargets(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("README.md", ".env"),
                Set.of(),
                "Read README.md and summarize it.");

        List<String> targets = EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.READ_TARGET_REQUIRED,
                workspace);

        assertTrue(targets.contains("README.md"), targets.toString());
        assertFalse(targets.contains(".env"), targets.toString());
    }

    @Test
    void protectedReadHandoffRequiresExplicitReadIntent(@TempDir Path workspace) {
        TaskContract readEnv = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Read .env and tell me what it contains.");
        TaskContract mentionOnly = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Is .env a protected path?");
        TaskContract negated = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Do not read .env; explain why it is protected.");

        assertTrue(EvidenceGate.hasExplicitProtectedReadIntent(
                readEnv,
                EvidenceGate.protectedExpectedTargets(readEnv, workspace)));
        assertFalse(EvidenceGate.hasExplicitProtectedReadIntent(
                mentionOnly,
                EvidenceGate.protectedExpectedTargets(mentionOnly, workspace)));
        assertFalse(EvidenceGate.hasExplicitProtectedReadIntent(
                negated,
                EvidenceGate.protectedExpectedTargets(negated, workspace)));
    }

    @Test
    void unsupportedCapabilityTargetsAreSelectedSeparately(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("report.docx", "README.md"),
                Set.of(),
                "Read report.docx and README.md.");

        assertFalse(EvidenceGate.hasOnlyUnsupportedExpectedTargets(contract));
        assertEquals(List.of("report.docx"), EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                workspace));
    }

    @Test
    void sourceEvidenceTargetsDriveHandoffInsteadOfMutationTargets(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("docs/summary.md"),
                Set.of("long-notes.txt"),
                Set.of(),
                "Summarize long-notes.txt into docs/summary.md.",
                "explicit-source-to-target-artifact-request");

        assertEquals(List.of("long-notes.txt"), EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.READ_TARGET_REQUIRED,
                workspace));
    }
}
