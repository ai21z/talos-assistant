package dev.talos.build;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Release ledger validation task")
class ReleaseLedgerValidationTaskTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("validateReleaseLedger accepts unreleased notes and a top released version matching talosVersion")
    void acceptsMatchingTopReleasedVersion() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.
                """);

        BuildResult result = runValidation(projectDir);

        assertEquals(SUCCESS, result.task(":validateReleaseLedger").getOutcome());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects placeholder release notes")
    void rejectsPendingReleaseNotesPlaceholder() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ## [0.9.9] - 2026-05-15

                ### Changed
                - pending release notes
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("CHANGELOG.md contains placeholder text: pending release notes"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects stale top released changelog version")
    void rejectsTopReleasedVersionMismatch() throws Exception {
        Path projectDir = createBuildFixture("0.9.10", """
                # Changelog

                ## [Unreleased]

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the previous beta candidate.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("Top released CHANGELOG.md version 0.9.9 does not match talosVersion 0.9.10"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects changelogs without an Unreleased section")
    void rejectsMissingUnreleasedSection() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("CHANGELOG.md must contain a top-level ## [Unreleased] section"),
                result.getOutput());
    }

    private Path createBuildFixture(String version, String changelog) throws IOException {
        Path projectDir = tempDir.resolve("fixture-" + version.replace('.', '-'));
        Files.createDirectories(projectDir);
        copyProjectFile("build.gradle.kts", projectDir.resolve("build.gradle.kts"));
        copyProjectFile("settings.gradle", projectDir.resolve("settings.gradle"));
        copyProjectFile("gradle.properties", projectDir.resolve("gradle.properties"));
        Path properties = projectDir.resolve("gradle.properties");
        String updatedProperties = Files.readString(properties, StandardCharsets.UTF_8)
                .replaceFirst("(?m)^talosVersion=.*$", "talosVersion=" + version);
        writeUtf8(properties, updatedProperties);
        writeUtf8(projectDir.resolve("CHANGELOG.md"), changelog);
        return projectDir;
    }

    private void copyProjectFile(String sourceName, Path target) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Files.copy(root.resolve(sourceName), target);
    }

    private BuildResult runValidation(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("validateReleaseLedger", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private BuildResult runValidationAndFail(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("validateReleaseLedger", "--stacktrace")
                .forwardOutput()
                .buildAndFail();
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
