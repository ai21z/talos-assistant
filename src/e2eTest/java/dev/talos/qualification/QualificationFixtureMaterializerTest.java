package dev.talos.qualification;

import dev.talos.core.security.NoFollowPathPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                QualificationFixtureMaterializer.materialize(catalog, "basic-workspace-v6.json", workspace);

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
                        catalog, "basic-workspace-v6.json", workspace));
        assertEquals("keep", Files.readString(workspace.resolve("keep.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void snapshotRejectsLinkedWorkspaceContentBeforeHashing() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("linked-workspace"));
        Path external = Files.writeString(
                temp.resolve("T1170_EXTERNAL_FIXTURE_CANARY.txt"),
                "T1170_EXTERNAL_FIXTURE_CANARY",
                StandardCharsets.UTF_8);
        Path link = workspace.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, external);
        } catch (java.io.IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }

        NoFollowPathPolicy.Violation failure = assertThrows(
                NoFollowPathPolicy.Violation.class,
                () -> QualificationFixtureMaterializer.snapshot(workspace));

        assertEquals(NoFollowPathPolicy.Reason.LINKED_COMPONENT, failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
        assertFalse(failure.getMessage().contains("T1170_EXTERNAL_FIXTURE_CANARY"));
    }
}
