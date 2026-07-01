package dev.talos.cli.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptDebugDestinationResolverTest {

    @AfterEach
    void clearConfig() {
        System.clearProperty("talos.promptDebugDir");
    }

    @Test
    void explicitDirectoryWinsOverConfiguredProperty(@TempDir Path tempDir) {
        Path configured = tempDir.resolve("configured");
        Path explicit = tempDir.resolve("explicit");
        System.setProperty("talos.promptDebugDir", configured.toString());

        Path resolved = PromptDebugDestinationResolver.resolve(explicit.toString());

        assertEquals(explicit.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void blankExplicitDirectoryFallsBackToConfiguredProperty(@TempDir Path tempDir) {
        Path configured = tempDir.resolve("configured");
        System.setProperty("talos.promptDebugDir", configured.toString());

        Path resolved = PromptDebugDestinationResolver.resolve("  ");

        assertEquals(configured.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void configuredPropertyWinsOverEnvironmentDirectory(@TempDir Path tempDir) {
        Path configured = tempDir.resolve("configured");
        Path environment = tempDir.resolve("environment");

        Path resolved = PromptDebugDestinationResolver.resolve(
                "",
                configured.toString(),
                environment.toString(),
                tempDir.toString());

        assertEquals(configured.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void environmentDirectoryWinsOverDefault(@TempDir Path tempDir) {
        Path environment = tempDir.resolve("environment");

        Path resolved = PromptDebugDestinationResolver.resolve(
                null,
                null,
                environment.toString(),
                tempDir.toString());

        assertEquals(environment.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void quotedExplicitDirectoryIsUnwrappedAndNormalized(@TempDir Path tempDir) {
        Path explicit = tempDir.resolve("explicit prompt debug");

        Path resolved = PromptDebugDestinationResolver.resolve("\"" + explicit + "\"");

        assertEquals(explicit.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void defaultDirectoryLivesUnderUserHomePromptDebug(@TempDir Path tempDir) {
        Path expected = Path.of(
                tempDir.toString(),
                ".talos",
                "prompt-debug").toAbsolutePath().normalize();

        Path resolved = PromptDebugDestinationResolver.resolve(null, null, null, tempDir.toString());

        assertEquals(expected, resolved);
    }
}
