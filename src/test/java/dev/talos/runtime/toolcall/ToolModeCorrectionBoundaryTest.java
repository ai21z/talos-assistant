package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A text-form tool call in native tool mode never runs, and earns at most one corrective native re-prompt
 * (MICA invariant I4, decision D7). Real assembly, receipt, loop, approval and file tools with a scripted provider.
 */
class ToolModeCorrectionBoundaryTest {
    private static final String REQUEST =
            "Change README.md from old title to new title and NOTES.md from old note to new note. Preserve everything else.";
    private static final LocalTurnTraceCapture.ToolCallAuthorityRejectionReason MISMATCH =
            LocalTurnTraceCapture.ToolCallAuthorityRejectionReason.PROVIDER_TOOL_MODE_MISMATCH;
    @TempDir Path workspace;

    @Test
    void aTextCallInNativeModeIsNotRunAndOneCorrectionLetsTheNativeCallsProceed() throws Exception {
        RecordingGate gate = new RecordingGate();
        Run run = run(gate, List.of(
                new LlmClient.StreamResult(textCall(read("read-1", "README.md")), List.of()),
                nativeResponse(read("read-1", "README.md"), read("read-2", "NOTES.md")),
                nativeResponse(edit("edit-1", "README.md", "old title", "new title"),
                        edit("edit-2", "NOTES.md", "old note", "new note")),
                new LlmClient.StreamResult("Updated both files.", List.of())));

        // P20 (D14 option A): one continuation follows the completed mutations without request-bound proof.
        assertEquals(4, run.requests().size());
        assertTrue(hasNotice(run.requests().get(1)), "The second request carries the runtime correction.");
        assertFalse(hasNotice(run.requests().getFirst()));
        assertEquals(1, run.result().toolOutcomes().stream()
                .filter(outcome -> outcome.failureReason() == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)
                .count(), "The text call itself never ran.");
        assertEquals(2, gate.calls);
        assertEquals("new title\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("new note\n", Files.readString(workspace.resolve("NOTES.md")));
    }

    @Test
    void aSecondTextCallStopsWithoutAnotherRequestAndNothingRuns() throws Exception {
        RecordingGate gate = new RecordingGate();
        Run run = run(gate, List.of(
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of())));

        assertEquals(2, run.requests().size(), "Only one correction is sent in a turn.");
        assertTrue(hasNotice(run.requests().get(1)));
        assertEquals(0, gate.calls);
        assertTrue(run.result().finalAnswer().contains("PROVIDER_TOOL_MODE_MISMATCH"), run.result().finalAnswer());
        assertEquals("old title\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("old note\n", Files.readString(workspace.resolve("NOTES.md")));
    }

    @Test
    void onlyAWhollyRejectedNativeModeIterationIsCorrected() throws Exception {
        LoopState state = nativeState();
        state.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));

