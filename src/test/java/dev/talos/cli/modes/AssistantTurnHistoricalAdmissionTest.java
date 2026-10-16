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
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.HistoricalFollowUpRequest;
import dev.talos.runtime.task.TaskType;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted responses test admission and evidence transport, not general model answer quality. */
class AssistantTurnHistoricalAdmissionTest {
    private static final String CURRENT_CONTENT = "retry_limit=7\n";
    private static final String FRESH_READ = " Read settings.properties and report the retry limit.";
    private static final String SCRIPTED_ANSWER = "The current configured retry limit is 7.";
    private static final String PRIOR_FINDING =
            "Earlier retry_limit=3 was recorded, but verification remains incomplete.";
    private static final String STATIC_BLOCKER = "HTML does not link JavaScript file: `script.js`";

    @TempDir Path workspace;

    enum Route {
        SESSION_UNCERTAINTY("What changed during this session? Include uncertainty."),
        SESSION_META_EVIDENCE("Did you change settings.properties earlier?"),
        STATIC_WEB_DIAGNOSTIC(
                "Based only on verified file evidence from the previous answer, list the blockers "
                        + "that prevent the button from working."),
        DOCUMENT_CREATION_STATUS("Did you create any valid pdf or docx in this audit?");

        private final String pureRequest;

