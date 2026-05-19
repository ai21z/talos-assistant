package dev.talos.harness;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateModeScriptedE2eTest {

    @TempDir
    Path workspace;

    @Test
    void private_mode_read_env_approved_local_display_only_does_not_enter_model_context() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_E2E_ENV\n");

        ToolCallLoop.LoopResult result = runPrivateTurn(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                List.of("I cannot see the raw protected value."));

        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_E2E_ENV"), result.finalAnswer());
        String modelLoopMessages = result.messages().toString();
        assertFalse(modelLoopMessages.contains("FILE_DISCOVERED_CANARY_E2E_ENV"), modelLoopMessages);
        assertTrue(modelLoopMessages.contains("withheld from model context"), modelLoopMessages);
    }

    @Test
    void private_mode_grep_env_canary_omits_result() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_E2E_GREP\n");
        Files.writeString(workspace.resolve("README.md"), "public text\n");

        ToolCallLoop.LoopResult result = runPrivateTurn(
                "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\"FILE_DISCOVERED_CANARY_E2E_GREP\"}}",
                List.of("No raw protected value was returned."));

        String combined = result.finalAnswer() + "\n" + result.summary();
        assertFalse(combined.contains("FILE_DISCOVERED_CANARY_E2E_GREP"), combined);
        assertTrue(combined.contains("protected content") || combined.contains("protected"), combined);
    }

    @Test
    void private_mode_grep_canary_match_withholds_neighbor_fields() throws Exception {
        Files.writeString(
                workspace.resolve("bank.csv"),
                "account,balance,note\nchecking,4812.44,FILE_DISCOVERED_CANARY_E2E_GREP_ROW\n");

        ToolCallLoop.LoopResult result = runPrivateTurn(
                "{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\"FILE_DISCOVERED_CANARY_E2E_GREP_ROW\"}}",
                List.of("No raw private row was returned."));

        String toolResultMessages = result.messages().stream()
                .map(ChatMessage::content)
                .filter(content -> content != null && content.contains("[tool_result"))
                .collect(Collectors.joining("\n"));
        assertFalse(toolResultMessages.contains("FILE_DISCOVERED_CANARY_E2E_GREP_ROW"), toolResultMessages);
        assertFalse(toolResultMessages.contains("4812.44"), toolResultMessages);
        assertFalse(toolResultMessages.contains("checking"), toolResultMessages);
        assertTrue(toolResultMessages.contains("withheld by private-mode search policy"), toolResultMessages);
    }

    private ToolCallLoop.LoopResult runPrivateTurn(String scriptedToolCall, List<String> followUps) throws Exception {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private"));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new GrepTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(followUps))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("harness"));
        messages.add(ChatMessage.user("private mode scripted e2e"));

        return loop.run(scriptedToolCall, messages, workspace, ctx);
    }
}
