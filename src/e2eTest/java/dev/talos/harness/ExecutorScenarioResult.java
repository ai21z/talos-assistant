package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;

import java.util.function.Consumer;

/**
 * Outcome of a {@link ScenarioRunner#runThroughExecutor(ScenarioDefinition,
 * String, java.util.List) runThroughExecutor(...)} harness run.
 *
 * <p>Captures the {@link AssistantTurnExecutor.TurnOutput} produced by
 * driving {@code AssistantTurnExecutor.execute(...)} end-to-end with a
 * scripted {@link dev.talos.core.llm.LlmClient} plus the workspace
 * fixture (so file-existence / content assertions remain available).
 *
 * <p>Deliberately narrower than {@link ScenarioResult}: the executor
 * seam does not expose a {@code LoopResult} directly (the loop runs
 * inside {@code execute()}), so {@code toolsInvoked} /
 * {@code failedCalls} / {@code retriedCalls} accessors would be
 * dishonest. When a scenario needs those, use {@link ScenarioResult}
 * via {@link ScenarioRunner#run(ScenarioDefinition)} instead.
 *
 * <p>The primary assertion surface is answer text — which is exactly
 * what the executor-seam gates (R2 / R6 / N2 / N3) produce. See
 * §8 N4 of {@code docs/new-architecture/talos-harness-main-plan.md}
 * for the seam design.
 */
public final class ExecutorScenarioResult implements AutoCloseable {

    private final ScenarioDefinition definition;
    private final AssistantTurnExecutor.TurnOutput turnOutput;
    private final ScenarioWorkspaceFixture workspace;
    private final AutoCloseable resourceToClose;

    ExecutorScenarioResult(
            ScenarioDefinition definition,
            AssistantTurnExecutor.TurnOutput turnOutput,
            ScenarioWorkspaceFixture workspace,
            AutoCloseable resourceToClose) {
        this.definition = definition;
        this.turnOutput = turnOutput;
        this.workspace = workspace;
        this.resourceToClose = resourceToClose;
    }

    public ScenarioDefinition definition() { return definition; }
    public AssistantTurnExecutor.TurnOutput turnOutput() { return turnOutput; }
    public ScenarioWorkspaceFixture workspace() { return workspace; }

    /** Full answer text produced by the executor (includes any gate annotations). */
    public String finalAnswer() { return turnOutput.text(); }

    /** True if the turn was streamed to a sink. */
    public boolean streamed() { return turnOutput.streamed(); }

    // ── Answer-text assertions (mirrors ScenarioResult API) ───────────

    public ExecutorScenarioResult assertAnswerContains(String expected) {
        String answer = finalAnswer();
        if (answer == null || !answer.contains(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to contain [" + expected
                    + "]\nActual answer:\n" + answer);
        }
        return this;
    }

    public ExecutorScenarioResult assertAnswerNotContains(String forbidden) {
        String answer = finalAnswer();
        if (answer != null && answer.contains(forbidden)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to NOT contain [" + forbidden
                    + "]\nActual answer:\n" + answer);
        }
        return this;
    }

    public ExecutorScenarioResult assertAnswerStartsWith(String expected) {
        String answer = finalAnswer();
        if (answer == null || !answer.startsWith(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to start with [" + expected
                    + "]\nActual answer:\n" + answer);
        }
        return this;
    }

    // ── Filesystem assertions (delegate to workspace fixture) ─────────

    public ExecutorScenarioResult assertWorkspace(Consumer<ScenarioWorkspaceFixture> assertion) {
        assertion.accept(workspace);
        return this;
    }

    public ExecutorScenarioResult assertFileExists(String relativePath) {
        workspace.assertFileExists(relativePath);
        return this;
    }

    public ExecutorScenarioResult assertFileAbsent(String relativePath) {
        workspace.assertFileAbsent(relativePath);
        return this;
    }

    public ExecutorScenarioResult assertFileContains(String relativePath, String expected) {
        workspace.assertFileContains(relativePath, expected);
        return this;
    }

    public ExecutorScenarioResult assertFileNotContains(String relativePath, String forbidden) {
        workspace.assertFileNotContains(relativePath, forbidden);
        return this;
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    public void closeWorkspace() {
        workspace.close();
        if (resourceToClose != null) {
            try { resourceToClose.close(); }
            catch (Exception ignored) { }
        }
    }

    @Override public void close() { closeWorkspace(); }
}

