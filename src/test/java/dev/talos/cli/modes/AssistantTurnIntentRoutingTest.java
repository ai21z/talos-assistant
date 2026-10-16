package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted answers are used to test routing and preservation, not model reasoning. */
class AssistantTurnIntentRoutingTest {
    private static final String FRESH_READ = "Please inspect retry.py again. Using the current implementation and configured retry limit, "
            + "list the attempt numbers that would run if each attempt failed, and state when should_retry becomes false. "
            + "Separately explain any remaining discrepancy with README. Use the current files, not just your previous answer. "
            + "Don't change files or run tests.";
    private static final String HISTORY = "Have you actually run tests or changed files during this conversation, "
            + "or only inspected them? Separate observed file changes from work performed by you and from proposed fixes. "
            + "Don't run anything now.";
    private static final String READ_ANSWER = "The current files were inspected. Attempts 1, 2, 3 and 4 would run. "
            + "should_retry becomes false at 4. README requests four retries after the first attempt, "
            + "so one further attempt is missing. No files were changed and no tests were run.";
    private static final String HISTORY_ANSWER = "Only file inspection was performed in this conversation. "
            + "No tests were run and no files were changed by Talos. The proposed correction was not applied. "
            + "External edits cannot be attributed to Talos from the retained evidence.";
    private static final String PYTHON = "def total_attempts(retry_limit):\n    return retry_limit\n\n"
            + "def should_retry(attempt_number, retry_limit):\n    return attempt_number < total_attempts(retry_limit)\n";

    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void savedProhibitionDoesNotPreventProviderAndCurrentReads() throws Exception {
        files();
        var fixture = fixture(read("retry.py"), read("settings.json"), read("README.md"), READ_ANSWER);

        Run run = execute(fixture, FRESH_READ);

        assertFalse(fixture.requests().isEmpty(), run.answer());
        for (String path : List.of("retry.py", "settings.json", "README.md")) {
            assertTrue(run.audit().toolCalls().stream().anyMatch(call ->
                    call.success() && "talos.read_file".equals(call.name()) && path.equals(call.pathHint())),
                    path + " " + run.audit().toolCalls());
        }
        assertFalse(run.answer().contains("approved command profile"), run.answer());
        assertFalse(run.answer().contains("ordered pair computation"), run.answer());
        assertFalse(run.audit().policyTrace().mutationAllowed());
        assertEquals(PYTHON, Files.readString(workspace.resolve("retry.py")));
        assertTrue(run.audit().toolCalls().stream().allMatch(call -> "talos.read_file".equals(call.name())));
    }

    @Test
    void savedWorkAccountingUsesRuntimeRecordsInsteadOfComparisonOrFreshReads() throws Exception {
        files();
        var fixture = fixture(read("README.md"), read("settings.json"), read("retry.py"), HISTORY_ANSWER);

        Run run = execute(fixture, HISTORY);

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertFalse(run.answer().contains("ordered pair computation"), run.answer());
        assertTrue(run.answer().contains("Retained runtime action records"), run.answer());
        assertTrue(run.answer().contains("not proof that no earlier action occurred"), run.answer());
        assertFalse(run.answer().contains("Only file inspection was performed"), run.answer());
        assertFalse(run.answer().contains("No tests were run"), run.answer());
        assertFalse(run.audit().policyTrace().mutationAllowed());
        assertTrue(run.audit().toolCalls().isEmpty());
        assertEquals(PYTHON, Files.readString(workspace.resolve("retry.py")));
        var contract = TaskContractResolver.fromUserRequest(HISTORY);
        assertNotEquals(EvidenceObligation.DOCUMENT_COMPARISON_REQUIRED,
                EvidenceObligationPolicy.derive(contract, CurrentTurnPlan.defaultPhaseFor(contract), workspace));
    }

