package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.SessionMemory;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A surface that a mode pinned for the turn is dispatched only when it carries the exact tool of the authorized
 * workspace operation (P3d, MICA runtime-owned facts). The executor is entered as a mode calls it, with the pin in
 * the context. The provider is scripted, the tools and workspace are real.
 */
class PinnedOperationSurfaceTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
        if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
    }

    @Test
    void aPinnedSurfaceWithoutTheExactOperationToolIsReselectedForTheOperation(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n");
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        registry.register(new dev.talos.tools.impl.ListDirTool());
        registry.register(new dev.talos.tools.impl.DeletePathTool());
        List<ToolSpec> all = registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                .toList();
        Set<String> contentOnly = Set.of("talos.read_file", "talos.write_file", "talos.edit_file", "talos.list_dir");
        List<ToolSpec> pinned = all.stream().filter(spec -> contentOnly.contains(spec.name())).toList();
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.delete_path", Map.of("path", "index.html"))), "tool_calls"),
                new LlmClient.StreamResult("Deleted index.html.", List.of(), "stop")), 16_384);
        provider.client().setToolSpecs(all);
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var ctx = Context.builder(new Config())
                .llm(provider.client())
                .memory(new SessionMemory())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 6))
                .build()
                .withNativeToolSpecs(pinned);
        String request = "delete index.html";
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(request));
        LocalTurnTraceCapture.begin(
                "trc-pinned", "sid", 1, "2026-10-31T00:00:00Z", "workspace-hash", "test", "llama_cpp",
                "test-model", request);
        dev.talos.runtime.TurnAuditCapture.begin();

        var output = AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertFalse(provider.requests().isEmpty(), output.text());
        assertEquals(List.of("talos.delete_path"),
                provider.requests().getFirst().tools.stream().map(ToolSpec::name).toList(), output.text());
        assertFalse(Files.exists(workspace.resolve("index.html")), output.text());
    }

    @Test
    void aReselectedOperationSurfaceIsDescribedByItsOwnPrompt(@TempDir Path workspace) throws Exception {
        // The pin, primary prompt and frame are those a mode builds for an empty surface, entered with a typed delete.
        // No current mode pins that surface for a typed delete without replacing the request, so the state is
        // synthetic. The reselected surface must not travel with prose that says no tools are listed.
        Files.writeString(workspace.resolve("index.html"), "<main>Main</main>\n");
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.DeletePathTool());
        List<ToolSpec> all = registry.descriptors().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.parametersSchema()))
                .toList();
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.delete_path", Map.of("path", "index.html"))), "tool_calls"),
                new LlmClient.StreamResult("Deleted index.html.", List.of(), "stop")), 16_384);
        provider.client().setToolSpecs(all);
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var ctx = Context.builder(new Config())
                .llm(provider.client())
                .memory(new SessionMemory())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new dev.talos.runtime.ToolCallLoop(processor, 6))
                .build()
                .withNativeToolSpecs(List.of());
        String request = "delete index.html";
        String modePrompt = dev.talos.core.llm.SystemPromptBuilder.forUnified().withNativeTools(true).build();
        assertTrue(modePrompt.contains("If no tools are listed"), modePrompt);
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(modePrompt));
        messages.add(ChatMessage.user(request));
        dev.talos.runtime.policy.CurrentTurnPromptInstructions.injectTaskContractInstruction(
                messages, null, dev.talos.runtime.phase.ExecutionPhase.INSPECT, List.of());
        LocalTurnTraceCapture.begin(
                "trc-pinned-prompt", "sid", 1, "2026-10-31T00:00:00Z", "workspace-hash", "test", "llama_cpp",
                "test-model", request);
        dev.talos.runtime.TurnAuditCapture.begin();

        var output = AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertFalse(provider.requests().isEmpty(), output.text());
        var first = provider.requests().getFirst();
        assertEquals(List.of("talos.delete_path"), first.tools.stream().map(ToolSpec::name).toList(), output.text());
        String system = first.messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(system.contains("If no tools are listed"), system);
        assertTrue(system.contains("- **talos.delete_path**"), system);
        assertFalse(Files.exists(workspace.resolve("index.html")), output.text());
    }
}
