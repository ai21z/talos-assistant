package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A removal request keeps its operation through the real executor: a bound operand reaches the delete tool, an
 * unresolved one is answered without a model call, and content removal stays an edit (MICA invariant I2, P3).
 * The provider is scripted, the tools, approval-free processor and workspace are real.
 */
class OperationOperandExecutorTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void aKindNounRemovalDeletesTheOnlyPageThroughTheDeleteSurface(@TempDir Path workspace) throws Exception {
        site(workspace);
        String request = "please remove the page";
        var turn = turn(workspace, new SessionMemory(), request, List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html."));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(Files.exists(workspace.resolve("index.html")), turn.text());
        assertTrue(Files.exists(workspace.resolve("style.css")));
        assertTrue(turn.text().contains("[Used 1 tool(s): talos.delete_path"), turn.text());
        List<String> tools = firstRequestTools(turn);
        assertTrue(tools.contains("talos.delete_path"), tools.toString());
        assertFalse(tools.contains("talos.write_file"), tools.toString());
        assertFalse(tools.contains("talos.edit_file"), tools.toString());
        var resolved = trace.events().stream()
                .filter(event -> "OPERATION_OPERAND_RESOLVED".equals(event.type()))
                .toList();
        assertEquals(1, resolved.size(), trace.events().toString());
        assertEquals("KIND_NOUN", resolved.getFirst().data().get("operandClass"));
        assertEquals("BOUND", resolved.getFirst().data().get("outcome"));
    }

    @Test
    void twoPagesAreAskedAboutWithoutCallingTheModel(@TempDir Path workspace) throws Exception {
        site(workspace);
        Files.writeString(workspace.resolve("about.html"), "<p>About</p>");

        var turn = turn(workspace, new SessionMemory(), "please remove the page", List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"about.html\"}}"));

