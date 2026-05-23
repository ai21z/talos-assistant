package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebSurfaceDetectorTest {

    @TempDir
    Path workspace;

    @Test
    void detectsObviousSmallStaticWebSurfaceWhileIgnoringHiddenFiles() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Fixture\n");
        Files.writeString(workspace.resolve(".env"), "ignored=true\n");
        Files.writeString(workspace.resolve("index.html"), "<html></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { color: red; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');");

        assertEquals(
                List.of("index.html", "script.js", "styles.css"),
                StaticWebSurfaceDetector.obviousPrimaryFiles(workspace));
        assertTrue(StaticWebSurfaceDetector.hasPrimaryWebSurface(
                List.of("index.html", "script.js", "styles.css")));
    }

    @Test
    void usesTargetAwareFallbackOnlyWhenVisibleWebTargetWasTouched() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Fixture\n");
        Files.writeString(workspace.resolve("config.json"), "{}\n");
        Files.writeString(workspace.resolve("notes.md"), "note\n");
        Files.writeString(workspace.resolve("report.docx"), "unsupported\n");
        Files.writeString(workspace.resolve("index.html"), "<html></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { color: red; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');");

        assertEquals(List.of(), StaticWebSurfaceDetector.obviousPrimaryFiles(workspace));
        assertEquals(
                List.of("index.html", "script.js", "styles.css"),
                StaticWebSurfaceDetector.targetAwarePrimaryFiles(workspace, List.of("script.js")));
        assertEquals(
                List.of(),
                StaticWebSurfaceDetector.targetAwarePrimaryFiles(workspace, List.of("src/script.js")));
    }

    @Test
    void reportsMissingPrimaryReadsByFilename() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { color: red; }");
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');");

        assertEquals(
                List.of("styles.css"),
                StaticWebSurfaceDetector.missingPrimaryReads(
                        workspace,
                        List.of("index.html", "nested/script.js")));
    }

    @Test
    void primaryHtmlTargetsPreferIndexHtml() {
        assertEquals(
                List.of("index.html"),
                StaticWebSurfaceDetector.primaryHtmlTargets(
                        List.of("about.html", "index.html", "script.js", "styles.css")));
        assertEquals(
                List.of("about.htm"),
                StaticWebSurfaceDetector.primaryHtmlTargets(
                        List.of("about.htm", "script.js", "styles.css")));
        assertFalse(StaticWebSurfaceDetector.hasPrimaryWebSurface(
                List.of("index.html", "styles.css")));
    }
}
