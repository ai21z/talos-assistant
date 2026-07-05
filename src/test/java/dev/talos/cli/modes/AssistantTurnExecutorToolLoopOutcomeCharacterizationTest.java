package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AssistantTurnExecutor tool-loop outcome characterization")
class AssistantTurnExecutorToolLoopOutcomeCharacterizationTest {

    @Test
    void postToolLoopSynthesisRetryRunsBeforeOutcomeShaping(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "PROJECT_MARKER = WAVE5-T814\n");
        ToolRegistry registry = registryWith(new ReadFileTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(readCall("call_read_notes", "notes.md"))),
                        new LlmClient.StreamResult("How can I help you with these files?", List.of()),
                        new LlmClient.StreamResult("The notes.md marker is WAVE5-T814.", List.of())),
                16_384);
        Context ctx = context(workspace, registry, recorded, 4);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Read notes.md and answer with the project marker from the file."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("WAVE5-T814"), out.text());
        assertFalse(out.text().contains("How can I help you with these files?"), out.text());
        assertTrue(recorded.requests().size() >= 3,
                "initial dispatch, post-tool-loop continuation, and synthesis retry must all dispatch");
        assertTrue(joinRequestMessages(recorded.requests().getLast()).contains(
                        "You already gathered the needed evidence using tools"),
                "the last provider call should be the post-tool synthesis retry");
    }

    @Test
    void postToolLoopMissingMutationRetryUsesRetryLoopEvidenceForFinalAnswer(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("problem.md"), "Create result.txt containing AFTER.\n");
        ToolRegistry registry = registryWith(new ReadFileTool(), new FileWriteTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(readCall("call_read_problem", "problem.md"))),
                        new LlmClient.StreamResult("Created result.txt.", List.of()),
                        new LlmClient.StreamResult("", List.of(writeCall(
                                "call_write_result",
                                "result.txt",
                                "AFTER"))),
                        new LlmClient.StreamResult("Created result.txt with AFTER.", List.of())),
                16_384);
        Context ctx = context(workspace, registry, recorded, 5);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Create result.txt containing exactly AFTER. Read problem.md first."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(Files.readString(workspace.resolve("result.txt")).contains("AFTER"));
        assertTrue(out.text().contains("[Used"), out.text());
        assertTrue(out.text().contains("talos.write_file"), out.text());
        assertFalse(out.text().contains("expected target was not successfully mutated"), out.text());
        assertTrue(recorded.requests().size() >= 3,
                "missing-mutation retry should make a provider request after the first tool loop");
        assertTrue(recorded.requests().stream()
                        .map(AssistantTurnExecutorToolLoopOutcomeCharacterizationTest::joinRequestMessages)
                        .anyMatch(prompt -> prompt.contains("[MutationRetryCapability]")),
                "retry dispatch must carry the bounded mutation retry frame");
    }

    @Test
    void postToolLoopInspectCompletenessRetryMergesRetryReadEvidenceAndSingleSummary(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <body>
                    <button class="cta-button">Run</button>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.cta-button').textContent = 'Ready';\n");
        ToolRegistry registry = registryWith(new ReadFileTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(readCall("call_read_index", "index.html"))),
                        new LlmClient.StreamResult("The HTML has a button.", List.of()),
                        new LlmClient.StreamResult("", List.of(readCall("call_read_script", "script.js"))),
                        new LlmClient.StreamResult("The button script sets the button text to Ready.", List.of())),
                16_384);
        Context ctx = context(workspace, registry, recorded, 5);

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Review this static web page and explain whether the button script is wired."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertTrue(out.text().contains("Ready"), out.text());
        assertEquals(1, countOccurrences(out.text(), "[Used "),
                "merged inspect-completeness retry evidence should surface one visible summary block");
        assertTrue(recorded.requests().stream()
                        .map(AssistantTurnExecutorToolLoopOutcomeCharacterizationTest::joinRequestMessages)
                        .anyMatch(prompt -> prompt.contains("Read these files now before answering: script.js")),
                "inspect-completeness retry must ask for the linked script that was missed initially");
    }

    @Test
    void postToolLoopDeniedMutationDoesNotTriggerMissingMutationRetry(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<div class=\"hero-content\">\n");
        ToolRegistry registry = registryWith(new FileWriteTool());
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(writeCall(
                                "call_write_index",
                                "index.html",
                                "<div class=\"hero-content cta-button\">\n"))),
                        new LlmClient.StreamResult("Updated index.html.", List.of())),
                16_384);
        var processor = new TurnProcessor(null, (description, detail) -> false, registry);
        Context ctx = Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 3))
                .nativeToolSpecs(specsFrom(registry))
                .build();

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Update index.html by adding the cta-button class to the hero-content div."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals("<div class=\"hero-content\">\n", Files.readString(workspace.resolve("index.html")));
        assertTrue(out.text().contains("No file changes were applied"), out.text());
        assertTrue(out.text().contains("approval was denied"), out.text());
        assertFalse(recorded.requests().stream()
                        .map(AssistantTurnExecutorToolLoopOutcomeCharacterizationTest::joinRequestMessages)
                        .anyMatch(prompt -> prompt.contains("[MutationRetryCapability]")),
                "approval-denied mutating outcomes must suppress bounded missing-mutation retry");
    }

    private static Context context(
            Path workspace,
            ToolRegistry registry,
            ScriptedNativeLlmClient.RecordedClient recorded,
            int maxIterations
    ) {
        return Context.builder(new Config())
                .llm(recorded.client())
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), maxIterations))
                .nativeToolSpecs(specsFrom(registry))
                .build();
    }

    private static ToolRegistry registryWith(dev.talos.tools.TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (dev.talos.tools.TalosTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ChatMessage.NativeToolCall readCall(String id, String path) {
        return new ChatMessage.NativeToolCall(
                id,
                "talos.read_file",
                Map.of("path", path));
    }

    private static ChatMessage.NativeToolCall writeCall(String id, String path, String content) {
        return new ChatMessage.NativeToolCall(
                id,
                "talos.write_file",
                Map.of("path", path, "content", content));
    }

    private static List<ToolSpec> specsFrom(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static String joinRequestMessages(ChatRequest request) {
        return request.messages.stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
