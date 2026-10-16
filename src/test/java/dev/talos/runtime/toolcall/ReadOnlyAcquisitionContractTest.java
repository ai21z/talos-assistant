package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.policy.ProviderRequestControlPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real task/read/reprompt boundaries with scripted provider replies, never a live model. */
class ReadOnlyAcquisitionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST = "Hey, this little local label project seems out of sync. "
            + "Read README.md, settings.json and label.py. Tell me what label the code would return, "
            + "what label is expected, and suggest the smallest change if needed. Don't change files or run anything.";
    private static final Set<String> TARGETS = Set.of("README.md", "settings.json", "label.py");
    private static final Map<String, String> CONTENTS = Map.of(
            "README.md", "The expected label is cedar.\n",
            "settings.json", "{\"label\":\"pine\"}\n",
            "label.py", "import json\nfrom pathlib import Path\n"
                    + "def label():\n    return json.loads(Path('settings.json').read_text())['label']\n");
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();

    @BeforeEach void begin() throws Exception {
        for (var entry : CONTENTS.entrySet()) Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
        TurnAuditCapture.begin();
        LocalTurnTraceCapture.begin("read-only-acquisition-contract", "offline-scripted-model", 1,
                "2026-09-30T00:00:00Z", "workspace", "auto", "llama_cpp", "scripted-model", REQUEST);
    }

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        TurnAuditCapture.end();
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }

    @Test void retainedWorkspaceExplanationKeepsRequiredNativeReadsAndQwenPrefillAfterReadme() throws Exception {
        var fixture = fixture(List.of(read("readme", "README.md"), read("settings", "settings.json")));
        var initial = fixture.recorded().requests().getFirst();
        assertRequiredReadControls(initial);
        assertRequiredQwenBody(serializeThroughCompat(initial));

        var outcome = execute(fixture);
        assertEquals(1, outcome.successesThisIteration());
        assertFalse(outcome.executionAuthorityRejectedThisIteration());
        assertEquals(Set.of("README.md"), Set.copyOf(fixture.state().pathsReadThisTurn));
        assertEquals(1, fixture.state().totalToolsInvoked);
        assertTrue(new ToolCallRepromptStage().reprompt(fixture.state(), outcome));
        assertEquals(2, fixture.recorded().requests().size());

        var next = fixture.recorded().requests().getLast();
        // Serialize even on the broken path, so REQUIRED and prefill assertions cover the real wire body.
        JsonNode wire = serializeThroughCompat(next);
        assertAll(
                () -> assertRequiredReadControls(next),
                () -> assertRequiredQwenBody(wire),
                () -> assertPendingPaths(next, Set.of("settings.json", "label.py")),
                () -> assertReadmeCarrier(next, wire),
                () -> assertEquals(1, fixture.state().totalToolsInvoked,
                        "The settings proposal has not executed at the request boundary"),
                () -> assertEquals(0, fixture.state().mutatingToolSuccesses));
        assertFilesUnchanged();
    }

    @Test void completingMandatoryReadsDoesNotAcquireDiagnosisSynthesisOrRepeatReads() throws Exception {
        var fixture = fixture(List.of(read("readme", "README.md"), read("settings", "settings.json"),
                read("label", "label.py"), new LlmClient.StreamResult(
                        "Scripted answer: static return pine, expected cedar, suggest changing the configured label.", List.of())));
        for (int index = 0; index < 3; index++) {
            var outcome = execute(fixture);
            assertEquals(1, outcome.successesThisIteration());
            assertEquals(index + 1, fixture.state().totalToolsInvoked);
            if (index < 2) {
                assertTrue(new ToolCallRepromptStage().reprompt(fixture.state(), outcome));
                assertRequiredReadControls(fixture.recorded().requests().getLast());
            } else {
                assertFalse(ReadOnlyEvidenceContext.select(fixture.state()).available(),
                        "Acquisition admission must not broaden the diagnosis/synthesis evidence selector");
                assertTrue(ReadOnlyEvidenceSynthesizer.tryHandle(fixture.state(), outcome).isEmpty());
                assertTrue(ReadOnlyEvidenceContinuation.tryHandle(fixture.state()).isEmpty(),
                        "No mandatory path remains unread");
                new ToolCallRepromptStage().reprompt(fixture.state(), outcome);
            }
        }
        assertEquals(TaskType.WORKSPACE_EXPLAIN, TurnTaskContractCapture.get().type());
        assertEquals(TARGETS, Set.copyOf(fixture.state().pathsReadThisTurn));
        assertEquals(3, fixture.state().toolOutcomes.size());
        assertTrue(fixture.state().toolOutcomes.stream().allMatch(item -> item.success() && !item.mutating()));
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, fixture.state().readOnlySynthesisStatus);
        assertEquals(4, fixture.recorded().requests().size());
        var completed = fixture.recorded().requests().getLast();
        assertEquals(ToolChoiceMode.AUTO, completed.controls.toolChoice());
        assertFalse(completed.controls.debugTags().contains("bounded-read-only-synthesis"));
        assertFalse(completed.messages.stream().anyMatch(message -> message.content().startsWith("[Pending")));
        var wire = serializeThroughCompat(completed);
        assertFalse(wire.has("tool_choice"));
        assertFalse(wire.has("continue_final_message"));
        assertTrue(fixture.state().currentNativeCalls.isEmpty());
        assertEquals(0, fixture.state().mutatingToolSuccesses);
        assertFilesUnchanged();
    }

    @Test void textualReadProposalsAfterTheNativeReadRemainRejectedWithoutAnotherGeneration() throws Exception {
        String textual = "```json\n{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"settings.json\"}}\n```";
        var fixture = fixture(List.of(read("readme", "README.md"), new LlmClient.StreamResult(textual, List.of())));
        var first = execute(fixture);
        assertEquals(1, first.successesThisIteration());
        assertTrue(new ToolCallRepromptStage().reprompt(fixture.state(), first));
        var parsed = new ToolCallParseStage().parse(fixture.state(), 2);
        assertTrue(parsed.useTextPath());
        assertFalse(parsed.useNativePath());
        assertEquals(1, parsed.calls().size());
        fixture.state().iterations++;
        var rejected = fixture.execution().execute(fixture.state(), parsed);
        assertTrue(rejected.executionAuthorityRejectedThisIteration());
        assertEquals(0, rejected.successesThisIteration());
        assertEquals(ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                fixture.state().toolOutcomes.getLast().failureReason());
        assertTrue(fixture.state().toolOutcomes.getLast().errorMessage().contains("PROVIDER_TOOL_MODE_MISMATCH"));
        assertFalse(new ToolCallRepromptStage().reprompt(fixture.state(), rejected));
        assertEquals(2, fixture.recorded().requests().size());
        assertEquals(1, fixture.state().totalToolsInvoked);
        assertEquals(Set.of("README.md"), Set.copyOf(fixture.state().pathsReadThisTurn));
        assertEquals(0, fixture.state().mutatingToolSuccesses);
        assertFilesUnchanged();
    }

    private Fixture fixture(List<LlmClient.StreamResult> replies) {
        var messages = new ArrayList<>(List.of(ChatMessage.system("Read the requested workspace sources."),
                ChatMessage.user(REQUEST)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        assertEquals(TaskType.WORKSPACE_EXPLAIN, resolution.contract().type());
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        assertFalse(resolution.contract().verificationRequired());
        // The retained request still receives legacy raw MUST_MUTATE roles despite its
        // non-mutating contract. Prompt-debug's effective MUST_READ projection is not
        // the resolver's raw intent. The explicit current read clause must establish
        // acquisition authority without treating these legacy roles alone as authority.
        assertEquals(TARGETS, resolution.contract().expectedTargets());
        assertTrue(resolution.intent().targets().pathsByRole(TargetRole.MUST_READ).isEmpty());
        assertEquals(TARGETS, resolution.intent().targets().pathsByRole(TargetRole.MUST_MUTATE));
        TurnTaskContractCapture.set(resolution, messages);
        TurnUserRequestCapture.set(REQUEST);
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(replies, 16_384);
        clients.add(recorded.client());
        var tool = new ReadFileTool();
        var descriptor = tool.descriptor();
        var specs = List.of(new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema()));
        recorded.client().setToolSpecs(specs);
        var plan = CurrentTurnPlan.create(resolution.contract(), CurrentTurnPlan.defaultPhaseFor(resolution.contract()),
                List.of(descriptor.name()), List.of(), List.of());
        var controls = ProviderRequestControlPolicy.forTurn(plan, specs, recorded.client().supportsRequiredToolChoice());
        var initial = recorded.client().chatFull(messages, specs, controls);
        var config = new Config();
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .llm(recorded.client()).nativeToolSpecs(specs).build();
        var state = new LoopState(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace,
                context, new Session(workspace, config), 10, 0);
        var registry = new ToolRegistry();
        registry.register(tool);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        return new Fixture(recorded, state, new ToolCallExecutionStage(processor, null, false));
    }

    private static ToolCallExecutionStage.IterationOutcome execute(Fixture fixture) {
        var parsed = new ToolCallParseStage().parse(fixture.state(), fixture.state().iterations + 1);
        assertTrue(parsed.useNativePath());
        assertEquals(1, parsed.calls().size());
        fixture.state().iterations++;
        return fixture.execution().execute(fixture.state(), parsed);
    }

    private static void assertRequiredReadControls(ChatRequest request) {
        assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        assertEquals(ResponseFormatMode.TEXT, request.controls.responseFormat());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE, request.controls.toolAuthorityMode());
        assertEquals(512, request.controls.maxOutputTokens());
        assertEquals(List.of("talos.read_file"), request.tools.stream().map(ToolSpec::name).toList());
    }

    private static void assertRequiredQwenBody(JsonNode body) {
        assertEquals("required", body.path("tool_choice").asText());
        assertEquals(512, body.path("max_tokens").asInt());
        assertEquals(1, body.path("tools").size());
        assertEquals("talos.read_file", body.path("tools").get(0).path("function").path("name").asText());
        var last = body.path("messages").get(body.path("messages").size() - 1);
        assertEquals("assistant", last.path("role").asText());
        assertEquals("<tool_call>\n", last.path("content").asText());
        assertEquals("content", body.path("continue_final_message").asText());
        assertFalse(body.path("add_generation_prompt").asBoolean(true));
        assertTrue(body.path("stream").asBoolean());
    }

    private static void assertPendingPaths(ChatRequest request, Set<String> expected) throws Exception {
        var reminder = request.messages.getLast();
        assertEquals("user", reminder.role());
        assertTrue(reminder.content().startsWith("[Pending"), reminder.content());
        var paths = new HashSet<String>();
        JSON.readTree(reminder.content().split("\n", 3)[1]).forEach(path -> paths.add(path.asText()));
        assertEquals(expected, paths);
    }

    private static void assertReadmeCarrier(ChatRequest request, JsonNode wire) {
        var carriers = request.messages.stream().filter(message -> "tool".equals(message.role())).toList();
        assertEquals(1, carriers.size());
        var readme = carriers.getFirst();
        assertEquals("readme", readme.toolCallId());
        assertTrue(readme.content().contains(CONTENTS.get("README.md").strip()));
        var wireCarriers = new ArrayList<JsonNode>();
        wire.path("messages").forEach(message -> {
            if ("tool".equals(message.path("role").asText())) wireCarriers.add(message);
        });
        assertEquals(1, wireCarriers.size());
        assertEquals(readme.content(), wireCarriers.getFirst().path("content").asText());
        assertEquals(readme.toolCallId(), wireCarriers.getFirst().path("tool_call_id").asText());
        assertTrue(request.messages.stream().anyMatch(message -> REQUEST.equals(message.content())));
    }

    /** Replays the actual captured request through production serialization to a loopback fixture. */
    private static JsonNode serializeThroughCompat(ChatRequest request) throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("data: {\"choices\":[{\"delta\":{\"content\":\"offline transport fixture\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, reply.length);
            try (var response = exchange.getResponseBody()) { response.write(reply); }
        });
        server.start();
        try (var http = HttpClient.newHttpClient()) {
            var compat = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "offline-qwen-serialization-fixture", http, JSON);
            try (var response = compat.chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                    CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, ProviderTransportHandoff.none())) {
                assertEquals("stop", response.toList().getLast().finishReason());
            }
            return JSON.readTree(body.get());
        } finally {
            server.stop(0);
        }
    }

    private void assertFilesUnchanged() throws Exception {
        for (var entry : CONTENTS.entrySet()) assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
    }

    private static LlmClient.StreamResult read(String id, String path) {
        return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path))));
    }

    private record Fixture(ScriptedNativeLlmClient.RecordedClient recorded, LoopState state,
                           ToolCallExecutionStage execution) {}
}
