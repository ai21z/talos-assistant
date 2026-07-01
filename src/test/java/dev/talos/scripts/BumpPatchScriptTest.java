package dev.talos.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BumpPatchScriptTest {

    private static final Path SCRIPT = Path.of("scripts", "bump-patch.ps1").toAbsolutePath();

    @TempDir
    Path tempDir;

    @Test
    void movesUnreleasedNotesIntoNextNumericPatchVersion() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, """
                talosVersion=0.9.9
                javaVersion=21
                """);
        writeUtf8(changelog, """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Stabilized beta blocker evidence lanes.
                - Added lane-labeled audit evidence capture.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(readUtf8(properties).contains("talosVersion=0.9.10"));

        String updated = normalize(readUtf8(changelog));
        String expectedHeader = "# Changelog\n\n## [Unreleased]\n\n"
                + "## [0.9.10] - " + LocalDate.now() + "\n\n";
        assertTrue(updated.startsWith(expectedHeader), updated);
        assertTrue(updated.contains("### Changed\n"
                + "- Stabilized beta blocker evidence lanes.\n"
                + "- Added lane-labeled audit evidence capture."));
        assertTrue(updated.indexOf("## [0.9.10]") < updated.indexOf("## [0.9.9]"));
        assertFalse(updated.contains("pending release notes"));
    }

    @Test
    void failsClosedWhenUnreleasedSectionIsMissing() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\n");
        writeUtf8(changelog, """
                # Changelog

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("CHANGELOG.md must contain a top-level '## [Unreleased]' section"),
                result.output());
        assertTrue(readUtf8(properties).contains("talosVersion=0.9.9"));
        assertFalse(readUtf8(changelog).contains("pending release notes"));
    }

    @Test
    void failsClosedWhenUnreleasedSectionHasNoMaterialNotes() throws Exception {
        Path properties = tempDir.resolve("gradle.properties");
        Path changelog = tempDir.resolve("CHANGELOG.md");
        writeUtf8(properties, "talosVersion=0.9.9\n");
        writeUtf8(changelog, """
                # Changelog

                ## [Unreleased]

                ### Changed

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);

        ScriptResult result = runBumpPatch(properties, changelog);

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Unreleased section has no material release notes"),
                result.output());
        assertTrue(readUtf8(properties).contains("talosVersion=0.9.9"));
    }

    private ScriptResult runBumpPatch(Path properties, Path changelog) throws Exception {
        String powershell = powershellExecutable()
                .orElse(null);
        assumeTrue(powershell != null, "PowerShell is unavailable; skipping script execution contract test.");

        List<String> command = new ArrayList<>();
        command.add(powershell);
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(SCRIPT.toString());
        command.add("-PropertiesPath");
        command.add(properties.toString());
        command.add("-ChangelogPath");
        command.add(changelog.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ScriptResult(exitCode, output);
    }

    private Optional<String> powershellExecutable() {
        for (String candidate : List.of("pwsh", "powershell")) {
            try {
                Process process = new ProcessBuilder(candidate, "-NoProfile", "-Command", "$PSVersionTable.PSVersion")
                        .redirectErrorStream(true)
                        .start();
                process.getInputStream().readAllBytes();
                if (process.waitFor() == 0) {
                    return Optional.of(candidate);
                }
            } catch (IOException e) {
                // Try the next PowerShell executable name.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void writeUtf8(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String readUtf8(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n");
    }

    private record ScriptResult(int exitCode, String output) {
    }
}
