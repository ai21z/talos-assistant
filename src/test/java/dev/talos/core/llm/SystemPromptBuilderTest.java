package dev.talos.core.llm;

import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SystemPromptBuilder}: composable system prompt assembly
 * with tool awareness and conversation history support.
 */
class SystemPromptBuilderTest {

    // ── Basic construction ──────────────────────────────────────────

    @Test
    void askModeProducesNonEmptyPrompt() {
        String prompt = SystemPromptBuilder.forAsk().build();
        assertNotNull(prompt);
        assertFalse(prompt.isBlank(), "ASK prompt should not be blank");
        assertTrue(prompt.contains("Talos"), "ASK prompt should mention Talos");
    }

    @Test
    void ragModeProducesNonEmptyPrompt() {
        String prompt = SystemPromptBuilder.forRag().build();
        assertNotNull(prompt);
        assertFalse(prompt.isBlank(), "RAG prompt should not be blank");
        assertTrue(prompt.contains("Talos"), "RAG prompt should mention Talos");
    }

    @Test
    void askAndRagProduceDifferentPrompts() {
        String ask = SystemPromptBuilder.forAsk().build();
        String rag = SystemPromptBuilder.forRag().build();
        assertNotEquals(ask, rag, "ASK and RAG prompts should differ");
    }

    // ── Tool awareness ──────────────────────────────────────────────

    @Test
    void noToolsSectionWhenRegistryIsEmpty() {
        String prompt = SystemPromptBuilder.forAsk()
                .withTools(new ToolRegistry())
                .build();
        assertFalse(prompt.contains("Available Tools"),
                "Should not include tools section when registry is empty");
    }

    @Test
    void noToolsSectionWhenRegistryIsNull() {
        String prompt = SystemPromptBuilder.forAsk()
                .withTools(null)
                .build();
        assertFalse(prompt.contains("Available Tools"),
                "Should not include tools section when registry is null");
    }

    @Test
    void toolsSectionIncludedWhenToolsRegistered() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("Available Tools"),
                "Should include tools preamble");
        assertTrue(prompt.contains("talos.read_file"),
                "Should include tool name");
        assertTrue(prompt.contains("Read a workspace file"),
                "Should include tool description");
    }

    @Test
    void toolsSectionIncludesMultipleTools() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file"));
        registry.register(stubTool("talos.grep", "Search workspace files"));
        registry.register(stubTool("talos.retrieve", "Retrieve context"));

        String prompt = SystemPromptBuilder.forRag()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("talos.read_file"));
        assertTrue(prompt.contains("talos.grep"));
        assertTrue(prompt.contains("talos.retrieve"));
    }

    @Test
    void toolsSectionIncludesParameterSchema() {
        var registry = new ToolRegistry();
        registry.register(new TalosTool() {
            @Override public String name() { return "talos.read_file"; }
            @Override public String description() { return "Read a file"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.read_file", "Read a file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
            }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.ok(""); }
        });

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("Parameters:"),
                "Should include parameters label when schema is present");
        assertTrue(prompt.contains("\"path\""),
                "Should include parameter schema content");
    }

    // ── Conversation history ────────────────────────────────────────

    @Test
    void noConversationSectionWhenHistoryFalse() {
        String prompt = SystemPromptBuilder.forAsk()
                .withHistory(false)
                .build();
        assertFalse(prompt.contains("Conversation Continuity"),
                "Should not include conversation section without history");
    }

    @Test
    void conversationSectionIncludedWhenHistoryTrue() {
        String prompt = SystemPromptBuilder.forAsk()
                .withHistory(true)
                .build();
        assertTrue(prompt.contains("Conversation Continuity"),
                "Should include conversation continuity section with history");
    }

    @Test
    void conversationSectionWorksWithRagMode() {
        String prompt = SystemPromptBuilder.forRag()
                .withHistory(true)
                .build();
        assertTrue(prompt.contains("Conversation Continuity"),
                "RAG mode should also support conversation section");
    }

    // ── Combined scenarios ──────────────────────────────────────────

    @Test
    void fullCompositionWithToolsAndHistory() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.grep", "Search workspace"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withHistory(true)
                .build();

        assertTrue(prompt.contains("Talos"), "Identity present");
        assertTrue(prompt.contains("Available Tools"), "Tools present");
        assertTrue(prompt.contains("talos.grep"), "Tool listed");
        assertTrue(prompt.contains("Conversation Continuity"), "Conversation present");
    }

    @Test
    void composedSectionsAreInCorrectOrder() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.grep", "Search workspace"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withHistory(true)
                .build();

        int identityPos = prompt.indexOf("Talos");
        int toolsPos = prompt.indexOf("Available Tools");
        int convPos = prompt.indexOf("Conversation Continuity");

        assertTrue(identityPos >= 0, "Identity section found");
        assertTrue(toolsPos >= 0, "Tools section found");
        assertTrue(convPos >= 0, "Conversation section found");
        assertTrue(identityPos < toolsPos,
                "Identity should come before tools");
        assertTrue(toolsPos < convPos,
                "Tools should come before conversation");
    }

    // ── Token estimation ────────────────────────────────────────────

    @Test
    void estimateTokensPositive() {
        int tokens = SystemPromptBuilder.forAsk().estimateTokens();
        assertTrue(tokens > 0, "Token estimate should be positive");
    }

    @Test
    void estimateTokensIncreasesWithTools() {
        int baseTokens = SystemPromptBuilder.forAsk().estimateTokens();

        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file"));
        registry.register(stubTool("talos.grep", "Search workspace files"));

        int toolTokens = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .estimateTokens();

        assertTrue(toolTokens > baseTokens,
                "Token estimate should increase when tools are added");
    }

    // ── toString ────────────────────────────────────────────────────

    @Test
    void toStringReflectsState() {
        var registry = new ToolRegistry();
        registry.register(stubTool("test", "test tool"));

        String str = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withHistory(true)
                .toString();

        assertTrue(str.contains("ASK"));
        assertTrue(str.contains("tools=true"));
        assertTrue(str.contains("history=true"));
    }

    @Test
    void toStringNoToolsNoHistory() {
        String str = SystemPromptBuilder.forRag().toString();
        assertTrue(str.contains("RAG"));
        assertTrue(str.contains("tools=false"));
        assertTrue(str.contains("history=false"));
    }

    // ── Resource loading ────────────────────────────────────────────

    @Test
    void readResourceReturnsNullForMissing() {
        assertNull(SystemPromptBuilder.readResource("prompts/sections/nonexistent.txt"));
    }

    @Test
    void readResourceFindsExistingSection() {
        String identity = SystemPromptBuilder.readResource("prompts/sections/identity.txt");
        assertNotNull(identity, "identity.txt should be loadable from classpath");
        assertTrue(identity.contains("Talos"));
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static TalosTool stubTool(String name, String description) {
        return new TalosTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public ToolDescriptor descriptor() { return new ToolDescriptor(name, description); }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.ok("stub"); }
        };
    }
}


