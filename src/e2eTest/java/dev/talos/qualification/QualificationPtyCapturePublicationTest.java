package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPtyCapturePublicationTest {
    @TempDir
    Path temp;

    @Test
    void protocolV1ThroughV5PtyCaptureRefusesBeforeChangingPacketState() throws Exception {
        for (int protocol = 1; protocol <= 5; protocol++) {
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(protocol);
            QualificationTestPacket packet = QualificationTestPacket.create(
                    temp.resolve("legacy-" + protocol), catalog);
            Map<String, FileState> before = treeState(packet.root());

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> QualificationPtyCaptureMain.main(new String[]{
                            packet.root().toString(),
                            catalog.admission().scenarios().getFirst().id(),
                            "1"
                    }));

            assertTrue(failure.getMessage().contains("publication writes exactly protocol v6"),
                    failure::getMessage);
            assertEquals(before, treeState(packet.root()), "protocol=" + protocol);
        }
    }

    @Test
    void undeclaredPtyRepetitionRefusesBeforeChangingPacketState() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationTestPacket packet = QualificationTestPacket.create(temp.resolve("v6"), catalog);
        Map<String, FileState> before = treeState(packet.root());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationPtyCaptureMain.main(new String[]{
                        packet.root().toString(),
                        catalog.admission().scenarios().getFirst().id(),
                        "4"
                }));

        assertTrue(failure.getMessage().contains("repetition must identify one trial"),
                failure::getMessage);
        assertEquals(before, treeState(packet.root()));
    }

    private static Map<String, FileState> treeState(Path root) throws Exception {
        Map<String, FileState> state = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                var attributes = Files.readAttributes(
                        path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                String kind = attributes.isRegularFile()
                        ? "FILE"
                        : attributes.isDirectory()
                        ? "DIRECTORY"
                        : attributes.isSymbolicLink()
                        ? "SYMLINK"
                        : "OTHER";
                byte[] bytes = attributes.isRegularFile() ? Files.readAllBytes(path) : null;
                state.put(
                        root.relativize(path).toString().replace('\\', '/'),
                        new FileState(
                                kind,
                                attributes.size(),
                                attributes.lastModifiedTime(),
                                bytes == null ? "" : QualificationScenarioBank.sha256(bytes)));
            }
        }
        return Map.copyOf(state);
    }

    private record FileState(
            String kind,
            long size,
            java.nio.file.attribute.FileTime modified,
            String sha256) {}
}
