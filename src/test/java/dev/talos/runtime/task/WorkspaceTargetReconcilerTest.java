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
    void linkedCssFileWinsOverPluralSiblingWhenBothExist(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head><body></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("styles.css"), "@tailwind base;\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Make the changes in Tailwind and update styles.css as needed.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertTrue(contract.expectedTargets().contains("style.css"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("styles.css"), contract.expectedTargets().toString());
    }

    @Test
    void linkedScriptFileWinsOverPluralSiblingWhenBothExist(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><body><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("script.js"), "console.log('linked');\n");
        Files.writeString(workspace.resolve("scripts.js"), "console.log('orphan');\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Update scripts.js so the interaction works.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertTrue(contract.expectedTargets().contains("script.js"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("scripts.js"), contract.expectedTargets().toString());
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

    @Test
    void explicitNewLinkedCssRequestPreservesRequestedPluralAsset(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head><body></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create a new styles.css file and update index.html to link it instead of style.css.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertTrue(contract.expectedTargets().contains("styles.css"), contract.expectedTargets().toString());
    }

    @Test
    void explicitStaticWebSurfaceCreatePreservesRequestedPluralAssetsDespiteOldLinks(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head>
                <body><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('old');\n");
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create a complete static BMI calculator with index.html, styles.css, and scripts.js.");

        TaskContract contract = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertTrue(contract.expectedTargets().contains("styles.css"), contract.expectedTargets().toString());
        assertTrue(contract.expectedTargets().contains("scripts.js"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("style.css"), contract.expectedTargets().toString());
        assertFalse(contract.expectedTargets().contains("script.js"), contract.expectedTargets().toString());
    }

    private static TaskContract reconciledStaticWebContract(Path workspace) {
        TaskContract raw = TaskContractResolver.fromUserRequest(
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.");
        return WorkspaceTargetReconciler.reconcile(raw, workspace);
    }
}
