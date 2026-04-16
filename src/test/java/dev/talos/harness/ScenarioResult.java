package dev.talos.harness;

import dev.talos.runtime.ToolCallLoop;

import java.util.List;
import java.util.function.Consumer;

/**
 * Captures the outcome of a single ScenarioRunner run.
 */
public final class ScenarioResult implements AutoCloseable {

    private final ScenarioDefinition definition;
    private final ToolCallLoop.LoopResult loopResult;
    private final ScenarioWorkspaceFixture workspace;
    private final List<String> toolResultTexts;

    ScenarioResult(
            ScenarioDefinition definition,
            ToolCallLoop.LoopResult loopResult,
            ScenarioWorkspaceFixture workspace,
            List<String> toolResultTexts) {
        this.definition = definition;
        this.loopResult = loopResult;
        this.workspace = workspace;
        this.toolResultTexts = List.copyOf(toolResultTexts);
    }

    public ScenarioDefinition definition() { return definition; }
    public ToolCallLoop.LoopResult loopResult() { return loopResult; }
    public ScenarioWorkspaceFixture workspace() { return workspace; }
    public List<String> toolResultTexts() { return toolResultTexts; }

    public int toolsInvoked()     { return loopResult.toolsInvoked(); }
    public int failedCalls()      { return loopResult.failedCalls(); }
    public int retriedCalls()     { return loopResult.retriedCalls(); }
    public boolean hitIterLimit() { return loopResult.hitIterLimit(); }
    public String finalAnswer()   { return loopResult.finalAnswer(); }

    public boolean anyToolResultContains(String substring) {
        return toolResultTexts.stream().anyMatch(t -> t.contains(substring));
    }

    public ScenarioResult assertWorkspace(Consumer<ScenarioWorkspaceFixture> assertion) {
        assertion.accept(workspace);
        return this;
    }

    public ScenarioResult assertFileExists(String relativePath) {
        workspace.assertFileExists(relativePath);
        return this;
    }

    public ScenarioResult assertFileAbsent(String relativePath) {
        workspace.assertFileAbsent(relativePath);
        return this;
    }

    public ScenarioResult assertFileContains(String relativePath, String expected) {
        workspace.assertFileContains(relativePath, expected);
        return this;
    }

    public ScenarioResult assertFileNotContains(String relativePath, String forbidden) {
        workspace.assertFileNotContains(relativePath, forbidden);
        return this;
    }

    public ScenarioResult assertToolsInvoked(int expected) {
        if (toolsInvoked() != expected) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected toolsInvoked=" + expected + " but was " + toolsInvoked()
                    + ". Loop summary: " + loopResult.summary());
        }
        return this;
    }

    public ScenarioResult assertFailedCalls(int expected) {
        if (failedCalls() != expected) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected failedCalls=" + expected + " but was " + failedCalls()
                    + ". Loop summary: " + loopResult.summary());
        }
        return this;
    }

    public ScenarioResult assertNoFailedCalls() {
        return assertFailedCalls(0);
    }

    public ScenarioResult assertHitIterLimit(boolean expected) {
        if (hitIterLimit() != expected) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected hitIterLimit=" + expected + " but was " + hitIterLimit());
        }
        return this;
    }

    /** Close and delete the workspace fixture. Call after all assertions are done. */
    public void closeWorkspace() {
        workspace.close();
    }

    /** AutoCloseable — delegates to closeWorkspace(). Enables try-with-resources. */
    @Override
    public void close() {
        closeWorkspace();
    }
}
