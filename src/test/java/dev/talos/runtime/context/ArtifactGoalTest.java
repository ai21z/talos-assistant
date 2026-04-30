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

    @Test void derivesStaticWebGoalFromWebTargets() {
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, goalFor("index.html").artifactKind());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, goalFor("page.htm").artifactKind());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, goalFor("style.css").artifactKind());
        assertEquals(ArtifactGoal.ArtifactKind.STATIC_WEB, goalFor("app.js").artifactKind());
    }

    @Test void derivesMarkdownGoalFromNonReadmeMarkdownTarget() {
        ArtifactGoal goal = goalFor("docs/guide.md");

        assertEquals(ArtifactGoal.ArtifactKind.MARKDOWN, goal.artifactKind());
    }

    @Test void derivesGenericFileGoalFromNonWebNonMarkdownTarget() {
        ArtifactGoal goal = goalFor("src/Main.java");

        assertEquals(ArtifactGoal.ArtifactKind.GENERIC_FILE, goal.artifactKind());
    }

    @Test void nullOrNoTargetActiveContextReturnsNoneGoal() {
        ActiveTaskContext noTargets = ActiveTaskContext.proposedChanges(
                1,
                "trace-empty",
                List.of(),
                "no targets");

        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, ArtifactGoal.fromActiveContext(null).artifactKind());
        assertEquals(ActiveTaskContext.Operation.NONE, ArtifactGoal.fromActiveContext(null).operation());
        assertEquals(ArtifactGoal.Source.NONE, ArtifactGoal.fromActiveContext(null).source());
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, ArtifactGoal.fromActiveContext(noTargets).artifactKind());
        assertEquals(ActiveTaskContext.Operation.NONE, ArtifactGoal.fromActiveContext(noTargets).operation());
        assertEquals(ArtifactGoal.Source.NONE, ArtifactGoal.fromActiveContext(noTargets).source());
    }

    @Test void nonActiveContextReturnsNoneGoal() {
        ActiveTaskContext active = ActiveTaskContext.proposedChanges(
                1,
                "trace-non-active",
                List.of("README.md"),
                "update README");

        assertNoneGoal(ArtifactGoal.fromActiveContext(active.suppressed("answer only")));
        assertNoneGoal(ArtifactGoal.fromActiveContext(active.cleared("new task")));
        assertNoneGoal(ArtifactGoal.fromActiveContext(active.expired("too old")));
    }

    @Test void targetsAreCopiedAndImmutable() {
        List<String> targets = new java.util.ArrayList<>(List.of("README.md"));
        ArtifactGoal goal = new ArtifactGoal(
                ArtifactGoal.ArtifactKind.README,
                ActiveTaskContext.Operation.APPLY_EDIT,
                targets,
                "profile",
                ArtifactGoal.Source.CURRENT_REQUEST);

        targets.set(0, "changed.md");

        assertEquals(List.of("README.md"), goal.targets());
        assertThrows(UnsupportedOperationException.class, () -> goal.targets().add("new.md"));
    }

    @Test void renderForPlanRedactsVerifierProfileAndCapsOutput() {
        ArtifactGoal goal = new ArtifactGoal(
                ArtifactGoal.ArtifactKind.GENERIC_FILE,
                ActiveTaskContext.Operation.VERIFY,
                List.of("build.gradle.kts"),
                "API_KEY=secret " + "x".repeat(2_000),
                ArtifactGoal.Source.CURRENT_REQUEST);

        String rendered = goal.renderForPlan();

        assertTrue(rendered.length() <= ActiveTaskContext.PROMPT_RENDER_CHAR_CAP);
        assertFalse(rendered.contains("API_KEY=secret"));
        assertTrue(rendered.contains("[redacted]"));
    }

    private static ArtifactGoal goalFor(String target) {
        return ArtifactGoal.fromActiveContext(ActiveTaskContext.proposedChanges(
                3,
                "trace-target",
                List.of(target),
                "update " + target));
    }

    private static void assertNoneGoal(ArtifactGoal goal) {
        assertEquals(ArtifactGoal.ArtifactKind.UNKNOWN, goal.artifactKind());
        assertEquals(ActiveTaskContext.Operation.NONE, goal.operation());
        assertEquals(List.of(), goal.targets());
        assertEquals(ArtifactGoal.Source.NONE, goal.source());
    }
}
