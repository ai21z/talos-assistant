package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSummaryEvidencePolicyTest {

    @Test
    void selectsOneAnchorByDeterministicPriority(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README"), "lower priority\n");
        Files.writeString(workspace.resolve("README.md"), "preferred\n");
        Files.writeString(workspace.resolve("README.txt"), "lower priority\n");

        assertEquals("README.md", ProjectSummaryEvidencePolicy.preferredAnchor(workspace).orElseThrow());
    }

    @Test
    void preservesActualCaseForCaseInsensitiveCandidateMatch(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("readme.rst"), "project facts\n");

        assertEquals("readme.rst", ProjectSummaryEvidencePolicy.preferredAnchor(workspace).orElseThrow());
    }

    @Test
    void ignoresDirectoriesAndFallsThroughToNextRegularCandidate(@TempDir Path workspace) throws Exception {
        Files.createDirectory(workspace.resolve("README.md"));
        Files.writeString(workspace.resolve("README.txt"), "project facts\n");

        assertEquals("README.txt", ProjectSummaryEvidencePolicy.preferredAnchor(workspace).orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoReadmeFamilyFileExists(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("script.js"), "console.log('ready');\n");

        assertTrue(ProjectSummaryEvidencePolicy.preferredAnchor(workspace).isEmpty());
        assertEquals(
                ProjectSummaryEvidencePolicy.SelectionStatus.ABSENT,
                ProjectSummaryEvidencePolicy.selectAnchor(workspace).status());
    }

    @Test
    void unavailableWorkspaceDoesNotMasqueradeAsNoAnchor(@TempDir Path workspace) {
        Path missing = workspace.resolve("missing");

        assertEquals(
                ProjectSummaryEvidencePolicy.SelectionStatus.UNAVAILABLE,
                ProjectSummaryEvidencePolicy.selectAnchor(missing).status());
    }

    @Test
    void doesNotAcceptSymbolicLinkAsProjectAnchor(@TempDir Path workspace) throws Exception {
        Path target = workspace.resolve("actual.md");
        Files.writeString(target, "outside the anchor policy\n");
        try {
            Files.createSymbolicLink(workspace.resolve("README.md"), target.getFileName());
        } catch (Exception unsupported) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        assertTrue(ProjectSummaryEvidencePolicy.preferredAnchor(workspace).isEmpty());
    }

    @Test
    void caseDistinctSiblingDoesNotSatisfyExactAnchorOnCaseSensitiveFileSystem(@TempDir Path workspace)
            throws Exception {
        Path expected = workspace.resolve("README.md");
        Path sibling = workspace.resolve("readme.md");
        Files.writeString(expected, "preferred project facts\n");
        Files.writeString(sibling, "different file\n");
        if (Files.isSameFile(expected, sibling)) {
            Assumptions.abort("The test filesystem is case-insensitive");
        }

        boolean grounded = ProjectSummaryEvidencePolicy.hasSourceEvidence(
                workspace,
                "README.md",
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "readme.md", true, false, false,
                        "read different file", "")));

        assertFalse(grounded);
    }
}
