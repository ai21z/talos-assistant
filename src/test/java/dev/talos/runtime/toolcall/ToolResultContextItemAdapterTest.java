package dev.talos.runtime.toolcall;

import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextItemSource;
import dev.talos.core.context.ContextPrivacyClass;
import dev.talos.core.context.ExecutionBoundary;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultContextItemAdapterTest {

    @Test
    void nullResultFallsBackToCallPathAndNormalMetadata() {
        ContextItem item = ToolResultContextItemAdapter.fromToolResult(
                "talos.read_file",
                "docs/readme.md",
                null);

        assertEquals(ContextItemSource.TOOL_RESULT, item.source());
        assertEquals(ExecutionBoundary.LOCAL_WORKSPACE, item.executionBoundary());
        assertEquals(ContextPrivacyClass.NORMAL, item.privacyClass());
        assertEquals("docs/readme.md", item.pathHint());
        assertEquals(0, item.chars());
        assertEquals(0, item.bytes());
        assertEquals(0, item.lines());
        assertEquals(0, item.estimatedTokens());
        assertTrue(item.textHash().startsWith("sha256:"), item.textHash());
    }

    @Test
    void blankMetadataSourcePathFallsBackToCallPath() {
        ContextItem item = ToolResultContextItemAdapter.fromToolResult(
                "talos.read_file",
                "notes.md",
                ToolResult.ok("alpha\nbeta"));

        assertEquals("notes.md", item.pathHint());
        assertEquals(10, item.chars());
        assertEquals(10, item.bytes());
        assertEquals(2, item.lines());
    }

    @Test
    void metadataSourcePathOverridesCallPathAndProtectedTokensAreRedacted() {
        ToolContentMetadata metadata = new ToolContentMetadata(
                ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT,
                ToolContentMetadata.ContentSource.DOCUMENT_EXTRACTION,
                "protected/private-notes.md",
                false,
                false,
                false,
                "private document");

        ContextItem item = ToolResultContextItemAdapter.fromToolResult(
                "talos.read_file",
                "fallback.md",
                ToolResult.ok("secret text", metadata));

        assertEquals(ContextItemSource.TOOL_RESULT, item.source());
        assertEquals(ExecutionBoundary.LOCAL_WORKSPACE, item.executionBoundary());
        assertEquals(ContextPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT, item.privacyClass());
        assertEquals("<protected-path>", item.pathHint());
    }

    @Test
    void ragMetadataMapsToRagSourceAndBoundary() {
        ToolContentMetadata metadata = new ToolContentMetadata(
                ToolContentMetadata.ContentPrivacyClass.PRIVATE_RAG_SNIPPET,
                ToolContentMetadata.ContentSource.RAG_RETRIEVE,
                "src/App.java#0",
                false,
                false,
                false,
                "private rag");

        ContextItem item = ToolResultContextItemAdapter.fromToolResult(
                "talos.retrieve",
                "ignored",
                ToolResult.ok("class App {}", metadata));

        assertEquals(ContextItemSource.RAG_SNIPPET, item.source());
        assertEquals(ExecutionBoundary.RAG_INDEX, item.executionBoundary());
        assertEquals(ContextPrivacyClass.PRIVATE_RAG_SNIPPET, item.privacyClass());
        assertEquals("src/App.java#0", item.pathHint());
    }

    @Test
    void commandMetadataAndRunCommandToolMapToCommandBoundary() {
        ToolContentMetadata metadata = new ToolContentMetadata(
                ToolContentMetadata.ContentPrivacyClass.COMMAND_OUTPUT,
                ToolContentMetadata.ContentSource.COMMAND,
                "",
                true,
                false,
                false,
                "command output");

        ContextItem metadataCommand = ToolResultContextItemAdapter.fromToolResult(
                "talos.any",
                "",
                ToolResult.ok("BUILD SUCCESSFUL", metadata));
        ContextItem toolNameCommand = ToolResultContextItemAdapter.fromToolResult(
                "talos.run_command",
                "",
                ToolResult.ok("BUILD SUCCESSFUL"));

        assertEquals(ContextItemSource.COMMAND_OUTPUT, metadataCommand.source());
        assertEquals(ExecutionBoundary.COMMAND_PROFILE_OUTPUT, metadataCommand.executionBoundary());
        assertEquals(ContextPrivacyClass.COMMAND_OUTPUT, metadataCommand.privacyClass());
        assertEquals(ContextItemSource.COMMAND_OUTPUT, toolNameCommand.source());
        assertEquals(ExecutionBoundary.COMMAND_PROFILE_OUTPUT, toolNameCommand.executionBoundary());
    }
}
