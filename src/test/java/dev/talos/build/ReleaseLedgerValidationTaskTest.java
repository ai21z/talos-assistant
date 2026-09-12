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
    @DisplayName("validateReleaseLedger accepts distinct historical release versions")
    void acceptsDistinctHistoricalReleaseVersions() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.

                ## [0.9.8] - 2026-05-08

                ### Changed
                - Declared the preceding beta candidate.

                ## [0.9.7] - 2026-05-01

                ### Changed
                - Declared an earlier beta candidate.
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
                - Pending Release Notes
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

    @Test
    @DisplayName("validateReleaseLedger rejects duplicate Unreleased headings")
    void rejectsDuplicateUnreleasedSections() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.

                ## [Unreleased]

                ### Fixed
                - This second open section is ambiguous.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("exactly one top-level ## [Unreleased] section"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects duplicate released version identities")
    void rejectsDuplicateReleasedVersionIdentities() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.

                ## [0.9.8] - 2026-05-08

                ### Changed
                - Declared the preceding beta candidate.

                ## [0.9.8] - 2026-05-07

                ### Fixed
                - A duplicate identity must not be silently accepted.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().toLowerCase().contains("duplicate"), result.getOutput());
        assertTrue(result.getOutput().contains("0.9.8"), result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects a missing released version section")
    void rejectsMissingReleasedVersionSection() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("CHANGELOG.md must contain at least one released version section"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects malformed released headings instead of skipping them")
    void rejectsMalformedReleasedHeading() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026/05/15

                ### Changed
                - A non-ISO heading must not disappear from ledger parsing.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().toLowerCase().contains("malformed bracketed top-level release heading"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects indented malformed CommonMark H2 release candidates")
    void rejectsIndentedMalformedReleasedHeading() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                   ## [0.9.9] - 2026/05/15

                ### Changed
                - An indented non-ISO candidate must not disappear from ledger parsing.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().toLowerCase().contains("malformed bracketed top-level release heading"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects tab-separated malformed CommonMark H2 release candidates")
    void rejectsTabSeparatedMalformedReleasedHeading() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ##\t[0.9.9] - 2026/05/15

                ### Changed
                - A tab-separated non-ISO candidate must not disappear from ledger parsing.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().toLowerCase().contains("malformed bracketed top-level release heading"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects non-canonical leading-zero release identities")
    void rejectsLeadingZeroReleaseIdentity() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Declared the latest beta candidate.

                ## [0.09.8] - 2026-05-08

                ### Changed
                - This alias-like identity must fail closed.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("numeric MAJOR.MINOR.PATCH identities"), result.getOutput());
        assertTrue(result.getOutput().contains("0.09.8"), result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects impossible ISO calendar dates")
    void rejectsImpossibleReleaseDate() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 2026-02-30

                ### Changed
                - A shape-valid but impossible date must fail closed.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("valid ISO release date"), result.getOutput());
        assertTrue(result.getOutput().contains("2026-02-30"), result.getOutput());
    }

    @Test
    @DisplayName("validateReleaseLedger rejects ISO-shaped year zero")
    void rejectsReleaseDateWithYearZero() throws Exception {
        Path projectDir = createBuildFixture("0.9.9", """
                # Changelog

                ## [Unreleased]

                ### Changed
                - Current stabilization work is tracked here.

                ## [0.9.9] - 0000-05-15

                ### Changed
                - Proleptic year zero is outside the release-ledger contract.
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("valid ISO release date"), result.getOutput());
        assertTrue(result.getOutput().contains("0000-05-15"), result.getOutput());
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
