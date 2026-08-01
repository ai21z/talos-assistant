package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.PromptToolDescriptor;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.llm.SystemPromptBuilder;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.CurrentTurnPromptInstructions;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.ProviderAuthorityTestTrace;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssistantTurnExecutor model dispatch characterization")
class AssistantTurnExecutorModelDispatchCharacterizationTest {

    @Test
    void nonStreamingMutationDispatchForwardsRequiredToolChoiceToProvider(@TempDir Path workspace) {
        ToolSpec writeFile = toolSpec("talos.write_file");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I updated result.txt.", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(writeFile))
                .build();

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Overwrite result.txt with exactly AFTER. Use talos.write_file."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertFalse(out.streamed(), "mutation dispatch must stay on the buffered provider path");
        ChatRequest request = firstRequest(recorded);
        assertEquals(List.of("talos.write_file"), request.tools.stream().map(ToolSpec::name).toList());
        assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        assertEquals(1024, request.controls.maxOutputTokens());
    }

    @Test
    void missingMutationEscalatedRetryUsesZeroTemperatureSampling(@TempDir Path workspace) {
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("The file has been updated.", List.of()),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_write",
                                "talos.write_file",
                                Map.of("path", "result.txt", "content", "AFTER")))),
                        new LlmClient.StreamResult("Updated result.txt.", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3))
                .nativeToolSpecs(specsFrom(registry))
                .build();

        AssistantTurnExecutor.execute(
                messages("Overwrite result.txt with exactly AFTER. Use talos.write_file."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(recorded.requests().size() >= 2, "missing mutation should dispatch an escalated retry");
        assertTrue(recorded.requests().stream().anyMatch(request ->
                        request.controls.sampling().temperature() != null
                                && request.controls.sampling().temperature() == 0.0),
                "escalated missing-mutation retry must dispatch with temperature 0.0");
    }

    @Test
    void postToolSynthesisRetryUsesTypedPurposeAndOriginalContract(
            @TempDir Path workspace
    ) {
        String request =
                "Read result.txt and report its exact contents. "
                        + "Do not modify files.";
        ToolSpec read = toolSpec("talos.read_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name()),
                List.of(read.name()),
                List.of());
        List<ChatMessage> turnMessages = new ArrayList<>(List.of(
                ChatMessage.system(systemPrompt(List.of(read))),
                ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                turnMessages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(turnMessages);
        CurrentTurnPlan projected =
                RetryDispatchPurpose.POST_TOOL_SYNTHESIS.project(plan);
        assertEquals(plan.taskContract(), projected.taskContract());
        assertEquals(plan.originalUserRequest(), projected.originalUserRequest());
        assertEquals(plan.taskExpectations(), projected.taskExpectations());
        assertEquals(plan.requestObligation(), projected.requestObligation());
        assertEquals(ActionObligation.NONE, projected.actionObligation());
        assertEquals("NONE", projected.evidenceObligation());
        assertTrue(projected.nativeTools().isEmpty());
        assertTrue(projected.promptTools().isEmpty());

        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("AFTER", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(read))
                .build();
        ToolCallLoop.LoopResult loopResult = new ToolCallLoop.LoopResult(
                "How can I help?",
                1,
                1,
                List.of("talos.read_file"),
                turnMessages,
                0,
                0,
                false,
                0,
                List.of("result.txt"),
                0,
                0,
                0,
                0);

        AssistantToolLoopOutcomeResolver.Resolution resolution =
                AssistantToolLoopOutcomeResolver.resolve(
                        "How can I help?",
                        turnMessages,
                        plan,
                        loopResult,
                        workspace,
                        ctx,
                        (ignoredLoop, ignoredSuccesses) -> {
                        },
                        (answer, ignoredLoop, ignoredSuccesses, ignoredFailure) ->
                                answer);

        assertEquals("AFTER", resolution.answer());
        assertEquals(1, recorded.requests().size());
        ChatRequest retry = recorded.requests().getFirst();
        assertEquals(ToolChoiceMode.AUTO, retry.controls.toolChoice());
        assertTrue(
                retry.controls.debugTags().contains(
                        "retry-purpose:POST_TOOL_SYNTHESIS"),
                retry.controls.toString());
        assertEquals(0, retry.controls.maxOutputTokens());
        assertEquals(originalMessages, turnMessages);
        assertEquals(originalMessages.size() + 2, retry.messages.size());
        assertEquals(
                capabilityFrameIndex(originalMessages),
                capabilityFrameIndex(retry.messages),
                "retry must replace the capability frame in place");
        assertEquals("assistant", retry.messages.get(
                originalMessages.size()).role());
        assertEquals("user", retry.messages.get(
                originalMessages.size() + 1).role());
        assertEquals(
                "How can I help?",
                retry.messages.get(originalMessages.size()).content());
        assertTrue(
                retry.messages.get(originalMessages.size() + 1)
                        .content()
                        .startsWith(
                                "The user's original request was:"));
        String providerPrompt = retry.messages.stream()
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(providerPrompt.contains("Available Tools"), providerPrompt);
        assertFalse(providerPrompt.contains(read.name()), providerPrompt);
        assertTrue(
                soleCapabilityFrame(retry.messages)
                        .contains("visibleTools: (none)"),
                soleCapabilityFrame(retry.messages));
    }

    @Test
    void retryReplacesStaleCapabilityFrameWithoutMutatingOriginalMessages(
            @TempDir Path workspace
    ) {
        String request = "Edit result.txt with the required change.";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("base"),
                ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("done", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(toolSpec("talos.write_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-capability-retry",
                "session-t1196-capability-retry",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            TurnModelDispatcher.dispatchBufferedRetry(
                    ctx,
                    messages,
                    plan,
                    RetryDispatchPurpose.POST_TOOL_SYNTHESIS);

            assertEquals(originalMessages, messages);
            ChatRequest retry = recorded.requests().getFirst();
            assertTrue(retry.tools.isEmpty());
            List<String> capabilityFrames = retry.messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(ChatMessage::content)
                    .filter(java.util.Objects::nonNull)
                    .filter(content -> content.startsWith(
                            "[CurrentTurnCapability]"))
                    .toList();
            assertEquals(1, capabilityFrames.size());
            String frame = capabilityFrames.getFirst();
            assertTrue(frame.contains("visibleTools: (none)"), frame);
            assertTrue(frame.contains("obligation: NONE"), frame);
            assertFalse(frame.contains("MUTATING_TOOL_REQUIRED"), frame);
            assertFalse(frame.contains("talos.write_file"), frame);
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void escalatedRetryRejectsMissingOrExcessAuthorityBeforeStageMutation(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt.";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of("talos.write_file"));
        ToolSpec read = toolSpec("talos.read_file");
        ToolSpec write = toolSpec("talos.write_file");
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("unused", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(read, write))
                .build();
        List<ChatMessage> messages =
                List.of(ChatMessage.user(request));
        LocalTurnTraceCapture.begin(
                "trace-t1196-escalated-retry-rejection",
                "session-t1196-escalated-retry-rejection",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LocalTurnTraceCapture.enterExecutionStage(
                    TurnExecutionStage.VERIFICATION);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchEscalatedRetry(
                            ctx,
                            messages,
                            null,
                            List.of(read),
                            RetryDispatchPurpose.MISSING_MUTATION));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchEscalatedRetry(
                            null,
                            messages,
                            plan,
                            List.of(read),
                            RetryDispatchPurpose.MISSING_MUTATION));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchEscalatedRetry(
                            ctx,
                            messages,
                            plan,
                            null,
                            RetryDispatchPurpose.MISSING_MUTATION));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchEscalatedRetry(
                            ctx,
                            messages,
                            plan,
                            List.of(write),
                            RetryDispatchPurpose.MISSING_MUTATION));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchEscalatedRetry(
                            ctx,
                            messages,
                            plan,
                            List.of(read),
                            null));

            assertTrue(recorded.requests().isEmpty());
            assertEquals(
                    TurnExecutionStage.VERIFICATION,
                    LocalTurnTraceCapture.currentExecutionStage());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void escalatedRetryProjectsOneExactSubsetAcrossFrameBodyAndReceipt(
            @TempDir Path workspace
    ) {
        String request = "Edit result.txt with the required change.";
        ToolSpec write = toolSpec("talos.write_file");
        ToolSpec edit = toolSpec("talos.edit_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of(write.name(), edit.name()),
                List.of(write.name(), edit.name()),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(
                List.of(ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("done", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(write, edit))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-escalated-retry-subset",
                "session-t1196-escalated-retry-subset",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchEscalatedRetry(
                            ctx,
                            messages,
                            plan,
                            List.of(edit),
                            RetryDispatchPurpose.MISSING_MUTATION);

            ChatRequest retry = recorded.requests().getFirst();
            assertEquals(originalMessages, messages);
            assertEquals(
                    List.of(edit.name()),
                    retry.tools.stream().map(ToolSpec::name).toList());
            assertEquals(
                    List.of(edit.name()),
                    result.provenance().toolAuthorityContract()
                            .selectedToolNames());
            assertEquals(
                    ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    retry.controls.toolAuthorityMode());
            assertTrue(
                    retry.controls.debugTags().contains(
                            "retry-purpose:MISSING_MUTATION"),
                    retry.controls.toString());
            assertTrue(
                    soleCapabilityFrame(retry.messages)
                            .contains("visibleTools: talos.edit_file"),
                    soleCapabilityFrame(retry.messages));
            assertFalse(
                    soleCapabilityFrame(retry.messages)
                            .contains("talos.write_file"),
                    soleCapabilityFrame(retry.messages));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void blockedToolOverlapCannotReenterBufferedRetryAuthority(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt.";
        CurrentTurnPlan base = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());
        CurrentTurnPlan blocked = new CurrentTurnPlan(
                base.taskContract(),
                base.originalUserRequest(),
                base.phaseInitial(),
                base.phaseFinal(),
                base.actionObligation(),
                base.taskExpectations(),
                base.nativeTools(),
                base.promptTools(),
                List.of("talos.read_file"),
                base.evidenceObligation(),
                base.outputObligation(),
                base.activeTaskContext(),
                base.artifactGoal(),
                base.verifierProfile(),
                base.requestObligation());
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("safe", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(toolSpec("talos.read_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-blocked-retry-tool",
                "session-t1196-blocked-retry-tool",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchBufferedRetry(
                            ctx,
                            List.of(ChatMessage.user(request)),
                            blocked,
                            RetryDispatchPurpose.READ_ONLY_INSPECTION);

            assertEquals(1, recorded.requests().size());
            assertTrue(recorded.requests().getFirst().tools.isEmpty());
            assertTrue(
                    result.provenance()
                            .toolAuthorityContract()
                            .selectedToolNames()
                            .isEmpty());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void retryWithoutCapturedPlanFailsBeforeDispatchOrStageMutation(
            @TempDir Path workspace
    ) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("must not dispatch", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(toolSpec("talos.read_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-null-retry-plan",
                "session-t1196-null-retry-plan",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                "Read safe.txt.");
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.VERIFICATION);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchBufferedRetry(
                            ctx,
                            List.of(ChatMessage.user(
                                    "Synthetic retry: edit evil.txt.")),
                            null,
                            RetryDispatchPurpose.READ_ONLY_INSPECTION));
            assertTrue(recorded.requests().isEmpty());
            assertEquals(
                    TurnExecutionStage.VERIFICATION,
                    LocalTurnTraceCapture.currentExecutionStage());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void retryWithoutTypedPurposeFailsBeforeDispatchOrStageMutation(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt.";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of(),
                List.of());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("must not dispatch", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(toolSpec("talos.read_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-null-retry-purpose",
                "session-t1196-null-retry-purpose",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.VERIFICATION);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TurnModelDispatcher.dispatchBufferedRetry(
                            ctx,
                            List.of(ChatMessage.user(request)),
                            plan,
                            null));
            assertTrue(recorded.requests().isEmpty());
            assertEquals(
                    TurnExecutionStage.VERIFICATION,
                    LocalTurnTraceCapture.currentExecutionStage());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void retryIntersectsProviderTextToolsWithCapturedPromptSurface(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt and report its contents.";
        List<ChatMessage> messages = List.of(ChatMessage.user(request));
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(),
                List.of("talos.read_file"),
                List.of("talos.write_file"));
        var recorded =
                ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("safe", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(
                        toolSpec("talos.read_file"),
                        toolSpec("talos.write_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-retry-tool-intersection",
                "session-t1196-retry-tool-intersection",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchBufferedRetry(
                            ctx,
                            messages,
                            plan,
                            RetryDispatchPurpose.READ_ONLY_INSPECTION);

            assertEquals(1, recorded.requests().size());
            ChatRequest retry = recorded.requests().getFirst();
            assertTrue(
                    retry.tools.isEmpty(),
                    "JSON-text tool authority is represented by the typed receipt, "
                            + "not a native transport surface");
            assertEquals(
                    List.of("talos.read_file"),
                    result.provenance()
                            .toolAuthorityContract()
                            .selectedToolNames(),
                    "source=" + result.provenance().source()
                            + ", receipt="
                            + result.provenance().toolAuthorityContract()
                            + ", controls=" + retry.controls);
            assertEquals(
                    ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                    retry.controls.toolAuthorityMode());
            String serializedPrompt = retry.messages.stream()
                    .map(ChatMessage::content)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(
                    List.of("visibleTools: talos.read_file"),
                    serializedPrompt.lines()
                            .filter(line -> line.startsWith("visibleTools: "))
                            .toList(),
                    serializedPrompt);
            assertFalse(
                    serializedPrompt.contains(
                            "Available mutating tools: talos.write_file"),
                    serializedPrompt);
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void retryIntersectsNativeProviderBodyAndReceiptToCapturedSurface(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt and report its contents.";
        List<ChatMessage> messages = List.of(ChatMessage.user(request));
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of(),
                List.of("talos.write_file"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("safe", List.of())),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(
                        toolSpec("talos.read_file"),
                        toolSpec("talos.write_file")))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-native-retry-tool-intersection",
                "session-t1196-native-retry-tool-intersection",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchBufferedRetry(
                            ctx,
                            messages,
                            plan,
                            RetryDispatchPurpose.READ_ONLY_INSPECTION);

            assertEquals(1, recorded.requests().size());
            ChatRequest retry = recorded.requests().getFirst();
            assertEquals(
                    List.of("talos.read_file"),
                    retry.tools.stream().map(ToolSpec::name).toList());
            assertEquals(
                    List.of("talos.read_file"),
                    result.provenance()
                            .toolAuthorityContract()
                            .selectedToolNames());
            assertEquals(
                    ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    retry.controls.toolAuthorityMode());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void inspectionRetryPurposesStripMutationAuthorityAcrossBodyFrameControlsAndReceipt(
            @TempDir Path workspace
    ) {
        String request =
                "Inspect README.md, then edit it if needed.";
        ToolSpec read = toolSpec("talos.read_file");
        ToolSpec write = toolSpec("talos.write_file");
        ToolSpec edit = toolSpec("talos.edit_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of(read.name(), write.name(), edit.name()),
                List.of(read.name(), write.name(), edit.name()),
                List.of());
        List<ChatMessage> messages =
                new ArrayList<>(List.of(
                        ChatMessage.system(
                                systemPrompt(List.of(read, write, edit))),
                        ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(
                                new LlmClient.StreamResult("inspected", List.of()),
                                new LlmClient.StreamResult("inspected", List.of()),
                                new LlmClient.StreamResult("inspected", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(read, write, edit))
                .build();
        LocalTurnTraceCapture.begin(
                "trace-t1196-inspection-retry-surface",
                "session-t1196-inspection-retry-surface",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            List<RetryDispatchPurpose> purposes = List.of(
                    RetryDispatchPurpose.INSPECT_COMPLETENESS,
                    RetryDispatchPurpose.READ_ONLY_INSPECTION,
                    RetryDispatchPurpose.NO_TOOL_GROUNDING);
            for (RetryDispatchPurpose purpose : purposes) {
                CurrentTurnPlan projected = purpose.project(plan);
                assertEquals(
                        plan.taskContract(),
                        projected.taskContract(),
                        "the original contract remains provenance");
                assertEquals(
                        ExecutionPhase.INSPECT,
                        projected.phaseInitial());
                assertEquals(
                        ExecutionPhase.INSPECT,
                        projected.phaseFinal());
            }
            List<LlmClient.StreamResult> results = new ArrayList<>();
            for (RetryDispatchPurpose purpose : purposes) {
                results.add(TurnModelDispatcher.dispatchBufferedRetry(
                        ctx,
                        messages,
                        plan,
                        purpose));
            }

            assertEquals(originalMessages, messages);
            assertEquals(purposes.size(), recorded.requests().size());
            for (int index = 0; index < purposes.size(); index++) {
                RetryDispatchPurpose purpose = purposes.get(index);
                ChatRequest retry = recorded.requests().get(index);
                assertEquals(
                        List.of(read.name()),
                        retry.tools.stream().map(ToolSpec::name).toList());
                String frame = soleCapabilityFrame(retry.messages);
                assertTrue(
                        frame.contains("obligation: INSPECT_REQUIRED"),
                        frame);
                assertTrue(frame.contains("phase: INSPECT"), frame);
                assertTrue(
                        frame.contains("mutationAllowed: false"),
                        frame);
                assertTrue(
                        frame.contains("visibleTools: talos.read_file"),
                        frame);
                assertFalse(frame.contains(write.name()), frame);
                assertFalse(frame.contains(edit.name()), frame);
                String body = retry.messages.stream()
                        .map(ChatMessage::content)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("\n"));
                assertFalse(body.contains(write.name()), body);
                assertFalse(body.contains(edit.name()), body);
                assertEquals(ToolChoiceMode.REQUIRED, retry.controls.toolChoice());
                assertTrue(
                        retry.controls.debugTags().contains(
                                purpose.debugTag()),
                        retry.controls.toString());
                assertEquals(
                        List.of(read.name()),
                        results.get(index).provenance()
                                .toolAuthorityContract()
                                .selectedToolNames());
            }
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void inheritedProviderSpecsAreBoundedByCapturedPlan(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt.";
        ToolSpec read = toolSpec("talos.read_file");
        ToolSpec write = toolSpec("talos.write_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name()),
                List.of(read.name()),
                List.of(write.name()));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("safe", List.of())),
                4096);
        recorded.client().setToolSpecs(List.of(read, write));
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        TurnModelDispatcher.dispatchBuffered(
                ctx,
                List.of(ChatMessage.user(request)),
                plan);

        ChatRequest providerRequest = recorded.requests().getFirst();
        assertEquals(
                List.of(read.name()),
                providerRequest.tools.stream()
                        .map(ToolSpec::name)
                        .toList());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                providerRequest.controls.toolAuthorityMode());
    }

    @Test
    void disappearedCurrentToolIsRemovedFromFrameBodyAndReceipt(
            @TempDir Path workspace
    ) {
        String request = "Inspect safe.txt.";
        ToolSpec read = toolSpec("talos.read_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name(), "talos.grep"),
                List.of(read.name(), "talos.grep"),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(
                List.of(ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("safe", List.of())),
                        4096);
        recorded.client().setToolSpecs(List.of(read));
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trace-t1196-disappeared-current-tool",
                "session-t1196-disappeared-current-tool",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchBuffered(
                            ctx,
                            messages,
                            plan);

            ChatRequest providerRequest = recorded.requests().getFirst();
            assertEquals(originalMessages, messages);
            assertEquals(
                    List.of(read.name()),
                    providerRequest.tools.stream()
                            .map(ToolSpec::name).toList());
            assertEquals(
                    List.of(read.name()),
                    result.provenance().toolAuthorityContract()
                            .selectedToolNames());
            assertTrue(
                    soleCapabilityFrame(providerRequest.messages)
                            .contains("visibleTools: talos.read_file"),
                    soleCapabilityFrame(providerRequest.messages));
            assertFalse(
                    soleCapabilityFrame(providerRequest.messages)
                            .contains("talos.grep"),
                    soleCapabilityFrame(providerRequest.messages));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedTimeoutAndStreamingDispatchShareOneNarrowedAuthoritySurface(
            @TempDir Path workspace
    ) throws Exception {
        String request = "Inspect safe.txt.";
        ToolSpec read = toolSpec("talos.read_file");
        ToolSpec grep = toolSpec("talos.grep");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name(), grep.name()),
                List.of(read.name(), grep.name()),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(
                List.of(ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(
                                new LlmClient.StreamResult("buffered", List.of()),
                                new LlmClient.StreamResult("timeout", List.of()),
                                new LlmClient.StreamResult("streaming", List.of())),
                        4096);
        recorded.client().setToolSpecs(List.of(read));
        List<String> streamedChunks = new ArrayList<>();
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .streamSink(streamedChunks::add)
                .build();

        LocalTurnTraceCapture.begin(
                "trace-t1196-dispatch-parity",
                "session-t1196-dispatch-parity",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult buffered =
                    TurnModelDispatcher.dispatchBuffered(
                            ctx, messages, plan);
            LlmClient.StreamResult timeout =
                    TurnModelDispatcher.dispatchBufferedWithTimeout(
                            ctx,
                            messages,
                            plan,
                            30_000L);
            LlmClient.StreamResult streaming =
                    TurnModelDispatcher.dispatchStreaming(
                            ctx, messages, plan);

            assertEquals(originalMessages, messages);
            assertEquals(3, recorded.requests().size());
            List<ChatRequest> requests =
                    List.copyOf(recorded.requests());
            List<String> capabilityFrames = requests.stream()
                    .map(providerRequest ->
                            soleCapabilityFrame(
                                    providerRequest.messages))
                    .toList();
            assertEquals(
                    List.of(
                            capabilityFrames.getFirst(),
                            capabilityFrames.getFirst(),
                            capabilityFrames.getFirst()),
                    capabilityFrames);
            for (ChatRequest providerRequest : requests) {
                assertEquals(
                        List.of(read.name()),
                        providerRequest.tools.stream()
                                .map(ToolSpec::name)
                                .toList());
                assertEquals(
                        List.of("visibleTools: talos.read_file"),
                        soleCapabilityFrame(
                                providerRequest.messages).lines()
                                .filter(line -> line.startsWith(
                                        "visibleTools: "))
                                .toList());
                assertFalse(
                        soleCapabilityFrame(providerRequest.messages)
                                .contains(grep.name()),
                        soleCapabilityFrame(
                                providerRequest.messages));
            }

            assertEquals(
                    requests.get(0).controls,
                    requests.get(1).controls);
            assertEquals(
                    requests.get(0).controls,
                    requests.get(2).controls);
            assertEquals(
                    List.of(
                            "action-obligation:"
                                    + plan.actionObligation().name()),
                    requests.getFirst().controls.debugTags().stream()
                            .filter(tag -> tag.startsWith(
                                    "action-obligation:"))
                            .toList());

            ProviderResponseCapture.ToolAuthorityContract
                    expectedAuthority =
                            buffered.provenance()
                                    .toolAuthorityContract();
            assertEquals(
                    ProviderResponseCapture.ToolAuthorityMode
                            .PROVIDER_NATIVE,
                    expectedAuthority.mode());
            assertEquals(
                    List.of(read.name()),
                    expectedAuthority.selectedToolNames());
            assertEquals(
                    expectedAuthority,
                    timeout.provenance()
                            .toolAuthorityContract());
            assertEquals(
                    expectedAuthority,
                    streaming.provenance()
                            .toolAuthorityContract());
            assertEquals(List.of("streaming"), streamedChunks);
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void promptOnlyNameCannotBePromotedIntoNativeProviderAuthority(
            @TempDir Path workspace
    ) {
        String request = "Inspect safe.txt.";
        ToolSpec read = toolSpec("talos.read_file");
        ToolSpec retrieve = toolSpec("talos.retrieve");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name()),
                List.of(retrieve.name()),
                List.of());
        List<ChatMessage> messages = new ArrayList<>(
                List.of(ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("safe", List.of())),
                        4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of(read, retrieve))
                .build();

        LocalTurnTraceCapture.begin(
                "trace-t1196-native-prompt-precedence",
                "session-t1196-native-prompt-precedence",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "assistant",
                "test",
                "model",
                request);
        try {
            LlmClient.StreamResult result =
                    TurnModelDispatcher.dispatchBuffered(
                            ctx,
                            messages,
                            plan);

            ChatRequest providerRequest = recorded.requests().getFirst();
            assertEquals(originalMessages, messages);
            assertEquals(
                    List.of(read.name()),
                    providerRequest.tools.stream()
                            .map(ToolSpec::name).toList());
            assertEquals(
                    List.of(read.name()),
                    result.provenance().toolAuthorityContract()
                            .selectedToolNames());
            assertEquals(
                    ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    providerRequest.controls.toolAuthorityMode());
            assertEquals(
                    List.of("visibleTools: talos.read_file"),
                    soleCapabilityFrame(providerRequest.messages).lines()
                            .filter(line -> line.startsWith("visibleTools: "))
                            .toList());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void explicitEmptyContextSurfaceDoesNotInheritProviderSpecs(
            @TempDir Path workspace
    ) {
        String request = "Read safe.txt.";
        ToolSpec read = toolSpec("talos.read_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.INSPECT,
                List.of(read.name()),
                List.of(read.name()),
                List.of());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("safe", List.of())),
                4096);
        recorded.client().setToolSpecs(List.of(read));
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .nativeToolSpecs(List.of())
                .build();

        List<ChatMessage> messages = new ArrayList<>(
                List.of(ChatMessage.user(request)));
        CurrentTurnPromptInstructions.replaceTaskContractInstruction(
                messages,
                plan);
        List<ChatMessage> originalMessages = List.copyOf(messages);
        LlmClient.StreamResult result = TurnModelDispatcher.dispatchBuffered(
                ctx,
                messages,
                plan);

        ChatRequest providerRequest = recorded.requests().getFirst();
        assertEquals(originalMessages, messages);
        assertTrue(providerRequest.tools.isEmpty());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                providerRequest.controls.toolAuthorityMode());
        assertTrue(
                result.provenance()
                        .toolAuthorityContract()
                        .selectedToolNames()
                        .isEmpty());
        assertEquals(
                List.of("visibleTools: (none)"),
                soleCapabilityFrame(providerRequest.messages).lines()
                        .filter(line -> line.startsWith("visibleTools: "))
                        .toList());
    }

    @Test
    void streamingAndBufferedNoToolDispatchCharacterizesFinalAnswerShape(@TempDir Path workspace) {
        String answer = "Dispatch OK.";
        var streamed = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())),
                4096);
        var buffered = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())),
                4096);
        var chunks = new ArrayList<String>();

        AssistantTurnExecutor.TurnOutput streamingOut = AssistantTurnExecutor.execute(
                messages("Answer briefly with exactly Dispatch OK."),
                workspace,
                Context.builder(new Config())
                        .llm(streamed.client())
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .streamSink(chunks::add)
                        .onStreamComplete(() -> {})
                        .build(),
                new AssistantTurnExecutor.Options());
        AssistantTurnExecutor.TurnOutput bufferedOut = AssistantTurnExecutor.execute(
                messages("Answer briefly with exactly Dispatch OK."),
                workspace,
                Context.builder(new Config())
                        .llm(buffered.client())
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .build(),
                new AssistantTurnExecutor.Options());

        assertTrue(streamingOut.streamed(), "stream sink should select the streaming provider path");
        assertFalse(bufferedOut.streamed(), "missing stream sink should select the buffered provider path");
        assertEquals(bufferedOut.text(), streamingOut.text());
        assertEquals(answer, streamingOut.text());
    }

    @Test
    void toolOnlyStreamingResponseInvokesOnStreamCompleteOnceBeforeToolLoop(@TempDir Path workspace) {
        ProviderAuthorityTestTrace.begin("model-dispatch-stream", null);
        try {
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger completionCountAtToolExecution = new AtomicInteger(-1);
        AtomicInteger toolExecutions = new AtomicInteger();
        var registry = new ToolRegistry();
        registry.register(new CompletionAwareReadFileTool(completions, completionCountAtToolExecution, toolExecutions));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_read",
                                "talos.read_file",
                                Map.of("path", "notes.txt")))),
                        new LlmClient.StreamResult("Read complete.", List.of())),
                4096);
        var chunks = new ArrayList<String>();
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3))
                .nativeToolSpecs(specsFrom(registry))
                .streamSink(chunks::add)
                .onStreamComplete(completions::incrementAndGet)
                .build();

        AssistantTurnExecutor.execute(
                messages("Read notes.txt and summarize it."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals(1, completions.get(), "tool-only streaming response should complete the stream once");
        assertEquals(1, toolExecutions.get(), "native tool call should enter the tool loop");
        assertEquals(1, completionCountAtToolExecution.get(),
                "stream completion should happen before tool execution starts");
        assertEquals(512, firstRequest(recorded).controls.maxOutputTokens());
        } finally {
            ProviderAuthorityTestTrace.clear();
        }
    }

    @Test
    void lengthLimitedNoToolAnswerAddsVisibleWarningAndTrace(@TempDir Path workspace) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Partial answer", List.of(), "length")),
                4096);
        var ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-t989-length",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Explain briefly.");
        try {
            AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                    messages("Explain briefly."),
                    workspace,
                    ctx,
                    new AssistantTurnExecutor.Options());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(out.text().contains("Partial answer"), out.text());
            assertTrue(out.text().contains("output limit"), out.text());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "LLM_OUTPUT_LIMIT_REACHED".equals(warning.code())
                            && warning.message().contains("finish_reason=length")));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void bufferedAbortMarkerRecordsFailedOutcome(@TempDir Path workspace) {
        var blocking = ScriptedNativeLlmClient.blockingAfterFirstChunk(5_000L);
        var ctx = Context.builder(new Config())
                .llm(blocking.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        LocalTurnTraceCapture.begin(
                "trc-t988-abort",
                "sid",
                1,
                "2026-07-09T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "test-model",
                "Answer slowly.");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    AssistantTurnExecutor.execute(
                            messages("Answer slowly."),
                            workspace,
                            ctx,
                            new AssistantTurnExecutor.Options().llmTimeoutMs(150L)));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("LLM_ABORTED", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void directProviderCancellationPropagatesUnchanged(@TempDir Path workspace) {
        CancellationException cancellation = new CancellationException("stop provider request");
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scriptedFailure(cancellation))
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> AssistantTurnExecutor.execute(
                        messages("Answer briefly with the number four."),
                        workspace,
                        ctx,
                        new AssistantTurnExecutor.Options()));

        assertSame(cancellation, thrown);
    }

    @Test
    void genericWrappedProviderCancellationPropagatesUnchanged(@TempDir Path workspace) {
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("stop provider request"));
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scriptedFailure(wrapped))
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> AssistantTurnExecutor.execute(
                        messages("Answer briefly with the number four."),
                        workspace,
                        ctx,
                        new AssistantTurnExecutor.Options()));

        assertSame(wrapped, thrown);
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolSpec toolSpec(String name) {
        return new ToolSpec(
                name,
                "Characterization test tool",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}");
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static ChatRequest firstRequest(ScriptedNativeLlmClient.RecordedClient recorded) {
        assertFalse(recorded.requests().isEmpty(), "provider request should be recorded");
        return recorded.requests().getFirst();
    }

    private static String systemPrompt(List<ToolSpec> tools) {
        List<PromptToolDescriptor> descriptors = tools.stream()
                .map(tool -> new PromptToolDescriptor(
                        tool.name(),
                        tool.description(),
                        tool.parametersSchemaJson(),
                        false))
                .toList();
        return SystemPromptBuilder.forUnified()
                .withNativeTools(true)
                .withPromptTools(descriptors)
                .withVisibleToolNames(
                        tools.stream().map(ToolSpec::name).toList())
                .build();
    }

    private static String soleCapabilityFrame(List<ChatMessage> messages) {
        List<String> frames = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .filter(java.util.Objects::nonNull)
                .filter(content -> content.startsWith(
                        "[CurrentTurnCapability]"))
                .toList();
        assertEquals(1, frames.size(), frames.toString());
        return frames.getFirst();
    }

    private static int capabilityFrameIndex(
            List<ChatMessage> messages
    ) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message != null
                    && "system".equals(message.role())
                    && message.content() != null
                    && message.content().startsWith(
                            "[CurrentTurnCapability]")) {
                return i;
            }
        }
        return -1;
    }

    private record CompletionAwareReadFileTool(
            AtomicInteger completions,
            AtomicInteger completionCountAtToolExecution,
            AtomicInteger toolExecutions
    ) implements TalosTool {
        @Override
        public String name() {
            return "talos.read_file";
        }

        @Override
        public String description() {
            return "Read a file for model-dispatch characterization.";
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name(),
                    description(),
                    "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                    ToolRiskLevel.READ_ONLY);
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            completionCountAtToolExecution.set(completions.get());
            toolExecutions.incrementAndGet();
            return ToolResult.ok("notes.txt contents");
        }
    }
}
