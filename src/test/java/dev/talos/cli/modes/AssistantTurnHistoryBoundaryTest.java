package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted provider responses exercise the real executor and filesystem tool boundary. */
class AssistantTurnHistoryBoundaryTest {
    private static final String SAVED_REQUEST =
            "What retry limit is configured now? Check the file and tell me what changed.";
    private static final String CURRENT_CONTENT = "retry_limit=7\n";
    private static final String CURRENT_ANSWER =
            "The current configured retry limit is 7. The earlier change set it to 3.";
    private static final String PRIOR_OUTCOME = """
            Partial verification: static checks failed after the mutation.
            The turn remains partial. The requested task is not verified complete.

            Succeeded:
            - talos.edit_file -> settings.properties

            Remaining static verification problems:
            - Earlier retry_limit=3 was recorded, but verification remains incomplete.
            """;

    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void savedCompoundRequestReadsFreshFileWithLedgerOrPriorSummary(boolean recordedHistory) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var fixture = fixture(read("settings.properties"), CURRENT_ANSWER);

        Run run = execute(fixture, SAVED_REQUEST, recordedHistory, true);

        assertFreshRead(run, fixture, SAVED_REQUEST);
        assertTrue(run.answer().contains("current configured retry limit is 7"), run.answer());
        assertTrue(run.answer().contains("earlier change set it to 3"), run.answer());
    }

    @Test
    void changedFilesQuestionWithFreshReadDoesNotShortCircuitWithoutHistory() throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        String request = "Read settings.properties and tell me what files changed in this session.";
        var fixture = fixture(read("settings.properties"),
                "The current file contains retry_limit=7. No prior recorded changes are available.");

        Run run = execute(fixture, request, false, false);

        assertFreshRead(run, fixture, request);
        assertFalse(run.answer().startsWith("No files were changed by Talos"), run.answer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you make the changes? Read settings.properties and report the retry limit.",
            "Is it working now? Read settings.properties and report the retry limit.",
            "Is it verified now? Read settings.properties and report the retry limit.",
            "Have you run tests? Read settings.properties and report the retry limit.",
            "Which model is answering me? Read settings.properties and report the retry limit.",
            "No project inspection is needed. Read settings.properties and report the retry limit."
    })
    void compoundStatusQuestionRetainsFreshRead(String request) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var fixture = fixture(read("settings.properties"), CURRENT_ANSWER);

        Run run = execute(fixture, request, false, true);

        assertFreshRead(run, fixture, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What changed? Explain exponential backoff.",
            "What changed and compare two retry strategies?",
            "What changed about the deployment window?",
            "What changed during the last session?",
            "What changed in the previous turn?",
            "What changed during this turn?",
            "What changed yesterday?"
    })
    void unmatchedClauseReachesProviderWithoutHistoricalShortcut(String request) {
        var fixture = fixture("Additional explanation is needed for the rest of the request.");

        Run run = execute(fixture, request, true, true);

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertOriginalRequest(fixture, request);
        assertFalse(run.answer().startsWith("Recorded file changes"), run.answer());
        assertFalse(run.answer().startsWith("Partially."), run.answer());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can you summarize what changed?",
            "What changed?",
            "Tell me what changed.",
            "What files changed during this audit? Do not read protected files.",
            "What files changed during this focused audit? Do not read protected files.",
            "Which files did you modify in this session?",
            "Please give me a summary of changes.",
            "State any uncertainty you have about files changed during this audit. "
                    + "Do not claim unverified facts and do not read protected files."
    })
    void pureHistoryRequestRetainsPartialLedgerWithoutGeneration(String request) {
        var fixture = fixture("Everything is complete.");

        Run run = execute(fixture, request, true, true);

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty(), run.audit().toolCalls().toString());
        assertTrue(run.answer().startsWith("Recorded file changes"), run.answer());
        assertTrue(run.answer().contains("settings.properties"), run.answer());
        assertTrue(run.answer().contains("not verified complete"), run.answer());
        assertTrue(run.answer().contains("verification remains incomplete"), run.answer());
        assertFalse(run.answer().contains("Everything is complete"), run.answer());
        if (request.contains("uncertainty")) {
            assertTrue(run.answer().contains("Uncertainty:"), run.answer());
            assertTrue(run.answer().contains("external edits"), run.answer());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can you summarize what changed?",
            "Did you make the changes?",
            "Is it working now?"
    })
    void pureHistoryRequestRetainsPriorPartialSummaryWithoutGeneration(String request) {
        var fixture = fixture("Everything is complete.");

        Run run = execute(fixture, request, false, true);

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty());
        assertTrue(run.answer().startsWith("Partially."), run.answer());
        assertTrue(run.answer().contains("verification remains incomplete"), run.answer());
        assertFalse(run.answer().contains("Everything is complete"), run.answer());
    }

    @Test
    void pureHistoryWithoutLedgerDoesNotReadWorkspaceMarkersOrProtectedFiles() throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        Files.writeString(workspace.resolve(".env"), "SECRET=history-boundary-secret\n");
        var fixture = fixture(read(".env"));

        Run run = execute(fixture,
                "What files changed during this audit? Do not read protected files.", false, true);

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty());
        assertTrue(run.answer().startsWith("No files were changed by Talos"), run.answer());
        assertFalse(run.answer().contains("settings.properties"), run.answer());
        assertFalse(run.answer().contains("history-boundary-secret"), run.answer());
    }

    @Test
    void compoundHistoryBypassDoesNotAuthorizeMutation() throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var fixture = fixture(
                "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"settings.properties\",\"content\":\"retry_limit=9\\n\"}}",
                read("settings.properties"), CURRENT_ANSWER);

        Run run = execute(fixture, SAVED_REQUEST, true, true);

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertFalse(run.audit().policyTrace().mutationAllowed(), run.audit().policyTrace().toString());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call ->
                "talos.write_file".equals(call.name()) && call.success()), run.audit().toolCalls().toString());
        assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("settings.properties")));
    }

    @Test
    void compoundHistoryBypassRetainsExplicitProtectedReadDenial() throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        Files.writeString(workspace.resolve(".env"), "SECRET=history-boundary-secret\n");
        String request = "Read settings.properties and tell me what changed. Do not read protected files.";
        var fixture = fixture(read(".env"), read("settings.properties"), CURRENT_ANSWER);
        var approvals = new AtomicInteger();

        Run run = execute(fixture, request, true, true, (description, detail) -> {
            approvals.incrementAndGet();
            assertTrue(description.contains("protected read"), description);
            assertTrue(detail.contains(".env"), detail);
            return false;
        });

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertEquals(1, approvals.get());
        assertEquals(1, run.audit().approvalsRequired());
        assertEquals(1, run.audit().approvalsDenied());
        assertEquals(0, run.audit().approvalsGranted());
        var protectedCalls = run.audit().toolCalls().stream().filter(call ->
                "talos.read_file".equals(call.name()) && ".env".equals(call.pathHint())).toList();
        assertEquals(1, protectedCalls.size(), run.audit().toolCalls().toString());
        assertFalse(protectedCalls.getFirst().success());
        assertEquals("approval denied by user for talos.read_file", protectedCalls.getFirst().reason());
        assertEquals(dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED,
                protectedCalls.getFirst().executionDisposition());
        assertEquals(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                protectedCalls.getFirst().failureReason());
        assertFalse(protectedCalls.getFirst().mutationEffects().hasEffects());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call -> call.success()
                && ".env".equals(call.pathHint())), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains("history-boundary-secret"), run.answer());
        assertTrue(fixture.requests().stream().flatMap(call -> call.messages.stream())
                .noneMatch(message -> message.content().contains("history-boundary-secret")));
    }

    private void assertFreshRead(Run run, ScriptedNativeLlmClient.RecordedClient fixture,
                                 String request) throws Exception {
        assertTrue(run.audit().toolCalls().stream().anyMatch(call ->
                        "talos.read_file".equals(call.name()) && call.success()
                                && "settings.properties".equals(call.pathHint())),
                "Fresh read was swallowed: " + run.answer() + " calls=" + run.audit().toolCalls());
        assertTrue(fixture.requests().stream().skip(1).flatMap(call -> call.messages.stream())
                .anyMatch(message -> message.content().contains("retry_limit=7")),
                "Fresh file content did not reach the continuation");
        assertOriginalRequest(fixture, request);
        assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("settings.properties")));
        assertFalse(run.audit().policyTrace().mutationAllowed(), run.audit().policyTrace().toString());
    }

    private static void assertOriginalRequest(ScriptedNativeLlmClient.RecordedClient fixture, String request) {
        String providerTask = dev.talos.runtime.policy.RuntimeModelIdentityAnswerPolicy.providerTask(request);
        assertTrue(fixture.requests().stream().flatMap(call -> call.messages.stream())
                .anyMatch(message -> "user".equals(message.role()) && providerTask.equals(message.content())), request);
    }

    private ScriptedNativeLlmClient.RecordedClient fixture(String... responses) {
        return ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                java.util.Arrays.stream(responses)
                        .map(response -> new LlmClient.StreamResult(response, List.of())).toList(),
                ModelIdentitySnapshot::empty);
    }

    private Run execute(ScriptedNativeLlmClient.RecordedClient fixture, String request,
                        boolean recordedHistory, boolean priorAssistantSummary) {
        return execute(fixture, request, recordedHistory, priorAssistantSummary, new NoOpApprovalGate());
    }

    private Run execute(ScriptedNativeLlmClient.RecordedClient fixture, String request,
                        boolean recordedHistory, boolean priorAssistantSummary, ApprovalGate approvalGate) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var memory = new SessionMemory();
        if (recordedHistory) {
            memory.setChangeSummaryContext(new ChangeSummaryContext(ChangeSummaryContext.SCHEMA_VERSION,
                    List.of(new ChangeSummaryContext.FileChange("settings.properties", "talos.edit_file", 1,
                            "history-boundary-prior", "SUCCEEDED", "FAILED", "TASK_INCOMPLETE")),
                    List.of(), "FAILED", "TASK_INCOMPLETE",
                    List.of("Earlier retry_limit=3 was recorded, but verification remains incomplete.")));
        }
        var context = Context.builder(new Config(null)).memory(memory).llm(fixture.client())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, approvalGate, registry), 4))
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        if (priorAssistantSummary) {
            messages.add(ChatMessage.user("Set the retry limit to 3 in settings.properties."));
            messages.add(ChatMessage.assistant(PRIOR_OUTCOME));
        }
        messages.add(ChatMessage.user(request));
        LocalTurnTraceCapture.begin("history-boundary", "history-tests", 2,
                "2026-09-27T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", request);
        TurnAuditCapture.begin();
        try (var ownedClient = fixture.client()) {
            String answer = AssistantTurnExecutor.execute(messages, workspace, context,
                    new AssistantTurnExecutor.Options()).text();
            assertTrue(messages.stream().anyMatch(message -> "user".equals(message.role()) && request.equals(message.content())));
            return new Run(answer, TurnAuditCapture.end());
        }
    }

    private static String read(String path) {
        return "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + path + "\"}}";
    }

    private record Run(String answer, TurnAudit audit) {}
}