        assertEquals(0, turn.provider().requests().size());
        assertTrue(turn.text().contains("about.html"), turn.text());
        assertTrue(turn.text().contains("index.html"), turn.text());
        assertTrue(Files.exists(workspace.resolve("about.html")));
        assertTrue(Files.exists(workspace.resolve("index.html")));
    }

    @Test
    void aFileNounAfterAVerifiedChangeDeletesThatFileAndNeverWritesIt(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        var memory = new SessionMemory();
        memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(
                1, "trc-previous", List.of("index.html"), "VERIFIED"));

        var turn = turn(workspace, memory, "delete this file", List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html."));

        assertFalse(Files.exists(workspace.resolve("index.html")), turn.text());
        List<String> tools = firstRequestTools(turn);
        assertTrue(tools.contains("talos.delete_path"), tools.toString());
        assertFalse(tools.contains("talos.write_file"), tools.toString());
    }

    @Test
    void anAnaphorAfterAVerifiedChangeAsksBecauseItMayMeanTheContent(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        var memory = new SessionMemory();
        memory.setActiveTaskContext(ActiveTaskContext.verifiedMutation(
                1, "trc-previous", List.of("index.html"), "VERIFIED"));

        var turn = turn(workspace, memory, "actually, remove it", List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}"));

        assertEquals(0, turn.provider().requests().size());
        assertTrue(turn.text().contains("send: delete index.html"), turn.text());
        assertEquals("<main>Main</main>\n<footer>Footer</footer>\n",
                Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void aRemovalTurnDropsAPendingSaveClarificationSoALaterYesCannotSaveIt(@TempDir Path workspace)
            throws Exception {
        site(workspace);
        var memory = new SessionMemory();
        var pending = dev.talos.runtime.context.ContentSaveContinuation.evaluate(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(
                        "Save a story about a woman in medieval times."),
                null, workspace, 1, dev.talos.runtime.policy.CapabilityPosture.AGENT).nextPending();
        assertTrue(pending != null);
        memory.setPendingContentSave(pending);

        var turn = turn(workspace, memory, "delete it", List.of("unused"));

        assertEquals(0, turn.provider().requests().size());
        assertEquals(null, memory.pendingContentSave());
    }

    @Test
    void aPastedCommandWithAComplaintReachesTheModelUnchanged(@TempDir Path workspace) throws Exception {
        site(workspace);
        String request = "rm notes.txt gives permission denied. Why?";

        var turn = turn(workspace, new SessionMemory(), request, List.of(
                "The file is probably read-only."));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, turn.provider().requests().size(), turn.text());
        var sent = turn.provider().requests().getFirst();
        String sentText = String.valueOf(sent.userPrompt) + sent.messages.stream()
                .map(ChatMessage::content)
                .reduce("", String::concat);
        assertTrue(sentText.contains(request), sentText);
        assertTrue(trace.events().stream().noneMatch(event -> "OPERATION_OPERAND_RESOLVED".equals(event.type())),
                trace.events().toString());
    }

    @Test
    void anAnaphorWithoutAReferentIsAnsweredAndNothingChanges(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, new SessionMemory(), "delete it", List.of(
                "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"index.html\",\"content\":\"\"}}"));

        assertEquals(0, turn.provider().requests().size());
        assertTrue(turn.text().contains("Talos did not delete anything."), turn.text());
        assertEquals("<main>Main</main>\n<footer>Footer</footer>\n",
                Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void aBulkDeleteRequestIsAnsweredAsNotPerformedAndNotExplained(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, new SessionMemory(),
                "Can you completely DELETE all the files in this workspace?",
                List.of("This workspace is a small static site."));

        assertEquals(0, turn.provider().requests().size());
        assertTrue(turn.text().contains("one named path per request"), turn.text());
        assertTrue(turn.text().contains("index.html"), turn.text());
        assertTrue(Files.exists(workspace.resolve("index.html")));
        assertTrue(Files.exists(workspace.resolve("style.css")));
    }

    @Test
    void eraseUsesTheDeleteTool(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, new SessionMemory(), "erase index.html", List.of(
                "{\"name\":\"talos.delete_path\",\"arguments\":{\"path\":\"index.html\"}}",
                "Deleted index.html."));

        assertFalse(Files.exists(workspace.resolve("index.html")), turn.text());
        assertTrue(firstRequestTools(turn).contains("talos.delete_path"), firstRequestTools(turn).toString());
    }

    @Test
    void contentRemovalFromANamedFileEditsThatFile(@TempDir Path workspace) throws Exception {
        site(workspace);

        var turn = turn(workspace, new SessionMemory(), "remove the footer from index.html", List.of(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"index.html\"}}",
                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"index.html\","
                        + "\"old_string\":\"<footer>Footer</footer>\\n\",\"new_string\":\"\"}}",
                "Removed the footer from index.html."));

        List<String> tools = firstRequestTools(turn);
        assertTrue(tools.contains("talos.edit_file"), tools.toString());
        assertFalse(tools.contains("talos.delete_path"), tools.toString());
        assertEquals("<main>Main</main>\n", Files.readString(workspace.resolve("index.html")), turn.text());
    }

    private record Turn(
            AssistantTurnExecutor.TurnOutput output,
            ScriptedNativeLlmClient.RecordedClient provider,
            dev.talos.runtime.TurnAudit audit) {
        String text() {
            return output.text();
        }
    }

    private static Turn turn(Path workspace, SessionMemory memory, String request, List<String> responses) {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin(
                "trc-operand", "sid", 1, "2026-10-27T00:00:00Z", "workspace-hash", "test", "llama_cpp",
                "test-model", request);
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        registry.register(new dev.talos.tools.impl.DeletePathTool());
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 6);
        var provider = recordingProvider(registry, responses);
        var ctx = Context.builder(new Config())
                .llm(provider.client())
                .memory(memory)
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(request));
        dev.talos.runtime.TurnAuditCapture.begin();
        try {
            var output = AssistantTurnExecutor.execute(
                    messages, workspace, ctx, new AssistantTurnExecutor.Options());
            return new Turn(output, provider, dev.talos.runtime.TurnAuditCapture.end());
        } finally {
            if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
        }
    }

    /** The tools the planned turn offered the model, natively or in the prompt. */
    private static List<String> firstRequestTools(Turn turn) {
        assertFalse(turn.provider().requests().isEmpty(), turn.text());
        var policy = turn.audit().policyTrace();
        assertTrue(policy != null, turn.text());
        var tools = new ArrayList<String>(policy.nativeTools());
        tools.addAll(policy.promptTools());
        return tools;
    }

    private static ScriptedNativeLlmClient.RecordedClient recordingProvider(
            ToolRegistry registry, List<String> responses) {
        List<LlmClient.StreamResult> results = responses.stream()
                .map(response -> new LlmClient.StreamResult(response, List.of()))
                .toList();
        var recorded = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(results, 16_384);
        recorded.client().setToolSpecs(registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema() == null ? "{}" : descriptor.parametersSchema()))
                .toList());
        return recorded;
    }

    private static void site(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n<footer>Footer</footer>\n");
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
    }
}
