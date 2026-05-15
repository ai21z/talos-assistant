package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedReadScopeIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void private_mode_approved_protected_read_is_withheld_from_model_context() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

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
        assertFalse(transcript.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), transcript);
        assertFalse(transcript.contains("API_TOKEN="), transcript);
        assertTrue(transcript.contains("withheld from model context"), transcript);
        assertTrue(transcript.contains("LOCAL_DISPLAY_ONLY") || transcript.contains("withheld from model context"), transcript);
        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), result.finalAnswer());
    }

    @Test
    void developer_mode_approved_protected_read_can_reach_model_context_explicit_risk() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The approved file contained FILE_DISCOVERED_CANARY_SCOPE_ENV.")))
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
        assertTrue(transcript.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), transcript);
        assertTrue(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), result.finalAnswer());
    }

    @Test
    void private_mode_send_to_model_requires_explicit_opt_in() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", false)))));

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

        loop.run("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages, workspace, ctx);

        assertFalse(messages.toString().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), messages.toString());
        assertTrue(messages.toString().contains("withheld from model context"), messages.toString());
    }

    @Test
    void private_mode_send_to_model_opt_in_allows_handoff_but_persistence_redacts() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", true,
                        "persist_raw_artifacts", false)))));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 5);
        Context ctx = Context.builder(cfg)
                .llm(LlmClient.scripted(List.of("The approved file contained FILE_DISCOVERED_CANARY_SCOPE_ENV.")))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me the value."));

        loop.run("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                messages, workspace, ctx);

        assertTrue(messages.toString().contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), messages.toString());

        JsonSessionStore store = new JsonSessionStore(workspace.resolve("sessions"));
        store.appendTurn("sid-scope", new TurnRecord(
                1,
                Instant.parse("2026-05-15T00:00:00Z"),
                100,
                "Read .env",
                "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.read_file",
                        ".env",
                        true,
                        "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV")),
                1,
                1,
                0,
                "trace FILE_DISCOVERED_CANARY_SCOPE_ENV"));

        String jsonl = Files.readString(workspace.resolve("sessions").resolve("sid-scope.turns.jsonl"));
        assertFalse(jsonl.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), jsonl);
        assertFalse(jsonl.contains("t267-token-should-not-appear"), jsonl);
        assertTrue(jsonl.contains("API_TOKEN=[redacted]"), jsonl);
    }

    @Test
    void persist_raw_artifacts_false_even_when_send_to_model_true() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope", "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model", true,
                        "persist_raw_artifacts", false)))));

        assertTrue(dev.talos.runtime.policy.ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
        assertFalse(dev.talos.runtime.policy.ProtectedReadScopePolicy.persistRawArtifacts(cfg));
    }
}
