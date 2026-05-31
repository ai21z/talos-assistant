package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTargetReconcilerTest {

    @Test
    void existingPluralScriptWinsOverUnmentionedConventionalSingular(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing');\n");

        TaskContract contract = reconciledStaticWebContract(workspace);

        assertTrue(contract.expectedTargets().contains("scripts.js"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("script.js"), contract.expectedTargets().toString());
    }

    @Test
    void existingPluralStylesWinsOverUnmentionedConventionalSingular(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");

        TaskContract contract = reconciledStaticWebContract(workspace);

        assertTrue(contract.expectedTargets().contains("styles.css"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("style.css"), contract.expectedTargets().toString());
    }

    @Test
    void emptyWorkspaceKeepsConventionalStaticSiteTargets(@TempDir Path workspace) {
        TaskContract contract = reconciledStaticWebContract(workspace);

        assertEquals(Set.of("index.html", "style.css", "script.js"), contract.expectedTargets());
    }

    @Test
    void ambiguousSingularPluralWorkspaceDoesNotGuessConventionalAssetTargets(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("script.js"), "console.log('singular');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('plural');\n");
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("styles.css"), "body { color: black; }\n");

        TaskContract contract = reconciledStaticWebContract(workspace);

        assertEquals(Set.of("index.html"), contract.expectedTargets());
    }

    @Test
    void explicitPluralTargetPreservesExactNameWhenSingularAlsoExists(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("script.js"), "console.log('singular');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('plural');\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Update scripts.js with real local interactivity.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertEquals(Set.of("scripts.js"), contract.expectedTargets());
    }

    @Test
    void explicitSingularTargetPreservesExactNameWhenPluralAlsoExists(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("script.js"), "console.log('singular');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('plural');\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Update script.js with real local interactivity.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertEquals(Set.of("script.js"), contract.expectedTargets());
    }

    private static TaskContract reconciledStaticWebContract(Path workspace) {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.");
        return WorkspaceTargetReconciler.reconcile(raw, workspace);
    }
}
