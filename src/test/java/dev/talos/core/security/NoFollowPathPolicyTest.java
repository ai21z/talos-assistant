package dev.talos.core.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoFollowPathPolicyTest {
    @TempDir
    Path temp;

    @Test
    void ordinaryNestedTreeAndMissingTargetRemainUsable() throws Exception {
        Path root = Files.createDirectories(temp.resolve("workspace/nested/empty"));
        Path workspace = temp.resolve("workspace");
        Path file = Files.writeString(
                temp.resolve("workspace/nested/data.txt"), "ordinary", StandardCharsets.UTF_8);
        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);

        assertEquals(NoFollowPathPolicy.EntryType.DIRECTORY,
                guarded.inspect(root));
        assertEquals(NoFollowPathPolicy.EntryType.REGULAR_FILE,
                guarded.inspect(file));
        assertEquals(NoFollowPathPolicy.EntryType.MISSING,
                guarded.inspect(workspace.resolve("missing/output.txt")));
        assertArrayEquals("ordinary".getBytes(StandardCharsets.UTF_8),
                guarded.readAllBytes(file));
        List<NoFollowPathPolicy.TreeEntry> entries =
                guarded.walk(workspace.resolve("nested"));
        assertTrue(entries.stream().anyMatch(entry ->
                entry.path().equals(root)
                        && entry.type() == NoFollowPathPolicy.EntryType.DIRECTORY));
        assertTrue(entries.stream().anyMatch(entry ->
                entry.path().equals(file)
                        && entry.type() == NoFollowPathPolicy.EntryType.REGULAR_FILE));
        List<NoFollowPathPolicy.TreeFile> files =
                guarded.readRegularFiles(
                        workspace.resolve("nested"),
                        path -> path.getFileName().toString().endsWith(".txt"));
        assertEquals(1, files.size());
        assertEquals(file, files.getFirst().path());
        assertArrayEquals(
                "ordinary".getBytes(StandardCharsets.UTF_8),
                files.getFirst().bytes());
    }

    @Test
    void malformedUtf8RemainsAReadFailureInsteadOfReplacementText()
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("malformed-workspace"));
        Path malformed = Files.write(
                workspace.resolve("malformed.txt"),
                new byte[] {(byte) 0xc3, 0x28});
        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);

        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> guarded.readString(malformed, StandardCharsets.UTF_8));
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> NoFollowPathPolicy.readString(malformed, StandardCharsets.UTF_8));
    }

    @Test
    void externalAndInternalLinksAreRejectedWithoutTargetDisclosure() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path outsideFile = Files.writeString(
                outside.resolve("secret.txt"), "T1170_CORE_EXTERNAL_CANARY");
        Path insideFile = Files.writeString(workspace.resolve("inside.txt"), "inside");
        Path externalLink = workspace.resolve("external-link.txt");
        Path internalLink = workspace.resolve("internal-link.txt");
        createSymbolicLinkOrAbort(externalLink, outsideFile);
        createSymbolicLinkOrAbort(internalLink, insideFile);
        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);

        for (Path linked : List.of(externalLink, internalLink)) {
            NoFollowPathPolicy.Violation failure = assertThrows(
                    NoFollowPathPolicy.Violation.class,
                    () -> guarded.readAllBytes(linked));
            assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, failure.reason());
            assertFalse(failure.getMessage().contains(outside.toString()));
            assertFalse(failure.getMessage().contains("T1170_CORE_EXTERNAL_CANARY"));
        }
        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> guarded.walk(workspace));
        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> guarded.readRegularFiles(workspace));
    }

    @Test
    void linkedRootIntermediateAncestorAndCreateParentAreRejected() throws Exception {
        Path realParent = Files.createDirectory(temp.resolve("real-parent"));
        Path realRoot = Files.createDirectory(realParent.resolve("root"));
        Path ordinary = Files.writeString(realRoot.resolve("ordinary.txt"), "ordinary");
        Path linkedParent = temp.resolve("linked-parent");
        createSymbolicLinkOrAbort(linkedParent, realParent);
        Path linkedRoot = linkedParent.resolve("root");

        NoFollowPathPolicy.Violation rootFailure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> NoFollowPathPolicy.bindExistingDirectory(linkedRoot));
        assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, rootFailure.reason());
        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> NoFollowPathPolicy.requireUnlinkedRegularFile(
                        linkedRoot.resolve(ordinary.getFileName())));

        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path linkedOutputParent = workspace.resolve("linked-output");
        createSymbolicLinkOrAbort(linkedOutputParent, realParent);
        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);
        assertThrows(NoFollowPathPolicy.Violation.class,
                () -> guarded.requireRegularFileTarget(
                        linkedOutputParent.resolve("new.txt")));
        assertFalse(Files.exists(realParent.resolve("new.txt")));
    }

    @Test
    void siblingPrefixEscapeIsRejected() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("work"));
        Path sibling = Files.createDirectory(temp.resolve("work-other"));
        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);

        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> guarded.inspect(sibling));

        assertEquals(NoFollowPathPolicy.Reason.OUTSIDE_ROOT, failure.reason());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionIsRejectedAsANonOrdinaryIntermediateComponent()
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("windows-workspace"));
        Path external = Files.createDirectory(temp.resolve("windows-external"));
        Files.writeString(external.resolve("canary.txt"), "T1170_WINDOWS_JUNCTION_CANARY");
        Path junction = workspace.resolve("junction");

        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        NoFollowPathPolicy.GuardedRoot guarded =
                NoFollowPathPolicy.bindExistingDirectory(workspace);
        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> guarded.readAllBytes(junction.resolve("canary.txt")));

        assertEquals(NoFollowPathPolicy.Reason.NON_ORDINARY_COMPONENT, failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(failure.getMessage().contains("T1170_WINDOWS_JUNCTION_CANARY"));
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
