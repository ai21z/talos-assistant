package dev.talos.core.context;

import dev.talos.tools.ToolContentMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextItemProtectedPathParityTest {

    @Test
    void contextItemPathHintUsesProtectedPathPolicyTokens() {
        assertEquals("<protected-path>", itemFor("protected/private-notes.md").pathHint());
        assertEquals("<protected-path>", itemFor(".github/workflows/deploy.yml").pathHint());
        assertEquals("<protected-path>", itemFor(".aws/credentials").pathHint());
    }

    @Test
    void contextItemPathHintKeepsNormalWorkspacePaths() {
        assertEquals("docs/environment.md", itemFor("docs/environment.md").pathHint());
    }

    private static ContextItem itemFor(String path) {
        return ContextItem.fromText(
                ContextItemSource.TOOL_RESULT,
                ExecutionBoundary.LOCAL_WORKSPACE,
                ToolContentMetadata.ContentPrivacyClass.NORMAL,
                path,
                "summary only",
                0);
    }
}
