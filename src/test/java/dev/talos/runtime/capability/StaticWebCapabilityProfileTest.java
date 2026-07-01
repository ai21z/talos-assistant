package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebCapabilityProfileTest {

    @Test
    void scopedDoNotCreateExtraFilesDoesNotRequireSeparateAssetMutations(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles.css"></head>
                <body><button id="pulse-button">Pulse</button><script src="scripts.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.addEventListener('DOMContentLoaded', () => {
                  document.getElementById('pulse-button').addEventListener('click', () => {});
                });
                """);

        var contract = TaskContractResolver.fromUserRequest(
                "Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(contract, workspace, Set.of("styles.css"));

        assertTrue(profile.staticWeb());
        assertFalse(StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile));
    }

    @Test
    void existingWebSurfaceDesignFollowUpKeepsStaticWebVerifier(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>Retrocats</h1><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        var contract = TaskContractResolver.fromUserRequest("ok just edit the site to look better");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(
                contract,
                workspace,
                Set.of("index.html", "style.css"));

        assertTrue(profile.staticWeb());
        assertEquals(VerifierProfile.STATIC_WEB, profile.verifierProfile());
    }

    @Test
    void genericDesignFollowUpDoesNotSelectStaticWebForNonWebMutation(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Notes\n");

        var contract = TaskContractResolver.fromUserRequest("ok just edit the site to look better");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(
                contract,
                workspace,
                Set.of("README.md"));

        assertFalse(profile.staticWeb());
        assertEquals(VerifierProfile.NONE, profile.verifierProfile());
    }

    @Test
    void exactLiteralHtmlWriteDoesNotSelectStaticWebCoherence(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>Before</h1><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        var contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(contract, workspace, Set.of("index.html"));

        assertFalse(profile.staticWeb());
        assertEquals(VerifierProfile.NONE, profile.verifierProfile());
    }

    @Test
    void cssOnlyVerifyConstraintDoesNotSelectStaticWebCoherence(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="style.css"></head>
                <body><h1>Retrocats</h1><script src="script.js"></script></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        var contract = TaskContractResolver.fromUserRequest("Rewrite styles.css so index.html still works.");

        CapabilityProfile profile = StaticWebCapabilityProfile.select(contract, workspace, Set.of("styles.css"));

        assertFalse(profile.staticWeb());
        assertEquals(VerifierProfile.NONE, profile.verifierProfile());
    }

    @Test
    void structuralTargetInferenceKeepsSingularExistingWebFileNames() {
        List<String> problems = List.of(
                "HTML does not link JavaScript file: `script.js`",
                "CSS file is present as style.css",
                "Files in ./: index.html, script.js, style.css");

        List<String> targets = StaticWebCapabilityProfile.inferStructuralTargets(List.of(), problems);

        assertEquals(List.of("index.html", "script.js", "style.css"), targets);
    }

    @Test
    void structuralTargetInferenceKeepsPluralExistingWebFileNames() {
        List<String> problems = List.of(
                "HTML does not link JavaScript file: `scripts.js`",
                "CSS file is present as styles.css",
                "Files in ./: index.html, scripts.js, styles.css");

        List<String> targets = StaticWebCapabilityProfile.inferStructuralTargets(List.of(), problems);

        assertEquals(List.of("index.html", "scripts.js", "styles.css"), targets);
    }

    @Test
    void structuralTargetInferenceDoesNotAddUnlinkedTailwindMinCssAsRepairTarget() {
        List<String> problems = List.of(
                "tailwind.min.css: Tailwind CSS file is not linked from HTML.",
                "tailwind.min.css: Tailwind directives are unprocessed; no Tailwind CDN or local build configuration was found.",
                "HTML does not link JavaScript file: `script.js`",
                "Files in ./: index.html, script.js, style.css, tailwind.min.css");

        List<String> targets = StaticWebCapabilityProfile.inferStructuralTargets(List.of(), problems);

        assertEquals(List.of("index.html", "script.js", "style.css"), targets);
    }
}
