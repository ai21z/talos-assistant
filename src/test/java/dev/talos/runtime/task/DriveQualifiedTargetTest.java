package dev.talos.runtime.task;

import dev.talos.safety.ProtectedWorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A path named with a Windows drive keeps that drive, so the target refers to the file the user named. */
class DriveQualifiedTargetTest {

    @Test
    void driveQualifiedTargetKeepsItsDrive() {
        assertEquals(Set.of("C:/project/notes.txt"),
                TaskContractResolver.extractExpectedTargets("Read C:\\project\\notes.txt and tell me what it says."));
        assertEquals(Set.of("c:/project/notes.txt"),
                TaskContractResolver.extractExpectedTargets("Read c:\\project\\notes.txt and tell me what it says."));
        assertEquals(Set.of("D:/data/a.txt"),
                TaskContractResolver.extractExpectedTargets("Read D:/data/a.txt and tell me what it says."));
    }

    @Test
    void driveQualifiedDirectoryIsNotReducedToItsDriveLetter() {
        Set<String> targets = TaskContractResolver.extractExpectedTargets("Create a folder named C:\\project\\out");

        assertTrue(targets.contains("C:/project/out"), targets.toString());
        assertFalse(targets.contains("C"), targets.toString());
    }

    @Test
    void forbiddenDriveQualifiedTargetKeepsItsDrive() {
        Set<String> forbidden = TaskContractResolver.extractForbiddenTargets(
                "Edit notes.txt but do not touch C:\\project\\secret.txt.");

        assertTrue(forbidden.contains("C:/project/secret.txt"), forbidden.toString());
        assertFalse(forbidden.contains("C"), forbidden.toString());
    }

    @Test
    void targetsWithoutADriveAreUnchanged() {
        assertEquals(Set.of("notes.txt"),
                TaskContractResolver.extractExpectedTargets("Read notes.txt and tell me what it says."));
        assertEquals(Set.of("src/app/Main.java"),
                TaskContractResolver.extractExpectedTargets("Read src\\app\\Main.java and tell me what it says."));
        assertEquals(Set.of("readme.md"),
                TaskContractResolver.extractExpectedTargets("Note:readme.md should be read."));
        assertEquals(Set.of("notes.txt"),
                TaskContractResolver.extractExpectedTargets("Read C:notes.txt and tell me what it says."));
        assertEquals(Set.of("/srv/data/a.txt"),
                TaskContractResolver.extractExpectedTargets("Read /srv/data/a.txt and tell me what it says."));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void driveQualifiedTargetInsideTheWorkspaceResolvesToThatFile(@TempDir Path temporary) throws Exception {
        Path workspace = temporary.toRealPath();
        String named = workspace.resolve("docs").resolve("notes.txt").toString();
        String target = named.replace('\\', '/');

        TaskContract contract = TaskContractResolver.fromUserRequest("Read " + named + " and tell me what it says.");
        var decision = ProtectedWorkspacePaths.classify(workspace, target);

        assertEquals(Set.of(target), contract.expectedTargets());
        assertTrue(decision.insideWorkspace());
        assertEquals("docs/notes.txt", decision.relativePath());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void targetOnAnotherDriveStaysOutsideEvenADriveRootWorkspace(@TempDir Path temporary) throws Exception {
        Path driveRoot = temporary.toRealPath().getRoot();
        String other = driveRoot.toString().toUpperCase(Locale.ROOT).startsWith("D") ? "E" : "D";

        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Read " + other + ":\\data\\a.txt and tell me what it says.");

        assertEquals(Set.of(other + ":/data/a.txt"), contract.expectedTargets());
        assertFalse(ProtectedWorkspacePaths.classify(driveRoot, contract.expectedTargets().iterator().next())
                .insideWorkspace());
    }
}