    @Test
    void realUnsupportedPythonExecutionStillStopsBeforeProvider() {
        var fixture = fixture("A response must not be requested.");

        Run run = execute(fixture, "Run python -m pytest.");

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty());
        assertTrue(run.answer().contains("approved command profile"), run.answer());
    }

    @Test
    void prohibitedExecutionDoesNotAuthorizeScriptedMutation() throws Exception {
        files();
        var fixture = fixture("{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"retry.py\",\"content\":\"changed\"}}",
                read("retry.py"), READ_ANSWER);

        Run run = execute(fixture, FRESH_READ);

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertFalse(run.audit().policyTrace().mutationAllowed());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call ->
                "talos.write_file".equals(call.name()) && call.success()));
        assertEquals(PYTHON, Files.readString(workspace.resolve("retry.py")));
    }

    @Test
    void missingCurrentEvidenceStillPreventsCompletion() {
        var messages = messages(FRESH_READ);
        var contract = TaskContractResolver.fromMessages(messages);
        var phase = CurrentTurnPlan.defaultPhaseFor(contract);
        var plan = CurrentTurnPlan.create(contract, phase, List.of("talos.read_file"), List.of("talos.read_file"), List.of());

        var outcome = ExecutionOutcome.fromNoTool(READ_ANSWER, plan, messages, null, true);

        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertFalse(outcome.mutationRequested());
    }

    @Test
    void unresolvedComparisonIsNotSatisfiedByTheAbsenceOfBoundPairs() {
        String request = "Compare these files.";
        var contract = TaskContractResolver.fromMessages(messages(request));

        assertEquals(EvidenceObligation.DOCUMENT_COMPARISON_REQUIRED,
                EvidenceObligationPolicy.derive(contract, CurrentTurnPlan.defaultPhaseFor(contract), workspace));
        assertFalse(TurnAuditCapture.comparisonEvidenceComplete(contract, workspace));
    }

    @Test
    void boundComparisonWithoutCurrentEvidenceIsIncomplete() {
        var contract = TaskContractResolver.fromMessages(messages("Compare a.md and b.md."));

        assertFalse(TurnAuditCapture.comparisonEvidenceComplete(contract, workspace));
    }

    @Test
    void ordinaryReadAndWorkAccountingDoNotAcquireComparisonObligations() {
        for (String request : List.of(FRESH_READ, HISTORY, "Read a.md and explain its contents.")) {
            var contract = TaskContractResolver.fromMessages(messages(request));
            assertTrue(TurnAuditCapture.comparisonEvidenceComplete(contract, workspace), request);
        }
    }

    private void files() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), PYTHON);
        Files.writeString(workspace.resolve("settings.json"), "{\"retry_limit\":4,\"network\":false}\n");
        Files.writeString(workspace.resolve("README.md"), "retry_limit counts retries after the first attempt. Attempt numbers start at one.\n");
    }

    private Run execute(ScriptedNativeLlmClient.RecordedClient fixture, String request) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var context = Context.builder(new Config(null)).memory(new SessionMemory()).llm(fixture.client())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 5)).build();
        LocalTurnTraceCapture.begin("intent-routing", "routing-tests", 2, "2026-09-27T00:00:00Z",
                "workspace-hash", "test", "llama_cpp", "fixture", request);
        TurnAuditCapture.begin();
        try (var ownedClient = fixture.client()) {
            String answer = AssistantTurnExecutor.execute(messages(request), workspace, context,
                    new AssistantTurnExecutor.Options()).text();
            return new Run(answer, TurnAuditCapture.end());
        }
    }

    private static ArrayList<ChatMessage> messages(String request) {
        return new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                ChatMessage.user("Inspect the backup example. Do not edit files."),
                ChatMessage.assistant("An additional attempt was proposed. Nothing was changed or executed."),
                ChatMessage.user(request)));
    }

    private static ScriptedNativeLlmClient.RecordedClient fixture(String... responses) {
        return ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList(),
                ModelIdentitySnapshot::empty);
    }

    private static String read(String path) {
        return "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + path + "\"}}";
    }

    private record Run(String answer, TurnAudit audit) {}
}
