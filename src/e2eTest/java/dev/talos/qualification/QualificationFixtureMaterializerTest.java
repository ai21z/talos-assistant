package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationFixtureMaterializerTest {
    @TempDir
    Path temp;

    @Test
    void materializesExactFrozenFixtureAndHashesIt() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        Path workspace = temp.resolve("workspace");

        QualificationFixtureMaterializer.Snapshot snapshot =
                QualificationFixtureMaterializer.materialize(catalog, "basic-workspace-v5.json", workspace);

        assertEquals("# Atlas Notes", Files.readString(workspace.resolve("README.md"),
                StandardCharsets.UTF_8).lines().findFirst().orElseThrow());
        assertEquals(7, snapshot.files().size());
        assertTrue(snapshot.sha256().get("README.md").matches("[0-9a-f]{64}"));
        assertEquals(snapshot, QualificationFixtureMaterializer.snapshot(workspace));
    }

    @Test
    void refusesToOverlayAnExistingWorkspace() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationFixtureMaterializer.materialize(
                        catalog, "basic-workspace-v5.json", workspace));
        assertEquals("keep", Files.readString(workspace.resolve("keep.txt"), StandardCharsets.UTF_8));
    }
}
