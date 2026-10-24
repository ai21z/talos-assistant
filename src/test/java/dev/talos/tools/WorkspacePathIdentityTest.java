package dev.talos.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspacePathIdentityTest {

    @TempDir Path temporary;
    Path workspace;

    @BeforeEach void useTheResolvedWorkspace() throws IOException {
        workspace = Files.createDirectories(temporary.resolve("ws")).toRealPath();
        Files.writeString(workspace.resolve("notes.txt"), "notes\n");
    }

    @Test void relativeAndAbsoluteSpellingsOfOneFileMatch() {
        String absolute = workspace.resolve("notes.txt").toString();

        assertTrue(WorkspacePathIdentity.matches(workspace, "notes.txt", absolute));
        assertTrue(WorkspacePathIdentity.matches(workspace, absolute, "notes.txt"));
        assertTrue(WorkspacePathIdentity.matches(workspace, "./notes.txt", "sub/../notes.txt"));
    }

    @Test void aFileNotYetWrittenMatchesOnlyItsOwnShape() {
        assertTrue(WorkspacePathIdentity.matches(
                workspace, "docs/new.md", workspace.resolve("docs").resolve("new.md").toString()));
        assertFalse(WorkspacePathIdentity.matches(
                workspace, "docs/new.md", workspace.resolve("new.md").toString()));
    }

    @Test void aSiblingFileDoesNotMatch() throws IOException {
        Files.writeString(workspace.resolve("other.txt"), "other\n");

        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", workspace.resolve("other.txt").toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", workspace.resolve("other.txt").toString()));
    }

    @Test void aFileOfTheSameNameInASubfolderDoesNotMatch() throws IOException {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs").resolve("notes.txt"), "docs\n");

        assertFalse(WorkspacePathIdentity.matches(
                workspace, "notes.txt", workspace.resolve("docs").resolve("notes.txt").toString()));
        assertFalse(WorkspacePathIdentity.matches(workspace, "docs/notes.txt", "notes.txt"));
    }

    @Test void aFileOutsideTheWorkspaceDoesNotMatch() throws IOException {
        Path outside = Files.writeString(temporary.toRealPath().resolve("notes.txt"), "outside\n");

        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", outside.toString()));
        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", "../notes.txt"));
        assertFalse(WorkspacePathIdentity.matches(workspace, outside.toString(), outside.toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", outside.toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, outside.toString(), outside.toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "../notes.txt", "../notes.txt"));
    }

    @Test void noWorkspaceGivesNoIdentity() {
        String absolute = workspace.resolve("notes.txt").toString();

        assertFalse(WorkspacePathIdentity.matches(null, "notes.txt", absolute));
        assertFalse(WorkspacePathIdentity.matches(Path.of("ws"), "notes.txt", absolute));
        assertFalse(WorkspacePathIdentity.mayMatch(null, "notes.txt", absolute));
        assertFalse(WorkspacePathIdentity.matches(workspace, "", absolute));
        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", " "));
    }

    @Test void aLinkToTheTargetDoesNotMatch() throws IOException {
        Path alias = workspace.resolve("alias.txt");
        Path linkedFolder = workspace.resolve("linked");
        Files.createDirectories(workspace.resolve("real"));
        Files.writeString(workspace.resolve("real").resolve("notes.txt"), "real\n");
        assumeTrue(createdLink(alias, workspace.resolve("notes.txt")), "links cannot be created here");
        assumeTrue(createdLink(linkedFolder, workspace.resolve("real")), "links cannot be created here");

        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", alias.toString()));
        assertFalse(WorkspacePathIdentity.matches(workspace, "real/notes.txt", "linked/notes.txt"));
        assertFalse(WorkspacePathIdentity.matches(
                workspace, "linked/notes.txt", linkedFolder.resolve("notes.txt").toString()));
        assertTrue(WorkspacePathIdentity.mayMatch(
                workspace, "linked/notes.txt", linkedFolder.resolve("notes.txt").toString()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCaseVariantIsAnotherFileWhereCaseIsSignificant() {
        assumeTrue(!Files.exists(workspace.resolve("Notes.txt")), "this file system ignores case");
        assertFalse(WorkspacePathIdentity.matches(workspace, "Notes.txt", workspace.resolve("notes.txt").toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "Notes.txt", workspace.resolve("notes.txt").toString()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aCaseVariantMatchesOnlyWhenTheFileSystemConfirmsIt() {
        assertTrue(WorkspacePathIdentity.matches(workspace, "NOTES.TXT", workspace.resolve("notes.txt").toString()));
        assertFalse(WorkspacePathIdentity.matches(workspace, "Missing.txt", workspace.resolve("missing.txt").toString()));
        assertTrue(WorkspacePathIdentity.mayMatch(workspace, "Missing.txt", workspace.resolve("missing.txt").toString()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRootedSpellingWithoutAnAbsoluteFormGivesNoIdentity() {
        String drive = workspace.getRoot().toString().substring(0, 2);
        String otherDrive = drive.startsWith("Q") ? "R:" : "Q:";
        String absolute = workspace.resolve("notes.txt").toString();
        String driveRooted = absolute.substring(2);

        assertFalse(WorkspacePathIdentity.matches(workspace, drive + "notes.txt", absolute));
        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", drive + "notes.txt"));
        assertFalse(WorkspacePathIdentity.matches(workspace, "notes.txt", driveRooted));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", drive + "other.txt"));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", otherDrive + "notes.txt"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRootedSpellingThatTheToolsResolveToTheTargetMayMatchIt() {
        String drive = workspace.getRoot().toString().substring(0, 2);
        String driveRooted = workspace.resolve("notes.txt").toString().substring(2);

        assertTrue(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", drive + "notes.txt"));
        assertTrue(WorkspacePathIdentity.mayMatch(workspace, drive + "notes.txt", "notes.txt"));
        assertTrue(WorkspacePathIdentity.mayMatch(workspace, "notes.txt", driveRooted));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void hostAndShareSpellingsDoNotSeparateOneFileOnAShare() {
        String tail = workspace.toString().substring(2);
        Path shared = Path.of("\\\\localhost\\" + workspace.toString().charAt(0) + "$" + tail);
        assumeTrue(Files.isDirectory(shared), "the local administrative share is not reachable");
        String upperHost = "\\\\LOCALHOST\\" + Character.toLowerCase(workspace.toString().charAt(0)) + "$"
                + tail + "\\notes.txt";

        assertTrue(WorkspacePathIdentity.matches(shared, "notes.txt", upperHost));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(60)
    void twoFilesThatDifferOnlyInCaseAreNotOneFile() throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("cs"));
        assumeTrue(madeCaseSensitive(folder), "case-sensitive folders cannot be made here");
        Path lower = Files.writeString(folder.resolve("notes.txt"), "lower\n");
        Path upper = Files.writeString(folder.resolve("Notes.txt"), "upper\n");
        assumeTrue(!Files.readString(lower).equals(Files.readString(upper)), "the folder is not case-sensitive");

        assertFalse(WorkspacePathIdentity.matches(workspace, "cs/notes.txt", upper.toString()));
        assertFalse(WorkspacePathIdentity.mayMatch(workspace, "cs/notes.txt", upper.toString()));
        assertTrue(WorkspacePathIdentity.matches(workspace, "cs/notes.txt", lower.toString()));
    }

    private static boolean madeCaseSensitive(Path folder) {
        try {
            Process process = new ProcessBuilder(
                    "fsutil.exe", "file", "setCaseSensitiveInfo", folder.toString(), "enable")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean createdLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            return false;
        }
    }
}
