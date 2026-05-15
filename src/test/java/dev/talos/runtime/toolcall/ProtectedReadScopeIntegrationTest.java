package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadScopeIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void approved_protected_read_local_display_only_does_not_enter_model_context() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_T275_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private"));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("I cannot see the raw protected value.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        ToolCallLoop.LoopResult result = loop.run(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages,
                workspace,
                ctx);

        String transcript = messages.toString();
        assertFalse(transcript.contains("FILE_DISCOVERED_CANARY_T275_ENV"), transcript);
        assertFalse(transcript.contains("API_TOKEN="), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_T275_ENV"), result.finalAnswer());
    }
}
