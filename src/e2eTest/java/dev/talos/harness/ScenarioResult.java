package dev.talos.harness;

import dev.talos.runtime.SessionData;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.ToolCallLoop;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final int approvalsAsked;
    private final int approvalsGranted;
    private final int approvalsDenied;
    private final int approvalsRemembered;
    private final List<String> approvalDetails;
    private final Path sessionsDir;
    private final String sessionId;
    private final SessionData snapshot;
    private final List<TurnRecord> turnLog;
    private final int replayedTurns;
    private final List<String> restoredAssistantTurns;
    private final List<AutoCloseable> resourcesToClose;

    ScenarioResult(
            ScenarioDefinition definition,
            ToolCallLoop.LoopResult loopResult,
            ScenarioWorkspaceFixture workspace,
            List<String> toolResultTexts,
            int approvalsAsked,
            int approvalsGranted,
            int approvalsDenied,
            int approvalsRemembered,
            List<String> approvalDetails,
            Path sessionsDir,
            String sessionId,
            SessionData snapshot,
            List<TurnRecord> turnLog,
            int replayedTurns,
            List<String> restoredAssistantTurns,
            List<AutoCloseable> resourcesToClose) {
        this.definition = definition;
        this.loopResult = loopResult;
        this.workspace = workspace;
        this.toolResultTexts = List.copyOf(toolResultTexts);
        this.approvalsAsked = approvalsAsked;
        this.approvalsGranted = approvalsGranted;
        this.approvalsDenied = approvalsDenied;
        this.approvalsRemembered = approvalsRemembered;
        this.approvalDetails = approvalDetails == null ? List.of() : List.copyOf(approvalDetails);
        this.sessionsDir = sessionsDir;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.snapshot = snapshot;
        this.turnLog = turnLog == null ? List.of() : List.copyOf(turnLog);
        this.replayedTurns = replayedTurns;
        this.restoredAssistantTurns = restoredAssistantTurns == null ? List.of() : List.copyOf(restoredAssistantTurns);
        this.resourcesToClose = resourcesToClose == null ? List.of() : List.copyOf(resourcesToClose);
    }

    public ScenarioDefinition definition() { return definition; }
    public ToolCallLoop.LoopResult loopResult() { return loopResult; }
    public ScenarioWorkspaceFixture workspace() { return workspace; }
    public List<String> toolResultTexts() { return toolResultTexts; }
    public List<String> toolNames() { return loopResult.toolNames(); }

    public int toolsInvoked()     { return loopResult.toolsInvoked(); }
    public int failedCalls()      { return loopResult.failedCalls(); }
    public int retriedCalls()     { return loopResult.retriedCalls(); }
    public boolean hitIterLimit() { return loopResult.hitIterLimit(); }
    public String finalAnswer()   { return loopResult.finalAnswer(); }
    public int approvalsAsked()   { return approvalsAsked; }
    public int approvalsGranted() { return approvalsGranted; }
    public int approvalsDenied()  { return approvalsDenied; }
    public int approvalsRemembered() { return approvalsRemembered; }
    public List<String> approvalDetails() { return approvalDetails; }
    public Path sessionsDir() { return sessionsDir; }
    public String sessionId() { return sessionId; }
    public SessionData snapshot() { return snapshot; }
    public List<TurnRecord> turnLog() { return turnLog; }
    public int replayedTurns() { return replayedTurns; }
    public List<String> restoredAssistantTurns() { return restoredAssistantTurns; }
    List<AutoCloseable> resourcesToClose() { return resourcesToClose; }

    public boolean anyToolResultContains(String substring) {
        return toolResultTexts.stream().anyMatch(t -> t.contains(substring));
    }

    public boolean usedTool(String toolName) {
        return loopResult.toolNames().stream().anyMatch(toolName::equals);
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

    public ScenarioResult assertUsedTool(String expectedTool) {
        if (!usedTool(expectedTool)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected tool to be used: " + expectedTool
                    + ". Actual tools: " + loopResult.toolNames());
        }
        return this;
    }

    public ScenarioResult assertDidNotUseTool(String forbiddenTool) {
        if (usedTool(forbiddenTool)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected tool NOT to be used: " + forbiddenTool
                    + ". Actual tools: " + loopResult.toolNames());
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

    public ScenarioResult assertApprovalCounts(int asked, int granted, int denied, int remembered) {
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

    public ScenarioResult assertAnyApprovalDetailContains(String expected) {
        boolean found = approvalDetails.stream().anyMatch(d -> d != null && d.contains(expected));
        if (!found) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected an approval detail to contain [" + expected
                    + "], actual details: " + approvalDetails);
        }
        return this;
    }

    // ── Answer-content assertions ───────────────────────────────────
    //
    // These assert on the *final answer text* returned by ToolCallLoop. They
    // operate at the harness seam only - i.e. on text ToolCallLoop itself
    // produces. They do NOT exercise AssistantTurnExecutor's post-loop
    // answer gates (deflection retry, claim-vs-action annotation); those
    // remain covered at the executor seam in AssistantTurnExecutorTest.
    //
    // Determinism note: when a scripted response contains no tool calls,
    // ToolCallLoop returns it verbatim and these assertions are fully
    // deterministic. When tool calls do fire, the PLACEHOLDER LLM re-prompt
    // makes post-tool text non-deterministic - in that case prefer
    // file/tool assertions over answer-text assertions.

    /**
     * Assert that the final answer text contains the given substring.
     * Uses plain {@link String#contains} - no regex.
     */
    public ScenarioResult assertAnswerContains(String expected) {
        String answer = finalAnswer();
        if (answer == null || !answer.contains(expected)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer to contain: " + quote(expected)
                    + "\nActual answer: " + quote(answer));
        }
        return this;
    }

    /**
     * Assert that the final answer text does NOT contain the given substring.
     * Useful for "the answer must not claim something the workspace disproves."
     */
    public ScenarioResult assertAnswerNotContains(String forbidden) {
        String answer = finalAnswer();
        if (answer != null && answer.contains(forbidden)) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected answer NOT to contain: " + quote(forbidden)
                    + "\nActual answer: " + quote(answer));
        }
        return this;
    }

    public ScenarioResult assertSnapshotExists() {
        if (snapshot == null || sessionsDir == null || sessionId.isBlank()
                || !Files.exists(sessionsDir.resolve(sessionId + ".json"))) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected snapshot file to exist for session " + sessionId);
        }
        return this;
    }

    public ScenarioResult assertTurnLogExists() {
        if (sessionsDir == null || sessionId.isBlank()
                || !Files.exists(sessionsDir.resolve(sessionId + ".turns.jsonl"))) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected turn log file to exist for session " + sessionId);
        }
        return this;
    }

    public ScenarioResult assertTurnLogSize(int expected) {
        if (turnLog.size() != expected) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected turn log size=" + expected + " but was " + turnLog.size());
        }
        return this;
    }

    public ScenarioResult assertTurnLogAssistantTextContains(String expected) {
        boolean found = turnLog.stream().anyMatch(r -> r.assistantText().contains(expected));
        if (!found) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected turn log assistant text to contain [" + expected + "]");
        }
        return this;
    }

    public ScenarioResult assertTurnLogAssistantTextNotContains(String forbidden) {
        boolean found = turnLog.stream().anyMatch(r -> r.assistantText().contains(forbidden));
        if (found) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected turn log assistant text to NOT contain [" + forbidden + "]");
        }
        return this;
    }

    public ScenarioResult assertReplayedTurns(int expected) {
        if (replayedTurns != expected) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected replayedTurns=" + expected + " but was " + replayedTurns);
        }
        return this;
    }

    public ScenarioResult assertRestoredAssistantTurnContains(String expected) {
        boolean found = restoredAssistantTurns.stream().anyMatch(t -> t.contains(expected));
        if (!found) {
            throw new AssertionError("Scenario '" + definition.name()
                    + "': expected restored assistant turns to contain [" + expected + "]"
                    + ", actual: " + restoredAssistantTurns);
        }
        return this;
    }

    private static String quote(String s) {
        if (s == null) return "<null>";
        // Trim very long answers in failure messages so assertion errors stay readable.
        String trimmed = s.length() > 500 ? s.substring(0, 500) + "…[truncated]" : s;
        return "\"" + trimmed + "\"";
    }

    /** Close and delete the workspace fixture. Call after all assertions are done. */
    public void closeWorkspace() {
        workspace.close();
        for (AutoCloseable closeable : resourcesToClose) {
            if (closeable == null) continue;
            try { closeable.close(); }
            catch (Exception ignored) { }
        }
        deleteRecursive(sessionsDir);
    }

    /** AutoCloseable - delegates to closeWorkspace(). Enables try-with-resources. */
    @Override
    public void close() {
        closeWorkspace();
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (Exception ignored) { }
                    });
        } catch (Exception ignored) { }
    }
}
