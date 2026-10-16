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
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real assembly, receipt, loop, approval and file tools with an offline scripted provider. */
class NativeMutationContinuationBoundaryTest {
    private static final String REQUEST =
            "Change README.md from old title to new title and NOTES.md from old note to new note. Preserve everything else.";
    @TempDir Path workspace;

    @Test
    void eachPrerequisiteNativeReadKeepsRequiredAndBroadSurfaceUntilMutationCompletes() throws Exception {
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        Run run = run(false, gate, List.of(
                nativeResponse(read("read-1", "README.md")),
                nativeResponse(read("read-2", "NOTES.md")),
                nativeResponse(edit("edit-1", "README.md", "old title", "new title"),
                        edit("edit-2", "NOTES.md", "old note", "new note"))));

        assertEquals(3, run.requests().size(), "Completed mutations must not trigger another generation.");
        for (int i = 1; i < run.requests().size(); i++) {
            ChatRequest request = run.requests().get(i);
            assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                    request.controls.toolAuthorityMode());
            assertTrue(request.controls.debugTags().contains("current-turn-mutation-obligation"));
            assertEquals(run.requests().get(0).tools.stream().map(ToolSpec::name).toList(),
                    request.tools.stream().map(ToolSpec::name).toList(), "Prerequisite reads must remain available.");
        }
        assertEquals(List.of("talos.read_file", "talos.read_file", "talos.edit_file", "talos.edit_file"),
                run.result().toolOutcomes().stream().map(ToolCallLoop.ToolOutcome::toolName).toList());
        assertEquals(2, run.result().mutatingToolSuccesses(), run.result().summary());
        assertEquals(2, gate.calls);
        assertEquals("new title\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("new note\n", Files.readString(workspace.resolve("NOTES.md")));
    }

