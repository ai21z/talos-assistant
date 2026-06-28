package dev.talos.harness;

import dev.talos.runtime.Result;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;

import java.util.List;
import java.util.function.Consumer;

/**
 * Outcome of a deterministic scenario routed through {@link dev.talos.runtime.TurnProcessor}.
 *
 * <p>This seam is narrower than {@link ScenarioResult} because it validates
 * mode routing and turn-level trace behavior rather than raw loop counters.
 */
public final class TurnProcessorScenarioResult implements AutoCloseable {

    private final ScenarioDefinition definition;
    private final TurnResult turnResult;
    private final ScenarioWorkspaceFixture workspace;
    private final String activeMode;
    private final int approvalsAsked;
    private final int approvalsGranted;
    private final int approvalsDenied;
    private final int approvalsRemembered;
    private final List<AutoCloseable> resourcesToClose;

    TurnProcessorScenarioResult(
            ScenarioDefinition definition,
            TurnResult turnResult,
            ScenarioWorkspaceFixture workspace,
            String activeMode,
            int approvalsAsked,
            int approvalsGranted,
            int approvalsDenied,
            int approvalsRemembered,
            List<AutoCloseable> resourcesToClose) {
        this.definition = definition;
        this.turnResult = turnResult;
        this.workspace = workspace;
        this.activeMode = activeMode == null ? "" : activeMode;
        this.approvalsAsked = approvalsAsked;
        this.approvalsGranted = approvalsGranted;
        this.approvalsDenied = approvalsDenied;
        this.approvalsRemembered = approvalsRemembered;
        this.resourcesToClose = resourcesToClose == null ? List.of() : List.copyOf(resourcesToClose);
    }

    public ScenarioDefinition definition() { return definition; }
    public TurnResult turnResult() { return turnResult; }
    public ScenarioWorkspaceFixture workspace() { return workspace; }
    public String activeMode() { return activeMode; }
    public LocalTurnTrace localTrace() {
        return turnResult == null || turnResult.audit() == null ? null : turnResult.audit().localTrace();
    }

    public String finalAnswer() {
        return render(turnResult == null ? null : turnResult.result());
    }

    public TurnProcessorScenarioResult assertActiveMode(String expected) {
        if (!activeMode.equals(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected active mode [" + expected + "] but was [" + activeMode + "]");
        }
        return this;
    }

    public TurnProcessorScenarioResult assertTraceMode(String expected) {
        LocalTurnTrace trace = localTrace();
        String actual = trace == null ? "" : trace.mode();
        if (!expected.equals(actual)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected trace mode [" + expected + "] but was [" + actual + "]");
        }
        return this;
    }

    public TurnProcessorScenarioResult assertApprovalCounts(int asked, int granted, int denied, int remembered) {
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

    public TurnProcessorScenarioResult assertAnswerContains(String expected) {
        String answer = finalAnswer();
        if (answer == null || !answer.contains(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to contain [" + expected
                    + "]\nActual answer:\n" + answer);
        }
        return this;
    }

    public TurnProcessorScenarioResult assertAnswerNotContains(String forbidden) {
        String answer = finalAnswer();
        if (answer != null && answer.contains(forbidden)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to NOT contain [" + forbidden
                    + "]\nActual answer:\n" + answer);
        }
        return this;
    }

    public TurnProcessorScenarioResult assertWorkspace(Consumer<ScenarioWorkspaceFixture> assertion) {
        assertion.accept(workspace);
        return this;
    }

    public TurnProcessorScenarioResult assertFileExists(String relativePath) {
        workspace.assertFileExists(relativePath);
        return this;
    }

    public TurnProcessorScenarioResult assertFileAbsent(String relativePath) {
        workspace.assertFileAbsent(relativePath);
        return this;
    }

    public TurnProcessorScenarioResult assertFileContains(String relativePath, String expected) {
        workspace.assertFileContains(relativePath, expected);
        return this;
    }

    public TurnProcessorScenarioResult assertFileNotContains(String relativePath, String forbidden) {
        workspace.assertFileNotContains(relativePath, forbidden);
        return this;
    }

    @Override
    public void close() {
        workspace.close();
        for (AutoCloseable closeable : resourcesToClose) {
            if (closeable == null) continue;
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best-effort test cleanup
            }
        }
    }

    private static String render(Result result) {
        if (result == null) return "";
        if (result instanceof Result.Ok ok) return ok.text;
        if (result instanceof Result.Info info) return info.text;
        if (result instanceof Result.TrustedInfo info) return info.text;
        if (result instanceof Result.Error error) return error.message;
        if (result instanceof Result.Streamed streamed) return streamed.fullText + streamed.suffix;
        if (result instanceof Result.Table table) return renderTable(table);
        return result.toString();
    }

    private static String renderTable(Result.Table table) {
        StringBuilder sb = new StringBuilder();
        if (!table.title.isBlank()) {
            sb.append(table.title).append('\n');
        }
        if (!table.columns.isEmpty()) {
            sb.append(String.join(" ", table.columns)).append('\n');
        }
        for (List<String> row : table.rows) {
            sb.append(String.join(" ", row)).append('\n');
        }
        return sb.toString();
    }
}
