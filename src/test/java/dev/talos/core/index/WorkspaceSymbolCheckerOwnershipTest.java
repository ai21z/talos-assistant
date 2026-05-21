package dev.talos.core.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSymbolCheckerOwnershipTest {

    @Test
    void workspaceSymbolCheckerIsOwnedByCoreIndexPackage() throws Exception {
        assertTrue(Files.exists(Path.of("src/main/java/dev/talos/core/index/WorkspaceSymbolChecker.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/talos/cli/modes/WorkspaceSymbolChecker.java")));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));
        assertFalse(baseline.contains("dev.talos.cli.modes.WorkspaceSymbolChecker"), baseline);
    }
}
