package dev.talos.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ForegroundProviderScriptedLlmClient;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline transport fixture, real provider assembly, selected receipts, approval,
 * and filesystem tools. No live model or manually fabricated response metadata.
 */
class NormalizedTextToolAuthorityE2eTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRESENTATION =
            "The replacement is <button id=\"example\">Continue</button>.\n";

    @TempDir
    Path workspace;

    private ToolRegistry sessionRegistry;
    private TurnProcessor sessionProcessor;
    private final List<ChatMessage> conversation = new ArrayList<>(List.of(
            ChatMessage.system("Use the exact selected JSON tool names.")));

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void normalizedHtmlProseRetainsExactApprovedEditThroughRealAssembly() throws Exception {
        String oldText = "<button id=\"old\">Old</button>\n";
        String newText = "<button id=\"new\">New Ω</button>\n";
        Files.writeString(workspace.resolve("example.txt"), oldText);
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);

        TurnRun run = runTurn(1, PRESENTATION + edit("example.txt", oldText, newText),
                gate, "Replace the exact button text in example.txt.");

        assertNormalizedProviderEos(run);
        assertEquals(newText, Files.readString(workspace.resolve("example.txt")));
        assertEquals(1, run.result().mutatingToolSuccesses());
        assertEquals(0, run.result().failedCalls(), run.result().summary());
        assertEquals(1, gate.calls);
        assertEquals(1, events(run.trace(), "APPROVAL_GRANTED"));
        assertEquals(0, events(run.trace(), "TOOL_CALL_AUTHORITY_REJECTED"));
        assertTextualAuthority(run.trace(), 1);
    }

    @Test
    void normalizedMultipleCallsPreserveArgumentsAndExecuteInSourceOrder() throws Exception {
        String oldText = ".item { color: red; }\n";
        String newText = ".item { color: blue; content: \"<button>\\path</button>\"; }\n";
        Files.writeString(workspace.resolve("style.txt"), oldText);
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        String response = PRESENTATION + edit("style.txt", oldText, newText)
                + "\n" + call("talos.read_file", Map.of("path", "style.txt"));

        TurnRun run = runTurn(1, response, gate, "Edit style.txt and read back its new content.");

        assertNormalizedProviderEos(run);
        assertEquals(newText, Files.readString(workspace.resolve("style.txt")));
        assertEquals(List.of("talos.edit_file", "talos.read_file"),
                run.result().toolOutcomes().stream().map(ToolCallLoop.ToolOutcome::toolName).toList());
        assertTrue(run.result().toolOutcomes().stream().allMatch(ToolCallLoop.ToolOutcome::success),
                run.result().summary());
        String readBody = run.result().readFileBodies().get("style.txt");
        assertNotNull(readBody);
        assertTrue(readBody.contains(newText.stripTrailing()),
                "The formatted read must observe the preceding edit: " + readBody);
        assertFalse(readBody.contains(oldText.stripTrailing()), readBody);
        assertEquals(1, gate.calls, "A read must not consume another mutation approval.");
        assertTextualAuthority(run.trace(), 2);
    }

    @Test
    void deniedMutationDoesNotPoisonFreshApprovedTurn() throws Exception {
        Files.writeString(workspace.resolve("renewal.txt"), "old\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.DENIED, ApprovalResponse.APPROVED);
        String response = PRESENTATION + edit("renewal.txt", "old\n", "new\n");

        TurnRun denied = runTurn(1, response, gate, "Change renewal.txt from old to new.");
        assertNormalizedProviderEos(denied);
        assertEquals("old\n", Files.readString(workspace.resolve("renewal.txt")));
        assertEquals(0, denied.result().mutatingToolSuccesses());
        assertEquals(1, events(denied.trace(), "APPROVAL_DENIED"));
        assertEquals(1, gate.calls);

        TurnRun approved = runTurn(2, response, gate, "Edit renewal.txt now with the requested replacement.");
        assertNormalizedProviderEos(approved);
        assertEquals("new\n", Files.readString(workspace.resolve("renewal.txt")));
        assertEquals(1, approved.result().mutatingToolSuccesses());
        assertEquals(1, events(approved.trace(), "APPROVAL_GRANTED"));
        assertEquals(2, gate.calls, "Renewed execution must receive a fresh approval.");
        assertNotSame(denied.initial().provenance(), approved.initial().provenance());
        assertNotEquals(denied.initial().provenance().generationId(),
                approved.initial().provenance().generationId());
        assertEquals(0, events(approved.trace(), "TOOL_CALL_AUTHORITY_REJECTED"));
    }

    @Test
    void historicalPreambleAndReferencedChangesReceiveFreshApprovalAndExactReadback() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Harbor Notes</h1>\n");
        Files.writeString(workspace.resolve("style.css"), "h1 { color: #334455; }\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.DENIED,
                ApprovalResponse.APPROVED, ApprovalResponse.APPROVED);
        String htmlEdit = edit("index.html", "Harbor Notes", "Harbor Notes Live");
        TurnRun denied = runTurn(1, PRESENTATION + htmlEdit, gate,
                "Change only index.html from Harbor Notes to Harbor Notes Live and style.css from #334455 to #445566.");
        assertEquals(0, denied.result().mutatingToolSuccesses());
        assertEquals(1, events(denied.trace(), "APPROVAL_DENIED"));

        String renewed = "Those edits were not approved before. Please implement those same two changes now, "
                + "then read both files back to check what was actually saved. Preserve everything else.";
        TurnRun approved = runTurn(2, PRESENTATION + htmlEdit + "\n"
                + edit("style.css", "#334455", "#445566") + "\n"
                + call("talos.read_file", Map.of("path", "index.html")) + "\n"
                + call("talos.read_file", Map.of("path", "style.css")), gate, renewed);
        assertNormalizedProviderEos(approved);
        assertTrue(approved.resolution().contract().mutationAllowed());
        assertEquals(java.util.Set.of("index.html", "style.css"), approved.resolution().contract().expectedTargets());
        assertEquals("explicit-user-mutation-reference", approved.resolution().contract().classificationReason());
        assertEquals(2, approved.result().mutatingToolSuccesses(), approved.result().summary());
        assertEquals(2, events(approved.trace(), "APPROVAL_GRANTED"));
        assertEquals(3, gate.calls);
        assertEquals("<h1>Harbor Notes Live</h1>\n", Files.readString(workspace.resolve("index.html")));
        assertEquals("h1 { color: #445566; }\n", Files.readString(workspace.resolve("style.css")));
        assertTrue(approved.result().readFileBodies().get("index.html").contains("Harbor Notes Live"));
        assertTrue(approved.result().readFileBodies().get("style.css").contains("#445566"));
        assertNotEquals(denied.initial().provenance().generationId(), approved.initial().provenance().generationId());
        assertEquals(0, events(approved.trace(), "TOOL_CALL_AUTHORITY_REJECTED"));
    }

    @Test
    void unboundReferenceDoesNotReachApprovalOrAlterFiles() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "old\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        TurnRun blocked = runTurn(1, PRESENTATION + edit("index.html", "old\n", "new\n"), gate,
                "Those edits were not approved before. Please implement those changes now.");
        assertFalse(blocked.resolution().contract().mutationAllowed());
        assertEquals("unresolved-user-mutation-reference", blocked.resolution().contract().classificationReason());
        assertTrue(blocked.result().toolOutcomes().stream().anyMatch(outcome -> outcome.errorMessage()
                .contains("A file change was requested, but its scope is not authorized")));
        assertTrue(blocked.result().toolOutcomes().stream().noneMatch(outcome -> outcome.errorMessage()
                .contains("The user did not ask to modify files")));
        assertEquals(0, gate.calls);
        assertEquals(0, blocked.result().mutatingToolSuccesses());
        assertEquals("old\n", Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void hiddenOnlyProposalNeverReachesApprovalOrFilesystem() throws Exception {
        Files.writeString(workspace.resolve("hidden.txt"), "old\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        String hidden = "<think>HIDDEN_SOURCE_CANARY\n"
                + edit("hidden.txt", "old\n", "changed\n") + "\n</think>Visible answer.";

        TurnRun run = runTurn(1, hidden, gate, "Change hidden.txt from old to changed.");

        assertTrue(run.initial().normalized());
        assertFalse(run.initial().text().contains("HIDDEN_SOURCE_CANARY"));
        assertEquals("old\n", Files.readString(workspace.resolve("hidden.txt")));
        assertEquals(0, gate.calls);
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertEquals(0, events(run.trace(), "TOOL_CALL_PARSED"));
        assertFalse(JSON.writeValueAsString(run.trace()).contains("HIDDEN_SOURCE_CANARY"),
                "Raw hidden source must not be serialized into local trace evidence.");
    }

    @Test
    void hiddenProposalAlongsideVisibleProposalCannotAuthorizeASubsetOrLeakRawSource() throws Exception {
        Files.writeString(workspace.resolve("hidden.txt"), "old\n");
        Files.writeString(workspace.resolve("visible.txt"), "old\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        String response = "<think>HIDDEN_SOURCE_CANARY\n" + edit("hidden.txt", "old\n", "changed\n")
                + "\n</think>\n" + edit("visible.txt", "old\n", "changed\n");
        TurnRun run = runTurn(1, response, gate, "Change both files.");
        assertNormalizedProviderEos(run);
        assertEquals(0, gate.calls);
        assertEquals("old\n", Files.readString(workspace.resolve("hidden.txt")));
        assertEquals("old\n", Files.readString(workspace.resolve("visible.txt")));
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertTrue(run.result().finalAnswer().contains("TEXT_PROJECTION_MISMATCH"), run.result().finalAnswer());
        assertTrue(run.trace().events().stream().filter(event ->
                        "TOOL_CALL_AUTHORITY_REJECTED".equals(event.type()))
                .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                .anyMatch(authority -> authority != null
                        && "SUPPRESSED_OR_UNSELECTED_PROPOSALS".equals(authority.get("projectionFailure"))));
        assertFalse(JSON.writeValueAsString(run.trace()).contains("HIDDEN_SOURCE_CANARY"));
    }

    @Test
    void incompleteSecondProposalPreventsExecutionOfCompleteFirstProposal() throws Exception {
        Files.writeString(workspace.resolve("incomplete.txt"), "old\n");
        RecordingGate gate = new RecordingGate(ApprovalResponse.APPROVED);
        String response = edit("incomplete.txt", "old\n", "changed\n")
                + "\n{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"unfinished";

        TurnRun run = runTurn(1, response, gate, "Change incomplete.txt from old to changed.");

        assertEquals(ProviderResponseCapture.Completion.PROVIDER_EOS, run.initial().completion());
        assertTrue(run.initial().normalized());
        assertEquals("old\n", Files.readString(workspace.resolve("incomplete.txt")));
        assertEquals(0, gate.calls);
        assertEquals(0, run.result().mutatingToolSuccesses());
        assertEquals(0, events(run.trace(), "TOOL_CALL_PARSED"));
    }

    private TurnRun runTurn(int turn, String response, RecordingGate gate, String request) {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        Config config = new Config(null);
        config.data.put("checkpoint", Map.of("enabled", false));
        if (sessionRegistry == null) {
            sessionRegistry = new ToolRegistry();
            sessionRegistry.register(new FileEditTool());
            sessionRegistry.register(new ReadFileTool());
            sessionProcessor = new TurnProcessor(null, gate, sessionRegistry, ApprovalPolicy.ALWAYS_ASK);
        }
        ToolRegistry registry = sessionRegistry;
        List<ToolSpec> specs = registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(descriptor.name(), descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
        ToolCallLoop loop = new ToolCallLoop(sessionProcessor, 2);
        LocalTurnTraceCapture.begin("normalized-text-e2e-" + turn, "normalized-text-e2e", turn,
                "2026-10-06T00:00:00Z", "isolated-workspace", "test",
                "foreground-provider-fixture", "foreground-provider-fixture", request);
        try (LlmClient llm = ForegroundProviderScriptedLlmClient.create(
                config, List.of(response, "The tool attempt is complete."))) {
            llm.setToolSpecs(specs);
            Context context = Context.builder(config)
                    .llm(llm)
                    .nativeToolSpecs(specs)
                    .sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry)
                    .toolCallLoop(loop)
                    .build();
            List<ChatMessage> messages = new ArrayList<>(conversation);
            messages.add(ChatMessage.user(request));
            var resolution = dev.talos.runtime.task.TaskContractResolver.resolveFromMessages(messages);
            TurnTaskContractCapture.set(resolution, messages);
            LlmClient.StreamResult initial = llm.chatFull(messages, specs);
            ToolCallLoop.LoopResult result = loop.run(initial.text(), initial.toolCalls(),
                    initial.provenance(), messages, workspace, context);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            conversation.clear();
            conversation.addAll(result.messages());
            conversation.add(ChatMessage.assistant(result.finalAnswer()));
            return new TurnRun(initial, result, trace, resolution);
        } finally {
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
        }
    }

    private static void assertNormalizedProviderEos(TurnRun run) {
        assertTrue(run.initial().normalized(), "Fixture must cross presentation normalization.");
        assertFalse(run.initial().truncated());
        assertEquals(ProviderResponseCapture.Completion.PROVIDER_EOS, run.initial().completion());
        assertTrue(run.initial().provenance().selected());
        assertTrue(run.initial().provenance().providerToolCallEligible());
        assertFalse(run.initial().provenance().providerTerminalEligible(),
                "Text proposal preservation must not grant ordinary answer terminal eligibility.");
    }

    private static void assertTextualAuthority(LocalTurnTrace trace, long expected) {
        assertEquals(expected, trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                .filter(authority -> authority != null
                        && "PROVIDER_TEXT_JSON".equals(authority.get("kind")))
                .count());
    }

    private static long events(LocalTurnTrace trace, String type) {
        return trace.events().stream().filter(event -> type.equals(event.type())).count();
    }

    private static String edit(String path, String oldText, String newText) throws Exception {
        return call("talos.edit_file", Map.of("path", path,
                "old_string", oldText, "new_string", newText));
    }

    private static String call(String name, Map<String, String> arguments) throws Exception {
        return "```json\n" + JSON.writeValueAsString(Map.of("name", name, "arguments", arguments)) + "\n```";
    }

    private record TurnRun(LlmClient.StreamResult initial, ToolCallLoop.LoopResult result,
                           LocalTurnTrace trace, dev.talos.runtime.task.TaskResolution resolution) {}

    private static final class RecordingGate implements ApprovalGate {
        private final List<ApprovalResponse> answers;
        private int calls;

        private RecordingGate(ApprovalResponse... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public boolean approve(String description, String detail) {
            return approveFull(description, detail).isApproved();
        }

        @Override
        public ApprovalResponse approveFull(String description, String detail) {
            assertTrue(calls < answers.size(), "Unexpected additional approval request.");
            return answers.get(calls++);
        }
    }
}
