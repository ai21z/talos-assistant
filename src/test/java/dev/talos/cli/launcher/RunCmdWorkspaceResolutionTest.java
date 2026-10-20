package dev.talos.cli.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace is resolved once at startup, and a folder that cannot be resolved stops startup. */
class RunCmdWorkspaceResolutionTest {
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    void existingFolderResolvesToItsRealPath(@TempDir Path temporary) throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("project"));

        assertEquals(folder.toRealPath(), RunCmd.resolveWorkspace(folder, Path::toRealPath, err));
        assertEquals("", errors());
    }

    @Test
    void unresolvableFolderStopsWithAClearMessage(@TempDir Path temporary) throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("locked-project"));

        Path resolved = RunCmd.resolveWorkspace(folder, path -> {
            throw new AccessDeniedException(path.toString());
        }, err);

        assertNull(resolved);
        assertTrue(errors().contains("The workspace folder locked-project could not be resolved"), errors());
        assertTrue(errors().contains("AccessDeniedException"), errors());
        assertFalse(errors().contains(temporary.toString()), "the full path stays masked: " + errors());
    }

    @Test
    void missingFolderKeepsTheNotADirectoryMessage(@TempDir Path temporary) {
        assertNull(RunCmd.resolveWorkspace(temporary.resolve("missing"), Path::toRealPath, err));
        assertTrue(errors().startsWith("Not a directory: missing"), errors());
    }

    @Test
    void driveRootIsAcceptedAsAWorkspace() throws Exception {
        Path root = Path.of("").toAbsolutePath().getRoot();

        assertEquals(root.toRealPath(), RunCmd.resolveWorkspace(root, Path::toRealPath, err));
        assertEquals("", errors());
    }

    private String errors() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
