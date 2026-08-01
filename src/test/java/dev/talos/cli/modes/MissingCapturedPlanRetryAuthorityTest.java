package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingCapturedPlanRetryAuthorityTest {

    @AfterEach
    void clearTurnCapture() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void synthesisRetryWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages("Explain the files you inspected.");

        assertMissingPlanRejected(probe, messages, () ->
                AssistantTurnExecutor.synthesisRetryIfNeeded(
                        "How can I help?", 1, messages, probe.context()));
    }

    @Test
    void mutationRetryWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages(
                "Create result.txt with exactly DONE.");

        assertMissingPlanRejected(probe, messages, () ->
                AssistantTurnExecutor.mutationRequestRetryIfNeeded(
                        "I can help with that.",
                        messages,
                        loopResult("I can help with that.", messages, 0),
                        workspace,
                        probe.context()));
    }

    @Test
    void inspectCompletenessRetryWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>fixture</main>\n");
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages(
                "Inspect this workspace and explain the main files.");

        assertMissingPlanRejected(probe, messages, () ->
                AssistantTurnExecutor.inspectCompletenessRetryIfNeeded(
                        "I listed the directory.",
                        messages,
                        loopResult("I listed the directory.", messages, 1),
                        workspace,
                        probe.context()));
    }

    @Test
    void groundingRetryWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages(
                "Read the workspace files and explain what they contain.");
        String ungrounded = "Unverified workspace description. ".repeat(20);

        assertMissingPlanRejected(probe, messages, () ->
                AssistantTurnExecutor.groundingRetryIfNeeded(
                        ungrounded, messages, probe.context()));
    }

    @Test
    void toolLoopOutcomeWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages("Explain the file you read.");

        assertMissingPlanRejected(probe, messages, () ->
                AssistantToolLoopOutcomeResolver.resolve(
                        "How can I help?",
                        messages,
                        null,
                        loopResult("How can I help?", messages, 1),
                        workspace,
                        probe.context(),
                        (ignoredLoop, ignoredSuccesses) -> {
                            throw new AssertionError("phase transition must not run");
                        },
                        (answer, ignoredLoop, ignoredSuccesses, ignoredFailure) -> answer));
    }

    @Test
    void noToolOutcomeWithoutCapturedPlanCannotDispatchOrMutateCallerState(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages(
                "Create result.txt with exactly DONE.");

        assertMissingPlanRejected(probe, messages, () ->
                AssistantNoToolOutcomeResolver.resolve(
                        "I can help with that.",
                        messages,
                        null,
                        workspace,
                        probe.context(),
                        (ignoredLoop, ignoredSuccesses) -> {
                            throw new AssertionError("phase transition must not run");
                        },
                        (answer, ignoredLoop, ignoredSuccesses, ignoredFailure) -> answer,
                        (answer, ignoredFailure) -> answer));
    }

    @Test
    void noToolExecutionOutcomeWithoutCapturedPlanKeepsDeterministicShapingButSkipsGroundingDispatch(
            @TempDir Path workspace) {
        Probe probe = probe(workspace);
        List<ChatMessage> messages = messages(
                "Read README.md and summarize it.");
        List<ChatMessage> originalMessages = List.copyOf(messages);
        String ungrounded = "README.md describes an unverified project. ".repeat(20);
        beginAtVerificationStage();

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                ungrounded, messages, probe.context(), false);

        assertEquals("""
                [Evidence incomplete: required workspace evidence was not gathered in this turn.]

                I did not inspect the required workspace target this turn, so I cannot answer from its contents or propose grounded changes yet. Required target(s): README.md.""",
                outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY,
                outcome.completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN,
                outcome.groundingStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN,
                outcome.verificationStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY,
                outcome.taskOutcome().completionStatus());
        assertEquals(List.of(TruthWarning.of(
                        TruthWarningType.MISSING_EVIDENCE,
                        "Required workspace evidence was not gathered in this turn.")),
                outcome.taskOutcome().warnings());
        assertTrue(probe.provider().requests().isEmpty(),
                "display-only compatibility projection must not authorize grounding dispatch");
        assertEquals(TurnExecutionStage.VERIFICATION,
                LocalTurnTraceCapture.currentExecutionStage());
        assertEquals(originalMessages, messages,
                "display-only shaping must not append a synthetic retry pair");
    }

    private static void assertMissingPlanRejected(
            Probe probe,
            List<ChatMessage> messages,
            Executable action
    ) {
        List<ChatMessage> originalMessages = List.copyOf(messages);
        beginAtVerificationStage();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                action);

        assertEquals("captured retry plan is required", failure.getMessage());
        assertTrue(probe.provider().requests().isEmpty(),
                "missing captured plan must fail before provider dispatch");
        assertEquals(TurnExecutionStage.VERIFICATION,
                LocalTurnTraceCapture.currentExecutionStage(),
                "missing captured plan must not advance the execution stage");
        assertEquals(originalMessages, messages,
                "missing captured plan must not mutate caller-owned messages");
    }

    private static void beginAtVerificationStage() {
        LocalTurnTraceCapture.begin(
                "missing-plan-retry",
                "session",
                1,
                "2026-07-31T00:00:00Z",
                "workspace",
                "assistant",
                "test",
                "model",
                "missing captured retry plan");
        LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.VERIFICATION);
    }

    private static Probe probe(Path workspace) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("must not dispatch", List.of())),
                16_384);
        Context context = Context.builder(new Config())
                .llm(provider.client())
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(
                        new TurnProcessor(null, new NoOpApprovalGate(), registry),
                        5))
                .nativeToolSpecs(registry.descriptors().stream()
                        .map(descriptor -> new ToolSpec(
                                descriptor.name(),
                                descriptor.description(),
                                descriptor.parametersSchema()))
                        .toList())
                .build();
        return new Probe(provider, context);
    }

    private static List<ChatMessage> messages(String request) {
        return new ArrayList<>(List.of(
                ChatMessage.system("You are Talos."),
                ChatMessage.user(request)));
    }

    private static ToolCallLoop.LoopResult loopResult(
            String answer,
            List<ChatMessage> messages,
            int toolsInvoked
    ) {
        return new ToolCallLoop.LoopResult(
                answer,
                toolsInvoked,
                toolsInvoked,
                toolsInvoked == 0 ? List.of() : List.of("talos.list_dir"),
                List.copyOf(messages),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0);
    }

    private record Probe(
            ScriptedNativeLlmClient.RecordedClient provider,
            Context context
    ) {
    }
}
