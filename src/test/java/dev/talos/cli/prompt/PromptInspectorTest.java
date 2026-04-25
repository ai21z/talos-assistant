package dev.talos.cli.prompt;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    private static Context context(Config cfg) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        return Context.builder(cfg)
                .toolRegistry(registry)
                .build();
    }
}
