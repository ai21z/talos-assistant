package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTurnCapabilityFrameTest {

    @Test
    void rendersActiveTaskContextGuidanceWhenPresent() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");
        String activeTaskContext = "ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT";
        String artifactGoal = "README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                activeTaskContext,
                artifactGoal,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ActiveTaskContext]"));
        assertTrue(frame.contains(activeTaskContext));
        assertTrue(frame.contains(artifactGoal));
        assertTrue(frame.contains("Active context is a current-turn hint only"));
        assertTrue(frame.contains("Explicit current user instructions win"));
        assertTrue(frame.contains("Use active targets only for narrow deictic follow-ups"));
        assertTrue(frame.contains("Do not broaden to unrelated workspace files"));
    }

    @Test
    void legacyRenderOmitsActiveTaskContextWhenNoPlanDerivedContextIsAvailable() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");

        String frame = CurrentTurnCapabilityFrame.render(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"));

        assertFalse(frame.contains("[ActiveTaskContext]"));
        assertFalse(frame.contains("activeTaskContext:"));
        assertFalse(frame.contains("artifactGoal:"));
    }

    @Test
    void protectedReadFrameInstructsReadFileApprovalPath() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Read .env and tell me what it says.");

        String frame = CurrentTurnCapabilityFrame.render(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"));

        assertTrue(frame.contains("evidenceObligation: PROTECTED_READ_APPROVAL_REQUIRED"));
        assertTrue(frame.contains("Call talos.read_file for the protected target"));
        assertTrue(frame.contains("runtime will request approval"));
        assertTrue(frame.contains("Do not answer from protected content unless the read succeeds"));
    }

    @Test
    void renderIncludesCurrentTurnExactLiteralWriteExpectation() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExactFileWrite]"), frame);
        assertTrue(frame.contains("target: index.html"), frame);
        assertTrue(frame.contains("sourcePattern: literal-overwrite-exactly"), frame);
        assertTrue(frame.contains("expectedBytes: 5"), frame);
        assertTrue(frame.contains("expectedChars: 5"), frame);
        assertTrue(frame.contains("expectedLines: 1"), frame);
        assertTrue(frame.contains("TALOS_CURRENT_TURN_EXACT_CONTENT"), frame);
        assertTrue(frame.contains("\nAFTER\n"), frame);
        assertTrue(frame.contains("Use this exact current-turn content for the complete file write"),
                frame);
        assertTrue(frame.contains("Do not reuse exact-write literals from earlier turns"), frame);
    }

    @Test
    void renderOmitsSuppressedContextDetailsFromModelGuidance() {
        TaskContract contract = new TaskContract(
                TaskType.SMALL_TALK,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "I am only chatting, please don't inspect my files.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of(),
                "SUPPRESSED PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT summary=Replace the README title",
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertFalse(frame.contains("[ActiveTaskContext]"));
        assertFalse(frame.contains("README.md"));
        assertFalse(frame.contains("Replace the README"));
        assertFalse(frame.contains("Use active targets only for narrow deictic follow-ups"));
    }

    @Test
    void renderRedactsAndBoundsPlanDerivedActiveTaskContextFields() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");
        String longBody = "LONG_ACTIVE_BODY ".repeat(2_000);
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                "ACTIVE API_KEY=secret " + longBody,
                "ARTIFACT API_KEY=secret " + longBody,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertFalse(frame.contains("API_KEY=secret"));
        assertTrue(frame.contains("API_KEY=[redacted]"));
        assertTrue(frame.contains("..."));
        assertFalse(frame.contains(longBody));
        assertTrue(frame.length() < 4_000, "frame should not include unbounded active context text");
    }
}