    @Test
    void actualUserDenialStopsWithoutAnotherProviderRequestOrEffects() throws Exception {
        RecordingGate gate = new RecordingGate(ApprovalResponse.DENIED);
        Run run = run(false, gate, List.of(
                nativeResponse(read("read-1", "README.md"), read("read-2", "NOTES.md")),
                nativeResponse(edit("edit-1", "README.md", "old title", "new title"))));

        assertEquals(2, run.requests().size());
        assertEquals(ToolChoiceMode.REQUIRED, run.requests().get(1).controls.toolChoice());
        assertEquals(1, gate.calls);
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertTrue(run.result().toolOutcomes().stream()
                .anyMatch(outcome -> outcome.failureReason() == ToolFailureReason.USER_APPROVAL_DENIED));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateFeedbackCannotDisplaceAdmittedMutationSources(boolean textual) throws Exception {
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        var first = read("read-1", "README.md");
        var second = read("read-2", "NOTES.md");
        var responses = new ArrayList<LlmClient.StreamResult>();
        for (var call : List.of(first, second, read("duplicate-1", "README.md"),
                read("duplicate-2", "README.md"))) {
            responses.add(textual ? new LlmClient.StreamResult(textCall(call), List.of()) : nativeResponse(call));
        }
        var title = edit("edit-1", "README.md", "old title", "new title");
        var note = edit("edit-2", "NOTES.md", "old note", "new note");
        responses.add(textual ? new LlmClient.StreamResult(textCall(title) + "\n" + textCall(note), List.of())
                : nativeResponse(title, note));

        Run run = run(textual, gate, responses);

        assertEquals(5, run.requests().size());
        String resultRole = textual ? "user" : "tool";
        for (int index : List.of(3, 4)) {
            var request = run.requests().get(index);
            for (String body : List.of("1 | old title", "1 | old note")) {
                assertEquals(1, request.messages.stream().filter(message -> resultRole.equals(message.role())
                        && message.content().contains(body)).count(), body);
                assertFalse(request.messages.stream().filter(message -> "system".equals(message.role()))
                        .anyMatch(message -> message.content().contains(body)), body);
            }
            assertTrue(request.messages.stream().anyMatch(message -> resultRole.equals(message.role())
                    && message.content().contains("requested mutation remains outstanding")));
            assertFalse(request.messages.stream().anyMatch(message -> resultRole.equals(message.role())
                    && message.content().contains("Answer the user's question now")));
            assertEquals(run.requests().getFirst().tools.stream().map(ToolSpec::name).toList(),
                    request.tools.stream().map(ToolSpec::name).toList());
            assertEquals(textual ? ToolChoiceMode.AUTO : ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        }
        assertEquals(2, gate.calls);
        assertEquals(2, run.result().mutatingToolSuccesses());
        assertEquals(4, run.result().toolOutcomes().size(), "Duplicate reads do not execute again.");
        assertEquals("new title\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("new note\n", Files.readString(workspace.resolve("NOTES.md")));
    }

    @Test
    void requiredNativeContinuationStillRejectsTextualMutationBeforeApproval() throws Exception {
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        Run run = run(false, gate, List.of(
                nativeResponse(read("read-1", "README.md"), read("read-2", "NOTES.md")),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")), List.of())));

        assertEquals(2, run.requests().size());
        assertEquals(ToolChoiceMode.REQUIRED, run.requests().get(1).controls.toolChoice());
        assertEquals(0, gate.calls);
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertTrue(run.result().finalAnswer().contains("PROVIDER_TOOL_MODE_MISMATCH"), run.result().finalAnswer());
        assertTrue(run.result().toolOutcomes().stream()
                .anyMatch(outcome -> outcome.failureReason() == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID));
        assertUnchanged();
    }

    @Test
    void textualProviderContinuationRetainsAutoInsteadOfRequiringNativeTools() throws Exception {
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        Run run = run(true, gate, List.of(
                new LlmClient.StreamResult(textCall(read("read-1", "README.md")) + "\n"
                        + textCall(read("read-2", "NOTES.md")), List.of()),
                new LlmClient.StreamResult(textCall(edit("edit-1", "README.md", "old title", "new title")) + "\n"
                        + textCall(edit("edit-2", "NOTES.md", "old note", "new note")), List.of())));

        assertEquals(2, run.requests().size());
        assertEquals(ToolChoiceMode.AUTO, run.requests().get(1).controls.toolChoice());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                run.requests().get(1).controls.toolAuthorityMode());
        assertFalse(run.requests().get(1).controls.debugTags().contains("current-turn-mutation-obligation"));
        assertEquals(2, gate.calls);
        assertEquals(2, run.result().mutatingToolSuccesses(), run.result().summary());
    }

    private Run run(boolean textual, RecordingGate gate, List<LlmClient.StreamResult> responses) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "old title\n");
        Files.writeString(workspace.resolve("NOTES.md"), "old note\n");
        Config config = new Config(null);
        config.data.put("checkpoint", Map.of("enabled", false));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileEditTool());
        List<ToolSpec> specs = registry.descriptors().stream().map(descriptor ->
                new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
        ToolCallLoop loop = new ToolCallLoop(new TurnProcessor(null, gate, registry, ApprovalPolicy.ALWAYS_ASK), 5);
        var fixture = textual
                ? ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(responses, 32_768)
                : ScriptedNativeLlmClient.recordingWithContextWindow(config, responses, 32_768);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.begin("native-continuation", "native-continuation", 1,
                "2026-10-06T00:00:00Z", "isolated-workspace", "test", "fixture", "fixture", REQUEST);
        try (LlmClient llm = fixture.client()) {
            llm.setToolSpecs(specs);
            Context context = Context.builder(config).llm(llm).nativeToolSpecs(specs)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).toolCallLoop(loop).build();
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user(REQUEST)));
            var resolution = TaskContractResolver.resolveFromMessages(messages);
            TurnTaskContractCapture.set(resolution, messages);
            assertEquals(ActionObligation.MUTATING_TOOL_REQUIRED,
                    ActionObligationPolicy.derive(resolution, CurrentTurnPlan.defaultPhaseFor(resolution.contract())));
            assertEquals(java.util.Set.of("README.md", "NOTES.md"), resolution.contract().expectedTargets());
            List<String> names = specs.stream().map(ToolSpec::name).toList();
            var plan = CurrentTurnPlan.createWithActionObligation(resolution,
                    CurrentTurnPlan.defaultPhaseFor(resolution.contract()), textual ? List.of() : names,
                    names, List.of(), CurrentTurnPlan.NONE_OR_NOT_DERIVED, CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                    CurrentTurnPlan.derivedVerifierProfile(resolution.contract()),
                    ActionObligation.MUTATING_TOOL_REQUIRED, config);
            var controls = ProviderRequestControlPolicy.forTurn(plan, specs, llm.supportsRequiredToolChoice());
            var initial = llm.chatFull(messages, specs, controls);
            assertTrue(initial.provenance().providerToolCallEligible());
            var result = loop.run(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context);
            return new Run(result, List.copyOf(fixture.requests()));
        } finally {
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
        }
    }

    private void assertUnchanged() throws Exception {
        assertEquals("old title\n", Files.readString(workspace.resolve("README.md")));
        assertEquals("old note\n", Files.readString(workspace.resolve("NOTES.md")));
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
        private final ApprovalResponse response;
        private int calls;

        private RecordingGate(ApprovalResponse response) { this.response = response; }

        @Override
        public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }

        @Override
        public ApprovalResponse approveFull(String description, String detail) {
            assertTrue(++calls <= 2, "Unexpected repeated approval.");
            return response;
        }
    }
}
