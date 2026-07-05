package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureDocsHygieneTest {

    @Test
    void architectureDocsCarryConcreteDiagramsAndBoundaries() throws Exception {
        String overview = read("docs/architecture/overview.md");
        String execution = read("docs/architecture/execution-model.md");
        String trust = read("docs/architecture/trust-boundaries.md");
        String packages = read("docs/architecture/package-map.md");
        String release = read("docs/development/release-process.md");

        assertContainsAll(overview,
                "```mermaid",
                "model suggestion and runtime authority",
                "The final answer is the least trusted artifact");
        assertContainsAll(execution,
                "sequenceDiagram",
                "Tool Surface Rule",
                "Approval, Checkpoint, Verification");
        assertContainsAll(trust,
                "Boundary Table",
                "Protected Reads",
                "Artifact And Redaction Limits");
        assertContainsAll(packages,
                "Package Responsibilities",
                "Ownership Rules",
                "Refactor Pressure Points");
        assertContainsAll(release,
                "```mermaid",
                "GitHub Release is the canonical artifact host",
                "attestationSourceRepositoryDigest");
    }

    @Test
    void workCycleDocsExplainManualAndReleaseEvidenceDepth() throws Exception {
        String workCycle = read("work-cycle-docs/work-test-cycle.md");
        String manualQa = read("work-cycle-docs/runbooks/manual-qa.md");
        String release = read("work-cycle-docs/runbooks/release-candidate.md");

        assertContainsAll(workCycle,
                "Loop Map",
                "Evidence Quality",
                "manual PTY transcript");
        assertContainsAll(manualQa,
                "Evidence Layout",
                "Approval Lanes",
                "mixed runtime/model failure");
        assertContainsAll(release,
                "Candidate Flow",
                "Staging Verification",
                "Failure Rule");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void assertContainsAll(String text, String... expected) {
        List<String> missing = List.of(expected).stream()
                .filter(item -> !text.contains(item))
                .toList();
        assertTrue(missing.isEmpty(), "Missing expected documentation anchors: " + missing);
    }
}
