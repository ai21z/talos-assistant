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
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String jsonReport = Files.readString(projectDir.resolve("build/reports/talos/architecture-boundaries.json"));
        assertTrue(jsonReport.contains("\"forbiddenReferencePrefixes\""), jsonReport);
        assertTrue(jsonReport.contains("\"referencedSymbol\""), jsonReport);
        assertFalse(jsonReport.contains("\"forbiddenImportPrefixes\""), jsonReport);
        assertFalse(jsonReport.contains("\"importedType\""), jsonReport);
        assertFalse(jsonReport.contains("\"referencedType\""), jsonReport);
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
    @DisplayName("validateArchitectureBoundaries normalizes static imports to the referenced type")
    void normalizesStaticImportsToReferencedType() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/BadCore.java"), """
                package dev.talos.core;

                import static dev.talos.runtime.policy.SafeLogFormatter.value;

                final class BadCore {
                    String format(String input) {
                        return value(input);
                    }
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidationAndFail(projectDir);

        String expected = "core-no-runtime|src/main/java/dev/talos/core/BadCore.java|dev.talos.runtime.policy.SafeLogFormatter";
        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(expected), result.getOutput());
        assertFalse(result.getOutput().contains(expected + ".value"), result.getOutput());
    }

    @Test
    @DisplayName("validateArchitectureBoundaries rejects forbidden package wildcard imports")
    void rejectsForbiddenPackageWildcardImport() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/BadCore.java"), """
                package dev.talos.core;

                import dev.talos.runtime.policy.*;

                final class BadCore {
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "core-no-runtime|src/main/java/dev/talos/core/BadCore.java|dev.talos.runtime.policy.*"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateArchitectureBoundaries rejects forbidden package wildcard imports with trailing block comments")
    void rejectsForbiddenPackageWildcardImportWithTrailingBlockComment() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/BadCore.java"), """
                package dev.talos.core;

                import dev.talos.runtime.policy.*; /* explanatory comment */

                final class BadCore {
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "core-no-runtime|src/main/java/dev/talos/core/BadCore.java|dev.talos.runtime.policy.*"),
                result.getOutput());
    }

    @Test
    @DisplayName("validateArchitectureBoundaries rejects forbidden fully qualified references without imports")
    void rejectsUnbaselinedForbiddenFullyQualifiedReference() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/BadCore.java"), """
                package dev.talos.core;

                final class BadCore {
                    // dev.talos.runtime.policy.ProtectedContentPolicy must not count from comments.
                    private static final String DOC =
                            "dev.talos.runtime.policy.PrivateDocumentPolicy must not count from strings";

                    String format(String input) {
                        return dev.talos.runtime.policy.SafeLogFormatter.value(input);
                    }
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
    @DisplayName("validateArchitectureBoundaries ignores forbidden references in comments and literals")
    void ignoresForbiddenReferencesInCommentsAndLiterals() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/core/DocumentationOnly.java"), """
                package dev.talos.core;

                /*
                 * dev.talos.runtime.policy.SafeLogFormatter must not count from block comments.
                 */
                final class DocumentationOnly {
                    // dev.talos.runtime.policy.ProtectedContentPolicy must not count from line comments.
                    private static final String STRING_DOC =
                            "dev.talos.runtime.policy.PrivateDocumentPolicy must not count from strings";
                    private static final String ESCAPED_STRING =
                            "quoted \\\" dev.talos.runtime.policy.ProtectedReadScopePolicy";
                    private static final char QUOTE = '"';
                    private static final char BACKSLASH = '\\\\';
                    private static final String TEXT_BLOCK = \"""
                            dev.talos.runtime.policy.SafeLogFormatter must not count from text blocks.
                            escaped delimiter: \\\"""
                            dev.talos.runtime.policy.ProtectedContentPolicy still must not count.
                            \""";
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidation(projectDir);

        assertEquals(SUCCESS, result.task(":validateArchitectureBoundaries").getOutcome());
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
    @DisplayName("validateArchitectureBoundaries rejects safety package references to Talos layers")
    void rejectsSafetyPackageReferencesToTalosLayers() throws Exception {
        Path projectDir = createBuildFixture();
        writeJava(projectDir.resolve("src/main/java/dev/talos/safety/BadSafety.java"), """
                package dev.talos.safety;

                import dev.talos.runtime.policy.ProtectedContentPolicy;

                final class BadSafety {
                    String sanitize(String input) {
                        return ProtectedContentPolicy.sanitizeText(input);
                    }
                }
                """);
        writeUtf8(projectDir.resolve("config/architecture-boundary-baseline.txt"), "");

        BuildResult result = runValidationAndFail(projectDir);

        assertTrue(result.getOutput().contains("New architecture boundary violations detected: 1"),
                result.getOutput());
        assertTrue(result.getOutput().contains(
                "safety-no-talos-layers|src/main/java/dev/talos/safety/BadSafety.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
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
        Files.writeString(
                projectDir.resolve("gradle.properties"),
                System.lineSeparator() + "org.gradle.daemon=false" + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
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
        return validationRunner(projectDir).build();
    }

    private BuildResult runValidationAndFail(Path projectDir) {
        return validationRunner(projectDir).buildAndFail();
    }

    private GradleRunner validationRunner(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(validationArguments())
                .forwardOutput();
    }

    private List<String> validationArguments() {
        return List.of(
                "--stacktrace",
                "validateArchitectureBoundaries");
    }

    private void writeJava(Path file, String content) throws IOException {
        writeUtf8(file, content);
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
