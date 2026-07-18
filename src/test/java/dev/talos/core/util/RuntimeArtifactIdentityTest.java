package dev.talos.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
