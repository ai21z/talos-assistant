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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        assertFalse(prompt.contains("You CAN create files"), prompt);
        assertFalse(prompt.contains("talos.write_file tool that writes files"), prompt);
        assertFalse(prompt.contains("When the user asks you to create or write a file, call talos.write_file"),
                prompt);
        assertFalse(prompt.contains("Never say \"I cannot create files\""), prompt);
    }

    @Test
    void createOnlyPlanTargetDoesNotBecomeReadTargetInPromptFrame() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                engineConfig(),
                List.of(new LlmClient.StreamResult("1. Create plan-only.txt in Agent mode.\n2. Verify content.", List.of())),
                8192);
        var ctx = Context.builder(engineConfig())
                .llm(recorded.client())
                .toolRegistry(toolRegistry())
                .build();

        Optional<Result> result = new PlanMode().handle(
                "Plan how to add a new file plan-only.txt with exactly PLAN ONLY. Do not edit anything.",
                WS,
                ctx);

        assertTrue(result.isPresent());
        ChatRequest request = recorded.requests().getFirst();
        assertFalse(toolNames(request).contains("talos.write_file"), toolNames(request).toString());
        assertFalse(toolNames(request).contains("talos.edit_file"), toolNames(request).toString());
        String prompt = joinedMessages(request);
        assertFalse(prompt.contains("evidenceObligation: READ_TARGET_REQUIRED"), prompt);
        assertFalse(prompt.contains("read the named target before answering"), prompt);
        assertFalse(prompt.contains("requiredTargets: plan-only.txt"), prompt);
    }

    @Test
    void commandRequestReturnsReadOnlyNudgeWithoutCallingLlm() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                engineConfig(),
                List.of(new LlmClient.StreamResult("should not be called", List.of())),
                8192);
        var ctx = Context.builder(engineConfig())
                .llm(recorded.client())
                .toolRegistry(toolRegistry())
                .build();

        Optional<Result> result = new PlanMode().handle(
                "Run the command Get-ChildItem -Name to list workspace files. "
                        + "If Plan mode cannot run commands, say so plainly and do not inspect files just to compensate.",
                WS,
                ctx);

        assertTrue(result.isPresent());
        String body = result.get().toString();
        assertTrue(body.contains("Plan is read-only"), body);
        assertTrue(body.contains("cannot run commands"), body);
        assertTrue(body.contains("/mode agent"), body);
        assertTrue(recorded.requests().isEmpty(), "Plan command refusal must not call the LLM");
    }

    @Test
    void noToolDirectAnswerPromptDoesNotInjectWorkspaceManifest(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), """
                # Plan fixture
                Visible project codename: PLAN-ARC-42
                """);
        Files.writeString(workspace.resolve(".env"), "TALOS_FAKE_SECRET=must-not-enter-prompt\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                engineConfig(),
                List.of(new LlmClient.StreamResult(
                        "I cannot verify the codename without inspecting files.",
                        List.of())),
                8192);
        var ctx = Context.builder(engineConfig())
                .llm(recorded.client())
                .toolRegistry(toolRegistry())
                .build();

        Optional<Result> result = new PlanMode().handle(
                "Without reading or listing any files, tell me the workspace codename. "
                        + "If you cannot know it from current verified evidence, say exactly: "
                        + "I cannot verify the codename without inspecting files.",
                workspace,
                ctx);

        assertTrue(result.isPresent());
        assertEquals(1, recorded.requests().size());
        ChatRequest request = recorded.requests().getFirst();
        assertTrue(toolNames(request).isEmpty(), toolNames(request).toString());
        String prompt = joinedMessages(request);
        assertFalse(prompt.contains("File structure:"), prompt);
        assertFalse(prompt.contains("README (excerpt):"), prompt);
        assertFalse(prompt.contains("PLAN-ARC-42"), prompt);
        assertFalse(prompt.contains(".env"), prompt);
        assertFalse(prompt.contains("must-not-enter-prompt"), prompt);
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
