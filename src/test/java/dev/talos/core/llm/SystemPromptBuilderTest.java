package dev.talos.core.llm;

import dev.talos.tools.*;
import dev.talos.tools.impl.RunCommandTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
    void defaultIdentityPromptIsBackendNeutral() {
        String prompt = SystemPromptBuilder.forAsk().build();

        assertFalse(prompt.contains("Ollama"),
                "Default model-facing identity prompt should not name an engine-specific backend");
        assertTrue(prompt.contains("configured runtime and tools"),
                "Default identity prompt should preserve configured-runtime semantics without naming Ollama");
        assertTrue(prompt.contains("tool-mediated"),
                "Default identity prompt should describe workspace access as tool-mediated");
        assertFalse(prompt.contains("never exfiltrate"),
                "Default identity prompt should not make absolute data-exfiltration guarantees");
        assertFalse(prompt.contains("full access"),
                "Default identity prompt should not claim unrestricted workspace access");
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
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok(""); }
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

    // ── Workspace awareness ─────────────────────────────────────────

    @Test
    void withWorkspaceInjectsPathIntoPrompt() {
        Path ws = Path.of("/home/user/my-project");
        String prompt = SystemPromptBuilder.forAsk()
                .withWorkspace(ws)
                .build();

        assertTrue(prompt.contains("Workspace:"),
                "Prompt should contain 'Workspace:' label");
        assertTrue(prompt.contains("my-project"),
                "Prompt should contain the workspace path");
    }

    @Test
    void withWorkspaceNullIsNoOp() {
        String withNull = SystemPromptBuilder.forAsk()
                .withWorkspace(null)
                .build();
        String without = SystemPromptBuilder.forAsk().build();

        assertEquals(without, withNull,
                "null workspace should produce identical prompt");
    }

    @Test
    void workspaceAppearsBeforeModeRules() {
        Path ws = Path.of("/tmp/test-ws");
        String prompt = SystemPromptBuilder.forAsk()
                .withWorkspace(ws)
                .build();

        int wsPos = prompt.indexOf("Workspace:");
        int rulesPos = prompt.indexOf("Behavior Rules");

        assertTrue(wsPos >= 0, "Workspace label should be present");
        assertTrue(rulesPos >= 0, "Mode rules should be present");
        assertTrue(wsPos < rulesPos,
                "Workspace should appear before mode rules");
    }

    @Test
    void withWorkspaceWorksWithRagMode() {
        Path ws = Path.of("/tmp/rag-ws");
        String prompt = SystemPromptBuilder.forRag()
                .withWorkspace(ws)
                .build();

        assertTrue(prompt.contains("Workspace:"),
                "RAG prompt should also include workspace");
        assertTrue(prompt.contains("rag-ws"),
                "RAG prompt should contain the workspace name");
    }

    @Test
    void withWorkspaceWorksWithToolsAndHistory() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.grep", "Search workspace"));

        Path ws = Path.of("/tmp/full-ws");
        String prompt = SystemPromptBuilder.forAsk()
                .withWorkspace(ws)
                .withTools(registry)
                .withHistory(true)
                .build();

        assertTrue(prompt.contains("Workspace:"), "Workspace present");
        assertTrue(prompt.contains("Available Tools"), "Tools present");
        assertTrue(prompt.contains("Conversation Continuity"), "Conversation present");

        // Verify order: identity < workspace < rules < tools < conversation
        int wsPos = prompt.indexOf("Workspace:");
        int toolsPos = prompt.indexOf("Available Tools");
        assertTrue(wsPos < toolsPos,
                "Workspace should appear before tools section");
    }

    // ── Native tools (PR-5) ─────────────────────────────────────────

    @Test
    void nativeToolsOmitsXmlFormatInstructions() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(true)
                .build();

        assertFalse(prompt.contains("<tool_call>"),
                "Native mode should NOT contain <tool_call> XML tags");
        assertFalse(prompt.contains("</tool_call>"),
                "Native mode should NOT contain </tool_call> closing tag");
        assertFalse(prompt.contains("You MUST use <tool_call>"),
                "Native mode should NOT require XML format");
        assertTrue(prompt.contains("Available Tools"),
                "Native mode should still have tools preamble");
    }

    @Test
    void fallbackToolsIncludesJsonFormatInstructions() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(false)
                .build();

        // Fallback should use JSON code-fenced format, not XML
        assertFalse(prompt.contains("<tool_call>"),
                "Fallback mode should NOT contain XML <tool_call> tags");
        assertTrue(prompt.contains("```json"),
                "Fallback mode should contain ```json code fence examples");
        assertTrue(prompt.contains("\"name\""),
                "Fallback mode should contain JSON format instructions");
        assertTrue(prompt.contains("Available Tools"),
                "Fallback mode should have tools preamble");
    }

    @Test
    void nativeToolsStillIncludesFileCreationRules() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.write_file", "Create or overwrite a file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(true)
                .build();

        assertTrue(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Native mode should still include critical file creation rules");
        assertTrue(prompt.contains("talos.write_file"),
                "Native mode should still mention write_file");
        assertTrue(prompt.contains("NEVER say \"I cannot create files\"")
                        || prompt.contains("You CAN create files"),
                "Native mode should reinforce file creation capability");
    }

    @Test
    void readOnlyToolModeOmitsMutatingToolDescriptors() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file", ToolRiskLevel.READ_ONLY));
        registry.register(stubTool("talos.write_file", "Create or overwrite a file", ToolRiskLevel.WRITE));
        registry.register(stubTool("talos.edit_file", "Replace a unique string", ToolRiskLevel.WRITE));

        String prompt = SystemPromptBuilder.forUnified()
                .withTools(registry)
                .withReadOnlyToolMode(true)
                .build();

        assertTrue(prompt.contains("Only inspection tools"),
                "Read-only mode should use read-only tool guidance");
        assertTrue(prompt.contains("Current Turn Contract"),
                "Read-only mode should include an explicit current-turn contract");
        assertTrue(prompt.contains("- **talos.read_file**"),
                "Read-only mode should keep inspection tool descriptors");
        assertFalse(prompt.contains("- **talos.write_file**"),
                "Read-only mode should not list write_file as an available tool descriptor");
        assertFalse(prompt.contains("- **talos.edit_file**"),
                "Read-only mode should not list edit_file as an available tool descriptor");
        assertFalse(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Read-only mode should not use the writable tool preamble");
    }

    @Test
    void nativeReadOnlyToolModeOmitsMutatingToolDescriptors() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.grep", "Search workspace files", ToolRiskLevel.READ_ONLY));
        registry.register(stubTool("talos.edit_file", "Replace a unique string", ToolRiskLevel.WRITE));

        String prompt = SystemPromptBuilder.forUnified()
                .withTools(registry)
                .withNativeTools(true)
                .withReadOnlyToolMode(true)
                .build();

        assertTrue(prompt.contains("Only inspection tools"),
                "Native read-only mode should use read-only tool guidance");
        assertTrue(prompt.contains("- **talos.grep**"),
                "Native read-only mode should keep read-only tool descriptors");
        assertFalse(prompt.contains("- **talos.edit_file**"),
                "Native read-only mode should filter mutating tool descriptors");
        assertFalse(prompt.contains("runtime handles tool invocation format automatically — just decide WHICH tool"),
                "Native read-only mode should not use the writable native preamble");
    }

    @Test
    void verificationCommandModeKeepsRunCommandAndOmitsMutationTools() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file", ToolRiskLevel.READ_ONLY));
        registry.register(stubTool("talos.write_file", "Create or overwrite a file", ToolRiskLevel.WRITE));
        registry.register(new RunCommandTool());

        String prompt = SystemPromptBuilder.forUnified()
                .withTools(registry)
                .withReadOnlyToolMode(true)
                .withCommandToolMode(true)
                .build();

        assertTrue(prompt.contains("verification-oriented"),
                "Verification command mode should use verification-oriented guidance");
        assertTrue(prompt.contains("approved command verification tools"),
                "Verification command mode should explain command tools are constrained");
        assertTrue(prompt.contains("- **talos.read_file**"),
                "Verification command mode should keep inspection tool descriptors");
        assertTrue(prompt.contains("- **talos.run_command**"),
                "Verification command mode should expose approved command profiles");
        assertFalse(prompt.contains("- **talos.write_file**"),
                "Verification command mode should not expose source mutation tools");
        assertFalse(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Verification command mode should not use the writable tool preamble");
    }

    @Test
    void nativeVerificationCommandModeKeepsRunCommandAndOmitsMutationTools() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.grep", "Search workspace files", ToolRiskLevel.READ_ONLY));
        registry.register(stubTool("talos.edit_file", "Replace a unique string", ToolRiskLevel.WRITE));
        registry.register(new RunCommandTool());

        String prompt = SystemPromptBuilder.forUnified()
                .withTools(registry)
                .withNativeTools(true)
                .withReadOnlyToolMode(true)
                .withCommandToolMode(true)
                .build();

        assertTrue(prompt.contains("verification-oriented"),
                "Native verification command mode should use verification-oriented guidance");
        assertTrue(prompt.contains("runtime handles tool invocation format automatically"),
                "Native verification command mode should preserve native tool-call guidance");
        assertTrue(prompt.contains("- **talos.grep**"),
                "Native verification command mode should keep inspection tools");
        assertTrue(prompt.contains("- **talos.run_command**"),
                "Native verification command mode should expose run_command");
        assertFalse(prompt.contains("- **talos.edit_file**"),
                "Native verification command mode should filter mutation tools");
        assertFalse(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Native verification command mode should not use writable guidance");
    }

    @Test
    void normalToolModeStillIncludesMutatingToolDescriptors() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a workspace file", ToolRiskLevel.READ_ONLY));
        registry.register(stubTool("talos.write_file", "Create or overwrite a file", ToolRiskLevel.WRITE));

        String prompt = SystemPromptBuilder.forUnified()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("- **talos.read_file**"));
        assertTrue(prompt.contains("- **talos.write_file**"));
        assertTrue(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Writable mode should preserve file operation reinforcement");
    }

    @Test
    void nativeToolsReducesTokenEstimate() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a file"));
        registry.register(stubTool("talos.grep", "Search workspace files"));

        int fallbackTokens = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(false)
                .estimateTokens();

        int nativeTokens = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(true)
                .estimateTokens();

        assertTrue(nativeTokens < fallbackTokens,
                "Native prompt (" + nativeTokens + " tokens) should be smaller than fallback ("
                        + fallbackTokens + " tokens)");
    }

    @Test
    void toStringReflectsNativeToolsFlag() {
        var registry = new ToolRegistry();
        registry.register(stubTool("test", "test"));

        String strTrue = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(true)
                .toString();
        assertTrue(strTrue.contains("nativeTools=true"),
                "toString should reflect nativeTools=true");

        String strFalse = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(false)
                .toString();
        assertTrue(strFalse.contains("nativeTools=false"),
                "toString should reflect nativeTools=false");
    }

    @Test
    void nativeToolsPreambleResourceExists() {
        String content = SystemPromptBuilder.readResource("prompts/sections/tools-preamble-native.txt");
        assertNotNull(content, "tools-preamble-native.txt should exist on classpath");
        assertTrue(content.contains("runtime handles tool invocation"),
                "Native preamble should mention automatic format handling");
        assertFalse(content.contains("<tool_call>"),
                "Native preamble should not contain XML format examples");
    }

    @Test
    void defaultNativeToolsFalseMatchesFallbackBehavior() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a file"));

        // Default (nativeTools not set → false) should include JSON format instructions
        String defaultPrompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        String explicitFallback = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .withNativeTools(false)
                .build();

        assertEquals(defaultPrompt, explicitFallback,
                "Default behavior should match explicit withNativeTools(false)");
    }

    @Test
    void nativeToolsWorksWithAllModes() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.read_file", "Read a file"));

        for (var builder : new SystemPromptBuilder[]{
                SystemPromptBuilder.forAsk(), SystemPromptBuilder.forRag(), SystemPromptBuilder.forUnified()}) {
            String prompt = builder.withTools(registry).withNativeTools(true).build();
            assertFalse(prompt.contains("<tool_call>"),
                    "Native mode should omit XML tags in all modes");
            assertTrue(prompt.contains("Available Tools"),
                    "All modes should include tools preamble with native tools");
        }
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static TalosTool stubTool(String name, String description) {
        return stubTool(name, description, ToolRiskLevel.READ_ONLY);
    }

    private static TalosTool stubTool(String name, String description, ToolRiskLevel riskLevel) {
        return new TalosTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public ToolDescriptor descriptor() { return new ToolDescriptor(name, description, null, riskLevel); }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("stub"); }
        };
    }

    // ── File operation prompt reinforcement ──────────────────────────

    @Test
    void toolsPreambleContainsWriteFileExample() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.write_file", "Create or overwrite a file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("talos.write_file"),
                "Prompt should contain write_file tool name");
        assertTrue(prompt.contains("creating/writing a file") || prompt.contains("talos.write_file"),
                "Prompt should contain write_file example section");
    }

    @Test
    void toolsPreambleContainsCriticalFileModificationSection() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.write_file", "Create or overwrite a file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        assertTrue(prompt.contains("FILE CREATION AND MODIFICATION"),
                "Prompt should contain the elevated File Modification section");
        assertTrue(prompt.contains("CRITICAL"),
                "File Modification section should be marked CRITICAL");
    }

    @Test
    void identityContainsExplicitFileCreationCapability() {
        String prompt = SystemPromptBuilder.forAsk().build();

        assertTrue(prompt.contains("CAN create files"),
                "Identity should explicitly state file creation capability");
        assertTrue(prompt.contains("talos.write_file"),
                "Identity should mention talos.write_file by name");
    }

    @Test
    void askRulesContainWriteFileReinforcement() {
        String prompt = SystemPromptBuilder.forAsk().build();

        assertTrue(prompt.contains("NEVER output code blocks as a substitute"),
                "Ask rules should reinforce never dumping code blocks");
    }

    @Test
    void ragRulesContainWriteFileReinforcement() {
        String prompt = SystemPromptBuilder.forRag().build();

        assertTrue(prompt.contains("NEVER say \"I cannot create files\"")
                        || prompt.contains("You CAN create files"),
                "RAG rules should reinforce file creation capability");
    }

    @Test
    void fileModificationProtocolAppearsBeforeToolList() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.write_file", "Create or overwrite a file"));
        registry.register(stubTool("talos.read_file", "Read a workspace file"));

        String prompt = SystemPromptBuilder.forAsk()
                .withTools(registry)
                .build();

        int criticalPos = prompt.indexOf("FILE CREATION AND MODIFICATION");
        int toolListPos = prompt.indexOf("- **talos.");

        assertTrue(criticalPos >= 0, "CRITICAL section should be present");
        assertTrue(toolListPos >= 0, "Tool list should be present");
        assertTrue(criticalPos < toolListPos,
                "File Modification Protocol should appear BEFORE the tool list");
    }

    @Test
    void writeFileExampleAppearsInWritableToolPrompt() {
        var registry = new ToolRegistry();
        registry.register(stubTool("talos.write_file", "Create or overwrite a file"));

        String prompt = SystemPromptBuilder.forRag()
                .withTools(registry)
                .build();

        // Verify the concrete write_file example is in the prompt
        assertTrue(prompt.contains("\"name\": \"talos.write_file\"")
                        || prompt.contains("talos.write_file"),
                "Prompt should contain a concrete write_file usage example");
        assertTrue(prompt.contains("output/summary.txt")
                        || prompt.contains("talos.write_file"),
                "Prompt should show a write_file example with a file path");
    }
}

