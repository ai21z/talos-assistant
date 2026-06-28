package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.Result;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanModeTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void nameIsPlan() {
        assertEquals("plan", new PlanMode().name());
    }

    @Test
    void mutationShapedPromptProducesPlanWithReadOnlySurface() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                engineConfig(),
                List.of(new LlmClient.StreamResult("1. Inspect README.md\n2. Draft the change\n3. Switch to Agent to apply.", List.of())),
                8192);
        var ctx = Context.builder(engineConfig())
                .llm(recorded.client())
                .toolRegistry(toolRegistry())
                .build();

        Optional<Result> result = new PlanMode().handle("Plan how to add a brief project note.", WS, ctx);

        assertTrue(result.isPresent());
        assertFalse(recorded.requests().isEmpty(), result.get().toString());
        ChatRequest request = recorded.requests().getFirst();
        assertFalse(toolNames(request).contains("talos.write_file"), toolNames(request).toString());
        assertFalse(toolNames(request).contains("talos.edit_file"), toolNames(request).toString());
        assertFalse(toolNames(request).contains("talos.run_command"), toolNames(request).toString());
        String prompt = joinedMessages(request);
        assertTrue(prompt.contains("Behavior Rules (Plan Mode)"), prompt);
        assertTrue(prompt.contains("produce a concrete implementation plan"), prompt);
        assertTrue(prompt.contains("Plan is read-only"), prompt);
        assertTrue(prompt.contains("switch to `/mode agent`"), prompt);
        assertFalse(prompt.contains("- **talos.write_file**"), prompt);
        assertFalse(prompt.contains("- **talos.edit_file**"), prompt);
        assertFalse(prompt.contains("- **talos.run_command**"), prompt);
    }

    @Test
    void commandRequestDoesNotExposeRunCommandInPlanMode() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                engineConfig(),
                List.of(new LlmClient.StreamResult("Plan the verification steps without running commands.", List.of())),
                8192);
        var ctx = Context.builder(engineConfig())
                .llm(recorded.client())
                .toolRegistry(toolRegistry())
                .build();

        new PlanMode().handle("Run Gradle check and plan any fixes.", WS, ctx);

        ChatRequest request = recorded.requests().getFirst();
        assertFalse(toolNames(request).contains("talos.run_command"), toolNames(request).toString());
        String prompt = joinedMessages(request);
        assertFalse(prompt.contains("- **talos.run_command**"), prompt);
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("native_calling", true);
        cfg.data.put("tools", tools);
        return cfg;
    }

    private static ToolRegistry toolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new RunCommandTool());
        return registry;
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.tools.stream().map(ToolSpec::name).sorted().toList();
    }

    private static String joinedMessages(ChatRequest request) {
        return request.messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
