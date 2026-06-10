package dev.talos.core.context;

import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T752 pinning: fromToolResult's source-path fallback flow (the old correlated ternary). */
class ContextItemSourcePathFallbackTest {

    @Test
    void nullResultFallsBackToCallPath() {
        ContextItem item = ContextItem.fromToolResult("talos.read_file", "docs/readme.md", null);

        assertEquals("docs/readme.md", item.pathHint());
    }

    @Test
    void normalMetadataWithBlankSourcePathFallsBackToCallPath() {
        ContextItem item = ContextItem.fromToolResult("talos.read_file", "notes.md", ToolResult.ok("text"));

        assertEquals("notes.md", item.pathHint());
    }
}