        assertTrue(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(MISMATCH))));
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 1, List.of(MISMATCH))),
                "A call that ran in the same iteration keeps the stop.");
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(2, 0, List.of(MISMATCH))),
                "A failure other than the authority rejection means a call was dispatched.");
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(MISMATCH,
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason.PROVIDER_GENERATION_STALE))),
                "Any other authority rejection is a hard stop.");
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(
                LocalTurnTraceCapture.ToolCallAuthorityRejectionReason.PROVIDER_GENERATION_STALE))));
        state.totalToolsInvoked = 1;
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(MISMATCH))),
                "A mismatch after a tool ran in the turn keeps the stop.");
        state.totalToolsInvoked = 0;
        state.toolModeCorrectionSent = true;
        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(MISMATCH))));
    }

    @Test
    void aRetryLoopInTheSameTurnSendsNoCorrectionAfterAToolRan() throws Exception {
        RecordingGate gate = new RecordingGate();
        List<ChatRequest> requests = executeTurn(gate, List.of(
                nativeResponse(read("read-1", "README.md")),
                new LlmClient.StreamResult("Both files are updated.", List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult("Unused.", List.of())));

        assertTrue(requests.size() >= 3, "The missing-mutation retry ran: " + requests.size());
        assertTrue(requests.stream().noneMatch(ToolModeCorrectionBoundaryTest::hasNotice),
                "A tool already ran in this turn, so a retry loop earns no correction.");
        assertEquals(0, gate.calls);
        assertEquals("old title\n", Files.readString(workspace.resolve("README.md")));
    }

    @Test
    void aRetryLoopInTheSameTurnSendsNoSecondCorrection() throws Exception {
        RecordingGate gate = new RecordingGate();
        List<ChatRequest> requests = executeTurn(gate, List.of(
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult("Both files are updated.", List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of()),
                new LlmClient.StreamResult("Unused.", List.of())));

        // Initial request, the one correction, then the missing-mutation retry, whose text call stops the turn.
        assertEquals(3, requests.size(), "Only one correction is sent in a turn, across every tool loop.");
        assertTrue(hasNotice(requests.get(1)));
        assertFalse(hasNotice(requests.get(2)), "The retry conversation carries no further correction.");
        assertEquals("BLOCKED_BY_POLICY", classification, "A stopped mismatch is never reported as complete.");
        assertEquals(0, gate.calls);
        assertEquals("old title\n", Files.readString(workspace.resolve("README.md")));
    }

    private String classification = "";

    private List<ChatRequest> executeTurn(RecordingGate gate, List<LlmClient.StreamResult> responses) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "old title\n");
        Files.writeString(workspace.resolve("NOTES.md"), "old note\n");
        Config config = new Config(null);
        config.data.put("checkpoint", Map.of("enabled", false));
        ToolRegistry registry = registry();
        List<ToolSpec> specs = specs(registry);
        ToolCallLoop loop = new ToolCallLoop(new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK), 5);
        var fixture = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 32_768);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.begin("tool-mode-correction", "tool-mode-correction", 1,
                "2026-10-29T00:00:00Z", "isolated-workspace", "test", "fixture", "fixture", REQUEST);
        // The turn audit is active as in a real turn, so turn-scoped facts span every loop of the turn.
        dev.talos.runtime.TurnAuditCapture.begin();
        try (LlmClient llm = fixture.client()) {
            llm.setToolSpecs(specs);
            Context context = Context.builder(config).llm(llm).nativeToolSpecs(specs)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).toolCallLoop(loop).build();
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(REQUEST)));
            dev.talos.cli.modes.AssistantTurnExecutor.execute(messages, workspace, context,
                    new dev.talos.cli.modes.AssistantTurnExecutor.Options());
            classification = LocalTurnTraceCapture.complete().outcome().classification();
        } finally {
            dev.talos.runtime.TurnAuditCapture.end();
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
        }
        return List.copyOf(fixture.requests());
    }

    @Test
    void aResponseWithoutNativeToolAuthorityIsNotCorrected() throws Exception {
        // Without a turn trace the provider receipt carries no native tool authority (synthetic unit state).
        LoopState state = nativeState(false);
        state.toolOutcomes.add(ToolOutcomeFactory.failedAuthority(null, "blocked"));

        assertFalse(ToolModeCorrectionReprompt.eligible(state, rejected(1, 0, List.of(MISMATCH))),
                "The correction asks for a native call, so it is sent only after a native-mode response.");
    }

    private static ToolCallExecutionStage.IterationOutcome rejected(
            int failures, int successes, List<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason> reasons) {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), failures, false, false, false, successes,
                List.of(), true, 0, reasons);
    }

    private static boolean hasNotice(ChatRequest request) {
        return request.messages.stream().anyMatch(message -> message.content() != null
                && message.content().contains("Text tool calls are not executed in this tool mode, and nothing was run."));
    }

    private LoopState nativeState() throws Exception {
        return nativeState(true);
    }

    private LoopState nativeState(boolean traced) throws Exception {
        Config config = new Config(null);
        ToolRegistry registry = registry();
        List<ToolSpec> specs = specs(registry);
        var fixture = ScriptedNativeLlmClient.recordingWithContextWindow(config, List.of(nativeResponse(
                read("read-1", "README.md"))), 32_768);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        // The provider receipt carries its tool authority only inside a turn trace, as in a real turn.
        if (traced) LocalTurnTraceCapture.begin("tool-mode-correction", "tool-mode-correction", 1,
                "2026-10-29T00:00:00Z", "isolated-workspace", "test", "fixture", "fixture", REQUEST);
        try (LlmClient llm = fixture.client()) {
            llm.setToolSpecs(specs);
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user(REQUEST)));
            var plan = plan(messages, specs, config);
            var initial = llm.chatFull(messages, specs, ProviderRequestControlPolicy.forTurn(plan, specs,
                    llm.supportsRequiredToolChoice()));
            LoopState state = new LoopState("", List.of(), messages, workspace, null, null, 5, 0);
            state.applyProviderResult(initial);
            return state;
        } finally {
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
        }
    }

    private Run run(RecordingGate gate, List<LlmClient.StreamResult> responses) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "old title\n");
        Files.writeString(workspace.resolve("NOTES.md"), "old note\n");
        Config config = new Config(null);
        config.data.put("checkpoint", Map.of("enabled", false));
        ToolRegistry registry = registry();
        List<ToolSpec> specs = specs(registry);
        ToolCallLoop loop = new ToolCallLoop(new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK), 5);
        var fixture = ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 32_768);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.begin("tool-mode-correction", "tool-mode-correction", 1,
                "2026-10-29T00:00:00Z", "isolated-workspace", "test", "fixture", "fixture", REQUEST);
        try (LlmClient llm = fixture.client()) {
            llm.setToolSpecs(specs);
            Context context = Context.builder(config).llm(llm).nativeToolSpecs(specs)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).toolCallLoop(loop).build();
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user(REQUEST)));
            var plan = plan(messages, specs, config);
            var controls = ProviderRequestControlPolicy.forTurn(plan, specs, llm.supportsRequiredToolChoice());
            var initial = llm.chatFull(messages, specs, controls);
            var result = loop.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
            return new Run(result, List.copyOf(fixture.requests()));
        } finally {
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
        }
    }

    private static CurrentTurnPlan plan(List<ChatMessage> messages, List<ToolSpec> specs, Config config) {
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        TurnTaskContractCapture.set(resolution, messages);
        List<String> names = specs.stream().map(ToolSpec::name).toList();
        return CurrentTurnPlan.createWithActionObligation(resolution,
                CurrentTurnPlan.defaultPhaseFor(resolution.contract()), names,
                names, List.of(), CurrentTurnPlan.NONE_OR_NOT_DERIVED, CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.derivedVerifierProfile(resolution.contract()),
                ActionObligation.MUTATING_TOOL_REQUIRED, config);
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileEditTool());
        return registry;
    }

    private static List<ToolSpec> specs(ToolRegistry registry) {
        return registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
    }

    private static ChatMessage.NativeToolCall read(String id, String path) {
        return new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path));
    }

    private static ChatMessage.NativeToolCall edit(String id, String path, String oldText, String newText) {
        return new ChatMessage.NativeToolCall(id, "talos.edit_file",
                Map.of("path", path, "old_string", oldText, "new_string", newText));
    }

    private static LlmClient.StreamResult nativeResponse(ChatMessage.NativeToolCall... calls) {
        return new LlmClient.StreamResult("", List.of(calls));
    }

    private static String textCall(ChatMessage.NativeToolCall call) throws Exception {
        return "```json\n" + new ObjectMapper().writeValueAsString(
                Map.of("name", call.name(), "arguments", call.arguments())) + "\n```";
    }

    private record Run(ToolCallLoop.LoopResult result, List<ChatRequest> requests) {}

    private static final class RecordingGate implements ApprovalGate {
        private int calls;

        @Override
        public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }

        @Override
        public ApprovalResponse approveFull(String description, String detail) {
            calls++;
            return ApprovalResponse.APPROVED;
        }
    }
}
