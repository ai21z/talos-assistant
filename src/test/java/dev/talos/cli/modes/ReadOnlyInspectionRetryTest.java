package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyInspectionRetryTest {

    @TempDir
    Path lineageWorkspace;

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void retriesReadOnlyEvidenceRequestAndRunsToolLoop(@TempDir Path workspace) throws Exception {
        beginTrace("read-only-retry-receipt");
        Files.writeString(workspace.resolve("README.md"), "Workspace facts from README.\n");
        String request = "Explain this workspace.";
        String readJson =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
        var provider = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(
                        new LlmClient.StreamResult(readJson, List.of()),
                        new LlmClient.StreamResult("Answer from retry evidence.", List.of())),
                16_384);
        Context ctx = providerContext(workspace, provider.client());
        List<ChatMessage> messages = messages(request);
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        CurrentTurnPlan retryPlan = CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of(),
                List.of("talos.read_file"),
                List.of());
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot inspect from here.",
                messages,
                retryPlan,
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return TurnModelDispatcher.dispatchBuffered(
                            ctx, sentMessages, retryPlan);
                });

        assertNotNull(result.loopResult(), "retry tool calls should re-enter the tool loop");
        assertEquals("Answer from retry evidence.", result.answer());
        assertEquals(List.of("README.md"), result.loopResult().readPaths());
        assertTrue(result.extraSummary().contains("talos.read_file"), result.extraSummary());
        assertEquals(4, retryMessages.get().size(), "retry appends assistant answer and corrective user prompt");
        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Use read-only tools now."), prompt);
        assertTrue(prompt.contains("README.md"), prompt);
        assertTrue(prompt.contains("Do not call write_file or edit_file."), prompt);
        var trace = LocalTurnTraceCapture.complete();
        var parsed = trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .filter(event -> event.data().containsKey("providerGenerationId"))
                .findFirst().orElseThrow();
        var completed = trace.events().stream()
                .filter(event -> "GENERATION_COMPLETED".equals(event.type()))
                .filter(event -> event.data().get("generationId").equals(
                        parsed.data().get("providerGenerationId")))
                .findFirst().orElseThrow();
        assertEquals(completed.data().get("selectedAttemptId"),
                parsed.data().get("providerAttemptId"));
    }

    @Test
    void runtimeLegacyTextStopCannotAuthorizeReadOnlyRetryToolCall(
            @TempDir Path workspace) throws Exception {
        beginTrace("read-only-legacy-stop-rejected");
        Files.writeString(workspace.resolve("README.md"), "must not be read\n");
        String request = "Explain this workspace.";
        String readJson =
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
        Context ctx = context(workspace, "unused continuation");

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot inspect from here.",
                messages(request),
                plan(TaskContractResolver.fromUserRequest(request), ExecutionPhase.INSPECT),
                workspace,
                ctx,
                ignored -> legacyStoppedTextToolResult(readJson));

        assertNotNull(result.loopResult(), "the textual proposal should reach the guarded loop");
        assertEquals(List.of(), result.loopResult().readPaths());
        assertEquals(0, result.loopResult().toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::success)
                .count());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(trace.events().stream().noneMatch(event ->
                "TOOL_EXECUTED".equals(event.type())));
        TurnTraceEvent rejected = trace.events().stream()
                .filter(event -> "TOOL_CALL_AUTHORITY_REJECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        var authority = (java.util.Map<String, Object>)
                rejected.data().get("executionAuthority");
        assertEquals("REJECTED", authority.get("status"));
        assertEquals("PROVIDER_TOOL_MODE_MISMATCH", authority.get("reasonCode"));
    }

    @Test
    void directoryListingRetryKeepsListOnlyPrompt(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "Directory entries:\n- README.md");
        List<ChatMessage> messages = messages("List the top-level files only.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();
        TaskContract contract = new TaskContract(
                TaskType.DIRECTORY_LISTING,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "List the top-level files only.",
                "explicit-directory-listing-request");

        ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot inspect from here.",
                messages,
                plan(contract, ExecutionPhase.INSPECT),
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("No listing.", List.of());
                });

        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Task type: DIRECTORY_LISTING"), prompt);
        assertTrue(prompt.contains("Use talos.list_dir"), prompt);
        assertTrue(prompt.contains("Answer with file and directory names only."), prompt);
        assertFalse(prompt.contains("Use read-only tools now."), prompt);
    }

    @Test
    void verifyOnlyCommandRetryKeepsRunCommandPrompt(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "No command was run.")
                .withNativeToolSpecs(List.of(new ToolSpec("talos.run_command", "Run approved command", "{}")));
        List<ChatMessage> messages = messages("Run the approved Gradle check command profile.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Run the approved Gradle check command profile.");

        ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot verify that from here.",
                messages,
                plan(contract, ExecutionPhase.VERIFY),
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("No command was run.", List.of());
                });

        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Task type: VERIFY_ONLY"), prompt);
        assertTrue(prompt.contains("talos.run_command"), prompt);
        assertFalse(prompt.contains("talos.list_dir"), prompt);
        assertFalse(prompt.contains("Use read-only tools"), prompt);
    }

    @Test
    void nonWorkspaceEvidenceTaskDoesNotRetry(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "should not be used");
        List<ChatMessage> messages = messages("hello");

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                "Hi, I am Talos.",
                messages,
                plan(TaskContractResolver.fromUserRequest("hello"), ExecutionPhase.RESPOND),
                workspace,
                ctx,
                ignored -> {
                    throw new AssertionError("chat should not be called");
                });

        assertEquals("Hi, I am Talos.", result.answer());
        assertNull(result.loopResult());
        assertNull(result.extraSummary());
        assertEquals(2, messages.size(), "non-retry path must not append messages");
    }

    @ParameterizedTest(name = "{0} retry restores original read-only answer owner")
    @EnumSource(FallbackKind.class)
    void fallbackRetryRestoresOriginalReadOnlyAnswerOwner(FallbackKind fallbackKind) {
        beginTrace("read-only-inspection-" + fallbackKind.name().toLowerCase());
        String request = "Explain this workspace.";
        String answer = "I cannot inspect from here.";
        LlmClient.StreamResult originalProviderResult = providerTextResult(answer);

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                answer,
                messages(request),
                plan(TaskContractResolver.fromUserRequest(request), ExecutionPhase.INSPECT),
                lineageWorkspace,
                context(lineageWorkspace, "unused scripted answer"),
                ignored -> fallbackProviderResult(fallbackKind, answer));

        assertEquals(answer, result.answer(),
                "read-only fallback must preserve the original answer bytes");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                fallbackKind.restorationReason());
    }

    @Test
    void acceptedDifferentReadOnlyProviderRetryRetainsRetryOwnerWithoutRestoration() {
        beginTrace("read-only-inspection-accepted");
        String request = "Explain this workspace.";
        String answer = "I cannot inspect from here.";
        String accepted = "Grounded answer from the workspace evidence.";
        providerTextResult(answer);
        AtomicReference<ProviderResponseCapture.Receipt> retryReceipt = new AtomicReference<>();

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                answer,
                messages(request),
                plan(TaskContractResolver.fromUserRequest(request), ExecutionPhase.INSPECT),
                lineageWorkspace,
                context(lineageWorkspace, "unused scripted answer"),
                ignored -> {
                    LlmClient.StreamResult retry = providerTextResult(accepted);
                    retryReceipt.set(retry.provenance());
                    return retry;
                });

        assertEquals(accepted, result.answer(), "accepted retry bytes must remain unchanged");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.answer());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty(),
                "accepted retry must not restore the original candidate");
        assertTerminalProviderOwner(trace, retryReceipt.get());
    }

    private static CurrentTurnPlan plan(TaskContract contract, ExecutionPhase phase) {
        return CurrentTurnPlan.compatibility(
                contract,
                phase,
                List.of("talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of("talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of());
    }

    private static Context context(Path workspace, String finalAnswer) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(finalAnswer)))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .build();
    }

    private static Context providerContext(Path workspace, LlmClient llm) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .nativeToolSpecs(specsFrom(registry))
                .build();
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static List<ChatMessage> messages(String request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static LlmClient.StreamResult legacyStoppedTextToolResult(String text) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP,
                false,
                false,
                true));
        generation.complete(receipt);
        return new LlmClient.StreamResult(
                text, List.of(), "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP,
                false, false, receipt);
    }

    private static LlmClient.StreamResult providerTextResult(String text) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return new LlmClient.StreamResult(
                text, List.of(), "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false, false, receipt);
    }

    private static void beginTrace(String id) {
        LocalTurnTraceCapture.begin(
                id, "session", 1, "2026-07-16T00:00:00Z", "workspace",
                "auto", "test", "model", "retry receipt");
    }

    private enum FallbackKind {
        BLANK("SPECULATIVE_RETRY_EMPTY"),
        EQUAL("SPECULATIVE_RETRY_REJECTED"),
        EXCEPTION("SPECULATIVE_RETRY_FAILED");

        private final String restorationReason;

        FallbackKind(String restorationReason) {
            this.restorationReason = restorationReason;
        }

        String restorationReason() {
            return restorationReason;
        }
    }

    private static LlmClient.StreamResult fallbackProviderResult(
            FallbackKind fallbackKind,
            String original) {
        return switch (fallbackKind) {
            case BLANK -> providerTextResult("");
            case EQUAL -> providerTextResult(original);
            case EXCEPTION -> {
                failProviderGeneration();
                throw new IllegalStateException("injected read-only retry failure");
            }
        };
    }

    private static void failProviderGeneration() {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        generation.fail(ProviderResponseCapture.Failure.PROVIDER_EXCEPTION);
    }

    private static void assertRestoredProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt originalReceipt,
            String expectedReason) {
        List<TurnTraceEvent> restorations = events(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals(1, restorations.size(), "fallback must emit exactly one closed restoration");
        TurnTraceEvent restoration = restorations.getFirst();
        assertEquals("MODEL_PROVIDER", restoration.data().get("origin"));
        assertEquals(originalReceipt.generationId(), restoration.data().get("sourceGenerationId"));
        assertEquals(originalReceipt.attemptId(), restoration.data().get("sourceAttemptId"));
        assertEquals(expectedReason, restoration.data().get("reasonCode"));
        assertTrue(events(trace, "ANSWER_TRANSFORMED").isEmpty(),
                "exact original fallback bytes require no transform");
        assertTerminalProviderOwner(trace, originalReceipt);
    }

    private static void assertTerminalProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt receipt) {
        TurnTraceEvent terminal = events(trace, "TERMINAL_ANSWER_SELECTED").getFirst();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(receipt.generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(receipt.attemptId(), terminal.data().get("sourceAttemptId"));
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .toList();
    }
}
