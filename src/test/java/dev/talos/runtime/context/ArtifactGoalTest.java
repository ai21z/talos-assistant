package dev.talos.runtime.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactGoalTest {

    @Test void derivesReadmeGoalFromMarkdownTarget() {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(
                3,
                "trace-readme",
                List.of("README.md"),
                "update README");

        ArtifactGoal goal = ArtifactGoal.fromActiveContext(context);

        assertEquals(ArtifactGoal.ArtifactKind.README, goal.artifactKind());
        assertEquals(ActiveTaskContext.Operation.APPLY_EDIT, goal.operation());
        assertEquals(List.of("README.md"), goal.targets());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, goal.source());
        assertTrue(goal.renderForPlan().contains("README"));
        assertTrue(goal.renderForPlan().contains("APPLY_EDIT"));
    }

    @Test void noneRendersAsNotDerived() {
        assertEquals(ActiveTaskContext.NONE_OR_NOT_DERIVED, ArtifactGoal.none().renderForPlan());
    }
}
