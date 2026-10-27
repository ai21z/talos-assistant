package dev.talos.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeArtifactIdentityTest {
    @TempDir
    Path temp;

    @Test
    void orderedClasspathIdentityBindsEveryEntryByteWithoutPaths() throws Exception {
        Path first = temp.resolve("first.jar");
        Path second = temp.resolve("second.jar");
        Path renamed = temp.resolve("renamed.jar");
        Files.writeString(first, "first-bytes");
        Files.writeString(second, "second-bytes");
        Files.copy(first, renamed);

        RuntimeArtifactIdentity.ClasspathSnapshot original =
                RuntimeArtifactIdentity.identifyClasspath(List.of(first, second));
        RuntimeArtifactIdentity.ClasspathSnapshot reversed =
                RuntimeArtifactIdentity.identifyClasspath(List.of(second, first));
        RuntimeArtifactIdentity.ClasspathSnapshot renamedEntry =
                RuntimeArtifactIdentity.identifyClasspath(List.of(renamed, second));
        Files.writeString(second, "changed-byte");
        RuntimeArtifactIdentity.ClasspathSnapshot changed =
                RuntimeArtifactIdentity.identifyClasspath(List.of(first, second));

        assertTrue(original.exact());
        assertEquals(2, original.entryCount());
        assertEquals(Files.size(first) + "second-bytes".length(), original.sizeBytes());
        assertNotEquals(original.sha256(), reversed.sha256());
        assertNotEquals(original.sha256(), renamedEntry.sha256());
        assertNotEquals(original.sha256(), changed.sha256());
        assertFalse(original.toString().contains(temp.toString()));
    }

    @Test
    void duplicateNormalizedClasspathEntryFailsClosed() throws Exception {
        Path entry = temp.resolve("one.jar");
        Files.writeString(entry, "one");

        RuntimeArtifactIdentity.ClasspathSnapshot duplicate =
                RuntimeArtifactIdentity.identifyClasspath(List.of(entry, entry.resolveSibling(".").resolve("one.jar")));

        assertFalse(duplicate.exact());
        assertEquals("", duplicate.sha256());
        assertEquals(0, duplicate.entryCount());
    }

    @Test
    void directoryIdentityContinuesToBindNonClassResources() throws Exception {
        Path classes = Files.createDirectories(temp.resolve("classes/dev/talos"));
        Files.writeString(classes.resolve("Main.class"), "class-bytes");
        Path resource = Files.writeString(
                temp.resolve("classes/dev/talos/build.properties"), "version=one\n");

        RuntimeArtifactIdentity.ClasspathSnapshot before =
                RuntimeArtifactIdentity.identifyClasspath(List.of(temp.resolve("classes")));
        Files.writeString(resource, "version=two\n");
        RuntimeArtifactIdentity.ClasspathSnapshot after =
                RuntimeArtifactIdentity.identifyClasspath(List.of(temp.resolve("classes")));

        assertTrue(before.exact());
        assertTrue(after.exact());
        assertEquals(1, before.entryCount());
        assertEquals(1, after.entryCount());
        assertEquals("class-bytes".length() + "version=one\n".length(), before.sizeBytes());
        assertEquals("class-bytes".length() + "version=two\n".length(), after.sizeBytes());
        assertNotEquals(before.sha256(), after.sha256());
    }

    @Test
    void linkedClasspathAncestorAndDescendantMakeTheWholeIdentityUnavailable()
            throws Exception {
        Path realParent = Files.createDirectories(temp.resolve("real-parent"));
        Path realClasspath = Files.createDirectories(realParent.resolve("real-classes"));
        Path realDirectory = Files.createDirectories(realClasspath.resolve("dev/talos"));
        Files.writeString(realDirectory.resolve("Main.class"), "class-bytes");
        Path linkedParent = temp.resolve("linked-parent");
        createSymbolicLinkOrAbort(linkedParent, realParent);
        Path linkedDirectory = linkedParent.resolve("real-classes");

        RuntimeArtifactIdentity.ClasspathSnapshot linkedAncestor =
                RuntimeArtifactIdentity.identifyClasspath(List.of(linkedDirectory));

        assertFalse(linkedAncestor.exact(),
                "an ordinary class reached through a linked ancestor must not be identified");

        Path ordinaryRoot = Files.createDirectories(temp.resolve("ordinary-classes/dev/talos"));
        Files.writeString(ordinaryRoot.resolve("Safe.class"), "safe");
        createSymbolicLinkOrAbort(
                ordinaryRoot.resolve("Linked.class"),
                realDirectory.resolve("Main.class"));

        RuntimeArtifactIdentity.ClasspathSnapshot linkedDescendant =
                RuntimeArtifactIdentity.identifyClasspath(List.of(temp.resolve("ordinary-classes")));

        assertFalse(linkedDescendant.exact(),
                "a linked descendant must reject the complete classpath entry, not be omitted");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionMakesTheWholeClasspathIdentityUnavailable()
            throws Exception {
        Path classes = Files.createDirectories(temp.resolve("windows-classes/dev/talos"));
        Files.writeString(classes.resolve("Main.class"), "class-bytes");
        Path external = Files.createDirectory(temp.resolve("windows-external-classes"));
        Files.writeString(external.resolve("External.class"), "external");
        Path junction = classes.resolve("junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        RuntimeArtifactIdentity.ClasspathSnapshot snapshot =
                RuntimeArtifactIdentity.identifyClasspath(
                        List.of(temp.resolve("windows-classes")));

        assertFalse(snapshot.exact());
        assertEquals("", snapshot.sha256());
    }

    @Test
    void currentPathFreeEntryLedgerExactlyReconstructsCurrentAggregate() {
        List<RuntimeArtifactIdentity.ClasspathEntryIdentity> entries =
                RuntimeArtifactIdentity.currentClasspathEntryIdentities();

        RuntimeArtifactIdentity.ClasspathSnapshot reconstructed =
                RuntimeArtifactIdentity.identifyClasspathEntries(entries);

        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().allMatch(
                RuntimeArtifactIdentity.ClasspathEntryIdentity::exact));
        assertTrue(entries.stream().map(
                RuntimeArtifactIdentity.ClasspathEntryIdentity::logicalToken)
                .noneMatch(token -> token.contains("/") || token.contains("\\")));
        assertEquals(RuntimeArtifactIdentity.currentClasspath(), reconstructed);
    }

    @Test
    void pathFreeLedgerRejectsNoncontiguousOrdinals() {
        RuntimeArtifactIdentity.ClasspathSnapshot invalid =
                RuntimeArtifactIdentity.identifyClasspathEntries(List.of(
                        new RuntimeArtifactIdentity.ClasspathEntryIdentity(
                                2, "FILE", "talos.jar", "a".repeat(64), 10L, 1)));

        assertFalse(invalid.exact());
    }

    @Test
    void ordinaryRuntimeIdentityDoesNotSynchronouslyInspectTheApplicationClasspath() {
        String previous = System.clearProperty("talos.qualification.root");
        try {
            RuntimeArtifactIdentity.Snapshot ordinary = RuntimeArtifactIdentity.current();

            assertEquals("UNAVAILABLE", ordinary.kind());
            assertEquals("", ordinary.sha256());
            assertEquals(0L, ordinary.sizeBytes());
            assertEquals("", ordinary.classpathSha256());
            assertEquals(0L, ordinary.classpathSizeBytes());
            assertEquals(0, ordinary.classpathEntryCount());
            assertFalse(ordinary.exact());
        } finally {
            if (previous != null) {
                System.setProperty("talos.qualification.root", previous);
            }
        }
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