        Route(String pureRequest) {
            this.pureRequest = pureRequest;
        }
    }

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void compoundReadContractRetainsOriginalRequestAndRequiredTarget(Route route) {
        String request = route.pureRequest + FRESH_READ;

        TaskContract contract = TaskContractResolver.fromMessages(messages(route, request));

        assertAll(
                () -> assertEquals(request, contract.originalUserRequest()),
                () -> assertTrue(contract.expectedTargets().contains("settings.properties")
                                || contract.sourceEvidenceTargets().contains("settings.properties"),
                        contract.toString()),
                () -> assertFalse(contract.mutationAllowed(), contract.toString()),
                () -> assertNotEquals("session-meta-evidence-question", contract.classificationReason()),
                () -> assertNotEquals("session-uncertainty-question", contract.classificationReason()));
        if (route == Route.SESSION_UNCERTAINTY || route == Route.SESSION_META_EVIDENCE) {
            assertEquals(EvidenceObligation.READ_TARGET_REQUIRED,
                    EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, workspace),
                    "A fresh read must not degrade into verification from earlier trace evidence");
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void compoundHistoryRequestReachesProviderAndReadsCurrentContent(Route route) throws Exception {
        assertFreshRead(route, route.pureRequest + FRESH_READ);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void freshReadBeforeHistoricalClauseReachesProviderAndReadsCurrentContent(Route route) throws Exception {
        String request = FRESH_READ.strip() + " " + route.pureRequest;
        TaskContract contract = TaskContractResolver.fromMessages(messages(route, request));
        assertEquals(request, contract.originalUserRequest());
        assertTrue(contract.expectedTargets().contains("settings.properties")
                || contract.sourceEvidenceTargets().contains("settings.properties"), contract.toString());
        assertNotEquals("session-meta-evidence-question", contract.classificationReason());
        assertNotEquals("session-uncertainty-question", contract.classificationReason());
        if (route == Route.SESSION_UNCERTAINTY || route == Route.SESSION_META_EVIDENCE) {
            assertEquals(EvidenceObligation.READ_TARGET_REQUIRED,
                    EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, workspace));
        }
        assertFreshRead(route, request);
    }

    private void assertFreshRead(Route route, String request) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var fixture = fixture(read("settings.properties"), SCRIPTED_ANSWER);

        Run run = execute(fixture, route, request, new NoOpApprovalGate());

        assertTrue(run.audit().toolCalls().stream().anyMatch(call ->
                        "talos.read_file".equals(call.name()) && call.success()
                                && "settings.properties".equals(call.pathHint())),
                "Fresh read was swallowed: " + run.answer() + " calls=" + run.audit().toolCalls());
        assertOriginalRequest(fixture, request);
        assertTrue(fixture.requests().stream().skip(1).flatMap(call -> call.messages.stream())
                        .anyMatch(message -> message.content().contains("retry_limit=7")),
                "The actual file content must reach the provider continuation");
        assertTrue(fixture.requests().getFirst().messages.stream()
                        .anyMatch(message -> message.content().contains("retry_limit=3")),
                "The fixture must retain conflicting earlier evidence");
        assertFalse(run.audit().policyTrace().mutationAllowed(), run.audit().policyTrace().toString());
        assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("settings.properties")));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void pureSupportedHistoryRequestKeepsRuntimeAnswerWithoutProviderOrTools(Route route) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var fixture = fixture(read("settings.properties"), "Unexpected provider answer.");

        Run run = execute(fixture, route, route.pureRequest, new NoOpApprovalGate());

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty(), run.audit().toolCalls().toString());
        switch (route) {
            case SESSION_UNCERTAINTY -> {
                assertTrue(run.answer().startsWith("Recorded file changes in this session/audit:"), run.answer());
                assertTrue(run.answer().contains("settings.properties"), run.answer());
                assertTrue(run.answer().contains("Uncertainty:"), run.answer());
                assertTrue(run.answer().contains("not verified complete"), run.answer());
                assertTrue(run.answer().contains(PRIOR_FINDING), run.answer());
                assertTrue(run.answer().contains("runtime mutation history"), run.answer());
                assertTrue(run.answer().contains("external edits"), run.answer());
                assertTrue(run.answer().contains("protected file contents"), run.answer());
            }
            case SESSION_META_EVIDENCE -> {
                assertTrue(run.answer().contains("File edit: settings.properties. A Talos edit is not established"),
                        run.answer());
                assertFalse(run.answer().contains("it mutated settings.properties"), run.answer());
                assertTrue(run.audit().workStatusReceipt().active());
            }
            case STATIC_WEB_DIAGNOSTIC -> assertEquals(
                    "Based on the previous runtime-owned static web diagnostics, the blockers are:\n- "
                            + STATIC_BLOCKER,
                    run.answer());
            case DOCUMENT_CREATION_STATUS -> {
                assertTrue(run.answer().startsWith(
                        "No. Talos has no runtime evidence that it created a valid PDF or DOCX"), run.answer());
                assertTrue(run.answer().contains("unsupported-document capability refusals"), run.answer());
            }
        }
        assertFalse(run.answer().contains("retry_limit=7"), run.answer());
    }

    @Test
    void pureSessionUncertaintyKeepsItsDedicatedRuntimeAnswer() {
        var fixture = fixture("Unexpected provider answer.");
        String request = "What are you unsure about from this session?";

        Run run = execute(fixture, Route.SESSION_UNCERTAINTY, request, new NoOpApprovalGate());

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty(), run.audit().toolCalls().toString());
        assertTrue(run.answer().startsWith("Uncertainty:"), run.answer());
        assertTrue(run.answer().contains("not verified complete"), run.answer());
        assertTrue(run.answer().contains(PRIOR_FINDING), run.answer());
        assertTrue(run.answer().contains("external edits and protected file contents"), run.answer());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Have you written read-settings.properties earlier?|talos.write_file|read-settings.properties|mutated",
            "Did Talos inspect changes.properties earlier?|talos.read_file|changes.properties|read",
            "Has Talos opened write-notes.md?|talos.read_file|write-notes.md|read",
            "Have you modified read-notes.md in this session?|talos.edit_file|read-notes.md|mutated"
    })
    void ordinaryMetadataVariationsUseParsedActionInsteadOfFilename(
            String request, String tool, String path, String action) {
        var fixture = fixture("Unexpected provider answer.");

        Run run = execute(fixture, Route.SESSION_META_EVIDENCE, request, new NoOpApprovalGate(),
                List.of(new TurnRecord.ToolCallSummary(tool, path, true)));

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty(), run.audit().toolCalls().toString());
        if ("mutated".equals(action)) {
            assertTrue(run.answer().contains("File edit: " + path + ". A Talos edit is not established"), run.answer());
            assertFalse(run.answer().contains("it mutated " + path), run.answer());
            assertTrue(run.audit().workStatusReceipt().active());
        } else {
            assertEquals("Yes. Talos has runtime evidence that it " + action + " " + path
                    + " earlier in this session.", run.answer());
        }
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void compoundNonFileExplanationCannotBeConsumedByHistoricalAnswer(Route route) {
        String request = route.pureRequest + " Explain exponential backoff.";
        var fixture = fixture("Exponential backoff increases the delay between successive retries.");

        Run run = execute(fixture, route, request, new NoOpApprovalGate());

        assertFalse(fixture.requests().isEmpty(), "Additional explanation was swallowed: " + run.answer());
        assertOriginalRequest(fixture, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you read settings.properties? Did you change settings.properties earlier?",
            "Did you change settings.properties yesterday?",
            "Did you change settings.properties in the previous session?",
            "Did you read settings.properties after edits earlier in this session? Answer yes or no.",
            "What are you uncertain about from yesterday's session?",
            "What are you unsure about from this session? Did you change settings.properties earlier?",
            "Did you change settings.properties earlier? What are you unsure about from this session?",
            "What changed? Is it verified?",
            "Is it verified? What changed?"
    })
    void mixedEvidenceRolesAndUnsupportedTemporalScopeReachProvider(String request) {
        var fixture = fixture("The recorded evidence does not establish all requested historical details.");

        Run run = execute(fixture, Route.SESSION_META_EVIDENCE, request, new NoOpApprovalGate());

        assertFalse(fixture.requests().isEmpty(), "Unsupported historical scope was consumed: " + run.answer());
        assertOriginalRequest(fixture, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Is this BMI page working now?",
            "What are you unsure about from this session? Explain why it is incomplete."
    })
    void rejectedHistoricalShortcutRetainsEvidenceRequirementAtNoToolOutcome(String request) {
        var messages = List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request));
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
        var plan = CurrentTurnPlan.create(contract, phase,
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of("talos.read_file", "talos.grep", "talos.retrieve"), List.of());

        // A fabricated candidate is passed directly into the real no-tool outcome boundary.
        // No provider is invoked and no current workspace or runtime evidence is supplied.
        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "The current workspace is verified complete and working correctly.",
                plan, messages, null, true);

        assertAll(
                () -> assertEquals(request, contract.originalUserRequest()),
                () -> assertEquals(TaskType.READ_ONLY_QA, contract.type()),
                () -> assertFalse(contract.mutationRequested()),
                () -> assertFalse(contract.mutationAllowed()),
                () -> assertTrue(contract.expectedTargets().isEmpty(), contract.toString()),
                () -> assertTrue(contract.sourceEvidenceTargets().isEmpty(), contract.toString()),
                () -> assertFalse(HistoricalFollowUpRequest.isPriorStatusRequest(request)),
                () -> assertFalse(HistoricalFollowUpRequest.isSessionUncertaintyRequest(request)),
                () -> assertFalse(HistoricalFollowUpRequest.isSessionEvidenceRequest(
                        request, contract.expectedTargets())),
                () -> assertEquals(EvidenceObligation.VERIFY_FROM_TRACE_OR_EVIDENCE,
                        EvidenceObligationPolicy.derive(contract, phase, workspace)),
                () -> assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus()),
                () -> assertEquals(TaskCompletionStatus.ADVISORY_ONLY,
                        outcome.taskOutcome().completionStatus()),
                () -> assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE)),
                () -> assertTrue(outcome.finalAnswer().startsWith(
                        "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"),
                        outcome.finalAnswer()),
                () -> assertFalse(outcome.toolLoopRan()),
                () -> assertFalse(outcome.mutationRequested()));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void compoundReadDoesNotAuthorizeScriptedMutation(Route route) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        String request = route.pureRequest + FRESH_READ;
        var fixture = fixture(
                "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"settings.properties\","
                        + "\"content\":\"retry_limit=9\\n\"}}",
                read("settings.properties"), SCRIPTED_ANSWER);

        Run run = execute(fixture, route, request, new NoOpApprovalGate());

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertOriginalRequest(fixture, request);
        assertFalse(run.audit().policyTrace().mutationAllowed(), run.audit().policyTrace().toString());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call ->
                        "talos.write_file".equals(call.name()) && call.success()),
                run.audit().toolCalls().toString());
        assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("settings.properties")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you make the changes? Explain why it is incomplete.",
            "What are you unsure about from this session? Explain why it is incomplete.",
            "Did you read settings.properties? Explain why it is incomplete."
    })
    void rejectedHistoricalAnswerCannotInheritPriorMutationAuthority(String request) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        var history = messages(Route.SESSION_META_EVIDENCE, request);
        assertTrue(TaskContractResolver.fromUserRequest(history.get(1).content()).mutationAllowed());
        TaskContract contract = TaskContractResolver.fromMessages(history);
        assertEquals(request, contract.originalUserRequest());
        assertFalse(contract.mutationRequested(), contract.toString());
        assertFalse(contract.mutationAllowed(), contract.toString());
        var fixture = fixture(
                "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"settings.properties\","
                        + "\"content\":\"retry_limit=9\\n\"}}",
                "The earlier operation remains incomplete.");

        Run run = execute(fixture, Route.SESSION_META_EVIDENCE, request, new NoOpApprovalGate());

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertOriginalRequest(fixture, request);
        assertFalse(run.audit().policyTrace().mutationAllowed(), run.audit().policyTrace().toString());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call ->
                "talos.write_file".equals(call.name()) && call.success()), run.audit().toolCalls().toString());
        assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("settings.properties")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you create any valid pdf or word in this audit?",
            "Did you create any valid PDF or Word  document in this audit?",
            "Have you created a valid Word\tdocument and PDF in this session?"
    })
    void documentStatusUsesEveryFormatFromNormalizedRequest(String request) {
        var fixture = fixture("Unexpected provider answer.");

        Run run = execute(fixture, Route.DOCUMENT_CREATION_STATUS, request, new NoOpApprovalGate());

        assertTrue(fixture.requests().isEmpty(), run.answer());
        assertTrue(run.audit().toolCalls().isEmpty());
        assertTrue(run.answer().contains("valid PDF or DOCX"), run.answer());
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void compoundReadPreservesProtectedApprovalDenial(Route route) throws Exception {
        Files.writeString(workspace.resolve("settings.properties"), CURRENT_CONTENT);
        Files.writeString(workspace.resolve(".env"), "SECRET=historical-admission-secret\n");
        String request = route.pureRequest + FRESH_READ + " Do not read protected files.";
        var fixture = fixture(read(".env"), read("settings.properties"), SCRIPTED_ANSWER);
        var approvalRequests = new AtomicInteger();

        Run run = execute(fixture, route, request, (description, detail) -> {
            approvalRequests.incrementAndGet();
            assertTrue(description.contains("protected read"), description);
            assertTrue(detail.contains(".env"), detail);
            return false;
        });

        assertFalse(fixture.requests().isEmpty(), run.answer());
        assertEquals(1, approvalRequests.get());
        assertEquals(1, run.audit().approvalsRequired());
        assertEquals(1, run.audit().approvalsDenied());
        assertEquals(0, run.audit().approvalsGranted());
        var protectedCalls = run.audit().toolCalls().stream().filter(call ->
                "talos.read_file".equals(call.name()) && ".env".equals(call.pathHint())).toList();
        assertEquals(1, protectedCalls.size(), run.audit().toolCalls().toString());
        assertFalse(protectedCalls.getFirst().success());
        assertEquals("approval denied by user for talos.read_file", protectedCalls.getFirst().reason());
        assertEquals(TurnRecord.ToolCallDisposition.BLOCKED,
                protectedCalls.getFirst().executionDisposition());
        assertEquals(dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED,
                protectedCalls.getFirst().failureReason());
        assertFalse(protectedCalls.getFirst().mutationEffects().hasEffects());
        assertTrue(run.audit().toolCalls().stream().noneMatch(call ->
                call.success() && ".env".equals(call.pathHint())), run.audit().toolCalls().toString());
        assertFalse(run.answer().contains("historical-admission-secret"), run.answer());
        assertTrue(fixture.requests().stream().flatMap(call -> call.messages.stream())
                .noneMatch(message -> message.content().contains("historical-admission-secret")));
    }

    private Run execute(ScriptedNativeLlmClient.RecordedClient fixture, Route route,
                        String request, ApprovalGate approvalGate) {
        return execute(fixture, route, request, approvalGate, List.of());
    }

    private Run execute(ScriptedNativeLlmClient.RecordedClient fixture, Route route,
                        String request, ApprovalGate approvalGate,
                        List<TurnRecord.ToolCallSummary> additionalPriorEvidence) {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        fixture.client().setToolSpecs(registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
        var memory = new SessionMemory();
        memory.setChangeSummaryContext(new ChangeSummaryContext(ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange("settings.properties", "talos.edit_file", 1,
                        "historical-admission-prior", "SUCCEEDED", "FAILED", "TASK_INCOMPLETE")),
                List.of(), "FAILED", "TASK_INCOMPLETE", List.of(PRIOR_FINDING)));
        memory.recordToolEvidence(1, List.of(
                new TurnRecord.ToolCallSummary("talos.edit_file", "settings.properties", true)));
        memory.recordToolEvidence(1, additionalPriorEvidence);
        var context = Context.builder(new Config(null)).memory(memory).llm(fixture.client())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, approvalGate, registry), 4))
                .build();
        LocalTurnTraceCapture.begin("historical-admission", "history-tests", 2,
                "2026-09-27T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", request);
        TurnAuditCapture.begin();
        try (var ownedClient = fixture.client()) {
            String answer = AssistantTurnExecutor.execute(messages(route, request), workspace, context,
                    new AssistantTurnExecutor.Options()).text();
            return new Run(answer, TurnAuditCapture.end());
        }
    }

    private static ArrayList<ChatMessage> messages(Route route, String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user("Update settings.properties to set retry_limit=3."));
        messages.add(ChatMessage.assistant("Partial verification: static checks failed. " + PRIOR_FINDING));
        if (route == Route.STATIC_WEB_DIAGNOSTIC) {
            messages.add(ChatMessage.user("Review the current static web page and its button."));
            messages.add(ChatMessage.assistant("""
                    I inspected the primary web files:
                    - HTML: `index.html`
                    - CSS: `styles.css`
                    - JavaScript: `script.js`

                    Static web diagnostics found:
                    - HTML does not link JavaScript file: `script.js`

                    No files were changed.
                    """));
        } else if (route == Route.DOCUMENT_CREATION_STATUS) {
            messages.add(ChatMessage.user("Create a PDF with instructions for configuring retries."));
            messages.add(ChatMessage.assistant(
                    "Talos cannot create valid PDF files with the current local text-file tool surface."));
        }
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static void assertOriginalRequest(ScriptedNativeLlmClient.RecordedClient fixture, String request) {
        assertTrue(fixture.requests().stream().flatMap(call -> call.messages.stream())
                .anyMatch(message -> "user".equals(message.role()) && request.equals(message.content())), request);
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
