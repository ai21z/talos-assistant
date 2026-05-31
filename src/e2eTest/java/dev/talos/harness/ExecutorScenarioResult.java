package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.runtime.trace.LocalTurnTrace;

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
 * §8 N4 of {@code docs/architecture/talos-harness-main-plan.md}
 * for the seam design.
 */
public final class ExecutorScenarioResult implements AutoCloseable {

    private final ScenarioDefinition definition;
    private final AssistantTurnExecutor.TurnOutput turnOutput;
    private final ScenarioWorkspaceFixture workspace;
    private final AutoCloseable resourceToClose;
    private final String streamedText;
    private final int approvalsAsked;
    private final int approvalsGranted;
    private final int approvalsDenied;
    private final int approvalsRemembered;
    private final LocalTurnTrace localTrace;

    ExecutorScenarioResult(
            ScenarioDefinition definition,
            AssistantTurnExecutor.TurnOutput turnOutput,
            ScenarioWorkspaceFixture workspace,
            AutoCloseable resourceToClose,
            String streamedText,
            int approvalsAsked,
            int approvalsGranted,
            int approvalsDenied,
            int approvalsRemembered) {
        this(definition, turnOutput, workspace, resourceToClose, streamedText,
                approvalsAsked, approvalsGranted, approvalsDenied, approvalsRemembered, null);
    }

    ExecutorScenarioResult(
            ScenarioDefinition definition,
            AssistantTurnExecutor.TurnOutput turnOutput,
            ScenarioWorkspaceFixture workspace,
            AutoCloseable resourceToClose,
            String streamedText,
            int approvalsAsked,
            int approvalsGranted,
            int approvalsDenied,
            int approvalsRemembered,
            LocalTurnTrace localTrace) {
        this.definition = definition;
        this.turnOutput = turnOutput;
        this.workspace = workspace;
        this.resourceToClose = resourceToClose;
        this.streamedText = streamedText == null ? "" : streamedText;
        this.approvalsAsked = approvalsAsked;
        this.approvalsGranted = approvalsGranted;
        this.approvalsDenied = approvalsDenied;
        this.approvalsRemembered = approvalsRemembered;
        this.localTrace = localTrace;
    }

    public ScenarioDefinition definition() { return definition; }
    public AssistantTurnExecutor.TurnOutput turnOutput() { return turnOutput; }
    public ScenarioWorkspaceFixture workspace() { return workspace; }

    /** Full answer text produced by the executor (includes any gate annotations). */
    public String finalAnswer() { return turnOutput.text(); }

    /** True if the turn was streamed to a sink. */
    public boolean streamed() { return turnOutput.streamed(); }

    /** Text emitted to the stream sink during execution. Empty for non-streaming runs. */
    public String streamedText() { return streamedText; }

    /** Redacted local trace summary attached by the executor scenario harness, if available. */
    public LocalTurnTrace localTrace() { return localTrace; }

    public String traceSummary() {
        if (localTrace == null) return "";
        return localTrace.traceId()
                + " events=" + localTrace.events().size()
                + " outcome=" + localTrace.outcome().status()
                + " verification=" + localTrace.verification().status();
    }

    public ExecutorScenarioResult assertLocalTraceRecorded() {
        if (localTrace == null || localTrace.traceId().isBlank()) {
            throw new AssertionError("Scenario '" + definition.name() + "': expected a local trace to be attached");
        }
        return this;
    }

    public ExecutorScenarioResult assertApprovalCounts(int asked, int granted, int denied, int remembered) {
        if (approvalsAsked != asked || approvalsGranted != granted
                || approvalsDenied != denied || approvalsRemembered != remembered) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected approvals asked/granted/denied/remembered = "
                    + asked + "/" + granted + "/" + denied + "/" + remembered
                    + " but was "
                    + approvalsAsked + "/" + approvalsGranted + "/" + approvalsDenied + "/" + approvalsRemembered);
        }
        return this;
    }

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

    public ExecutorScenarioResult assertStreamedTextContains(String expected) {
        if (!streamedText.contains(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected streamed text to contain [" + expected
                    + "]\nActual streamed text:\n" + streamedText);
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

