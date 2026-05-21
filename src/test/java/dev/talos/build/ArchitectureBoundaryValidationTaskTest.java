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

@DisplayName("Architecture boundary validation task")
class ArchitectureBoundaryValidationTaskTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("validateArchitectureBoundaries accepts forbidden imports that are explicitly baselined")
    void acceptsCurrentBaselineViolations() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/runtime/Loop.java"), """
                package dev.talos.runtime;

                import dev.talos.cli.repl.Context;

                final class Loop {
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), """
                # Format: rule|path|import
                runtime-core-no-cli|src/main/java/dev/talos/runtime/Loop.java|dev.talos.cli.repl.Context
                """);

        BuildResult result = runValidation(projectDir);

        assertEquals(SUCCESS, result.task(":validateArchitectureBoundaries").getOutcome());
        assertTrue(Files.exists(projectDir.resolve("build/reports/talos/architecture-boundaries.json")));
        assertTrue(Files.exists(projectDir.resolve("build/reports/talos/architecture-boundaries.md")));
    }

    @Test
    @DisplayName("validateArchitectureBoundaries rejects new forbidden imports not present in the baseline")
    void rejectsUnbaselinedForbiddenImport() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/BadCore.java"), """
                package dev.talos.core;

                import dev.talos.runtime.policy.SafeLogFormatter;

                final class BadCore {
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "core-no-runtime|src/main/java/dev/talos/core/BadCore.java|dev.talos.runtime.policy.SafeLogFormatter"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateArchitectureBoundaries treats a missing baseline file as an empty baseline")
    void treatsMissingBaselineAsEmptyBaseline() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/engine/BadEngine.java"), """
                package dev.talos.engine;

                import dev.talos.runtime.policy.SafeLogFormatter;

                final class BadEngine {
                }
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "engine-no-runtime|src/main/java/dev/talos/engine/BadEngine.java|dev.talos.runtime.policy.SafeLogFormatter"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateArchitectureBoundaries rejects stale baseline entries after violations are removed")
    void rejectsStaleBaselineEntry() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/runtime/CleanRuntime.java"), """
                package dev.talos.runtime;

                final class CleanRuntime {
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), """
                runtime-core-no-cli|src/main/java/dev/talos/runtime/CleanRuntime.java|dev.talos.cli.repl.Context
                """);

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("Stale architecture boundary baseline entries detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "runtime-core-no-cli|src/main/java/dev/talos/runtime/CleanRuntime.java|dev.talos.cli.repl.Context"),
                result.getOutput());
    }

    private Path createBuildFixture() throws IOException {
        Path projectDir = tempDir.resolve("fixture-" + System.nanoTime());
        Files.createDirectories(projectDir);
        copyProjectFile("build.gradle.kts", projectDir.resolve("build.gradle.kts"));
        copyProjectFile("settings.gradle", projectDir.resolve("settings.gradle"));
        copyProjectFile("gradle.properties", projectDir.resolve("gradle.properties"));
        writeUtf8(projectDir.resolve("CHANGELOG.md"), """
                # Changelog

                ## [Unreleased]

                ## [0.9.9] - 2026-05-15

                ### Changed
                - Fixture release entry.
                """);
        return projectDir;
    }

    private void copyProjectFile(String sourceName, Path target) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Files.copy(root.resolve(sourceName), target);
    }

    private BuildResult runValidation(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("validateArchitectureBoundaries", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private BuildResult runValidationAndFail(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("validateArchitectureBoundaries", "--stacktrace")
                .forwardOutput()
                .buildAndFail();
    }

    private void writeJava(Path file, String content) throws IOException {
        writeUtf8(file, content);
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
