package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
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
