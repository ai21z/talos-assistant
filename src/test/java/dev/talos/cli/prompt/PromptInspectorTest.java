package dev.talos.cli.prompt;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptInspectorTest {

    @Test
    void renderNextAutoUsesUnifiedPromptWithMetadata() {
        Context ctx = context(new Config());

        PromptRender render = PromptInspector.renderNext(
                "auto",
                "Check the workspace.",
                Path.of(".").toAbsolutePath().normalize(),
                ctx);

        assertEquals("auto", render.requestedMode());
        assertEquals("unified", render.resolvedMode());
        assertEquals(0, render.historyMessages());
        assertTrue(render.tools().contains("talos.read_file"));
        assertTrue(render.sections().contains("mode:unified"));
        assertTrue(render.sections().contains("tools:native"));
        assertTrue(render.systemPrompt().contains("Available Tools"));
        assertEquals("user", render.messages().get(render.messages().size() - 1).role());
        assertEquals("Check the workspace.", render.messages().get(render.messages().size() - 1).content());
    }

    @Test
    void renderNextCanShowTextFallbackToolPreamble() {
        Config cfg = new Config();
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("native_calling", false);
        cfg.data.put("tools", tools);

        PromptRender render = PromptInspector.renderNext(
                "ask",
                "",
                Path.of(".").toAbsolutePath().normalize(),
                context(cfg));

        assertEquals("ask", render.resolvedMode());
        assertFalse(render.nativeTools());
        assertTrue(render.sections().contains("tools:text-fallback"));
        assertTrue(render.systemPrompt().contains("```json"));
        assertEquals(PromptInspector.DEFAULT_INPUT_PLACEHOLDER,
                render.messages().get(render.messages().size() - 1).content());
    }

    @Test
    void formatIncludesPromptStatsAndMessages() {
        PromptRender render = PromptInspector.renderNext(
                "rag",
                "Explain README.md",
                Path.of(".").toAbsolutePath().normalize(),
                context(new Config()));

        String formatted = PromptInspector.format(render);

        assertTrue(formatted.contains("# Talos Prompt Render"));
        assertTrue(formatted.contains("Resolved prompt mode: rag"));
        assertTrue(formatted.contains("Prompt chars:"));
        assertTrue(formatted.contains("## Messages"));
        assertTrue(formatted.contains("Explain README.md"));
    }

    @Test
    void lastPromptCaptureStoresMostRecentRender() {
        LastPromptCapture.clear();
        PromptRender render = PromptInspector.renderNext(
                "auto",
                "hello",
                Path.of(".").toAbsolutePath().normalize(),
                context(new Config()));

        LastPromptCapture.record(render);

        assertTrue(LastPromptCapture.latest().isPresent());
        assertEquals("hello", LastPromptCapture.latest().orElseThrow()
                .messages().getLast().content());
    }

    @Test
    void renderNextSmallTalkMatchesNoToolRuntimeSurface() {
        PromptRender render = PromptInspector.renderNext(
                "auto",
                "hello",
                Path.of(".").toAbsolutePath().normalize(),
                fullToolContext(new Config()));

        assertEquals("SMALL_TALK", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().isEmpty());
        assertTrue(render.registryTools().contains("talos.read_file"));
        assertTrue(render.registryTools().contains("talos.write_file"));
        assertFalse(render.sections().contains("tools:native"));
        assertFalse(render.sections().contains("workspace"));
        assertFalse(render.systemPrompt().contains("Available Tools"));
        assertTrue(render.messages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("type: SMALL_TALK")
                        && message.content().contains("Do not call tools")));
    }

    @Test
    void renderNextReadOnlyWorkspacePromptShowsReadOnlyEffectiveTools() {
        PromptRender render = PromptInspector.renderNext(
                "auto",
                "What is in this workspace?",
                Path.of(".").toAbsolutePath().normalize(),
                fullToolContext(new Config()));

        assertEquals("WORKSPACE_EXPLAIN", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.read_file"));
        assertFalse(render.tools().contains("talos.write_file"));
        assertTrue(render.registryTools().contains("talos.write_file"));
        assertTrue(render.sections().contains("tools:native"));
        assertTrue(render.systemPrompt().contains("Only inspection tools"));
    }

    @Test
    void renderNextMutationPromptShowsWritableEffectiveTools() {
        PromptRender render = PromptInspector.renderNext(
                "auto",
                "Create a README.md file.",
                Path.of(".").toAbsolutePath().normalize(),
                fullToolContext(new Config()));

        assertEquals("FILE_CREATE", render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.read_file"));
        assertTrue(render.tools().contains("talos.write_file"));
        assertTrue(render.tools().contains("talos.edit_file"));
        assertFalse(render.messages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("[TaskContract]")));
    }

    @Test
    void fromMessagesReportsPerTurnNativeToolSurfaceWhenPresent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(new FileUndoStack()));
        Context ctx = Context.builder(new Config())
                .toolRegistry(registry)
                .nativeToolSpecs(List.of(new ToolSpec("talos.read_file", "Read", "{}")))
                .build();

        PromptRender render = PromptInspector.fromMessages(
                "auto",
                "unified",
                Path.of(".").toAbsolutePath().normalize(),
                ctx,
                true,
                0,
                List.of(ChatMessage.system("system"), ChatMessage.user("hello")));

        assertTrue(render.tools().contains("talos.read_file"));
        assertFalse(render.tools().contains("talos.write_file"));
    }

    @Test
    void fromMessagesDoesNotReportToolSectionWhenNativeOverrideIsEmpty() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(new FileUndoStack()));
        Context ctx = Context.builder(new Config())
                .toolRegistry(registry)
                .nativeToolSpecs(List.of())
                .build();

        PromptRender render = PromptInspector.fromMessages(
                "auto",
                "unified",
                Path.of(".").toAbsolutePath().normalize(),
                ctx,
                true,
                0,
                List.of(
                        ChatMessage.system("system"),
                        ChatMessage.system("""
                                [TaskContract]
                                type: SMALL_TALK
                                mutationAllowed: false
                                Answer directly. Do not call tools."""),
                        ChatMessage.user("hello")));

        assertEquals("SMALL_TALK", render.taskType());
        assertTrue(render.tools().isEmpty());
        assertFalse(render.sections().contains("tools:native"));
    }

    private static Context context(Config cfg) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        return Context.builder(cfg)
                .toolRegistry(registry)
                .build();
    }

    private static Context fullToolContext(Config cfg) {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return Context.builder(cfg)
                .toolRegistry(registry)
                .build();
    }
}
