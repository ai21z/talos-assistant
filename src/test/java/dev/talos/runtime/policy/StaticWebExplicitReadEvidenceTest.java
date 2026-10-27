package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticWebExplicitReadEvidenceTest {
    @Test
    void linkedEvidenceDoesNotSatisfyOtherNamedSources(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED,
                check(workspace, linkedReads(), Set.of("README.md", "index.html", "script.js", "scripts.js")).status());
    }

    @Test
    void allNamedContentReadsSatisfyAlongsideLinkage(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        var reads = new ArrayList<>(linkedReads());
        reads.add(read("README.md"));
        reads.add(read("scripts.js"));
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED,
                check(workspace, reads, Set.of("README.md", "index.html", "script.js", "scripts.js")).status());
    }

    @Test
    void deniedNamedReadDominatesMissingSources(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        var reads = new ArrayList<>(linkedReads());
        reads.add(new ToolCallLoop.ToolOutcome("talos.read_file", "scripts.js", false, false, true,
                "", "Denied", null, ToolError.DENIED));
        assertEquals(EvidenceObligationVerifier.Status.BLOCKED,
                check(workspace, reads, Set.of("README.md", "scripts.js")).status());
    }

    @Test
    void missingFileOrDirectoryListingDoesNotProveContentRead(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        var reads = new ArrayList<>(linkedReads());
        reads.add(new ToolCallLoop.ToolOutcome("talos.read_file", "scripts.js", false, false, false,
                "", "Missing", null, ToolError.NOT_FOUND));
        reads.add(new ToolCallLoop.ToolOutcome("talos.list_dir", ".", true, false, false,
                "README.md\nscripts.js", ""));
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED,
                check(workspace, reads, Set.of("scripts.js", "README.md")).status());
    }

    @Test
    void explicitCssSiblingIsRequiredEvenWhenOnlyOtherSiblingIsLinked(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
        Files.writeString(workspace.resolve("styles.css"), ".sentinel {}\n");
        var reads = new ArrayList<>(linkedReads());
        reads.add(read("style.css"));
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED,
                check(workspace, reads, Set.of("style.css", "styles.css")).status());
        reads.add(read("styles.css"));
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED,
                check(workspace, reads, Set.of("style.css", "styles.css")).status());
    }

    @Test
    void inferredAbsentCssDoesNotBecomeMandatory(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED,
                EvidenceObligationVerifier.verify(EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                        Set.of("index.html", "script.js", "style.css"), linkedReads(), workspace, Set.of()).status());
    }

    private static EvidenceObligationVerifier.Result check(Path workspace,
            List<ToolCallLoop.ToolOutcome> reads, Set<String> explicit) {
        var targets = new java.util.HashSet<>(explicit);
        targets.add("index.html");
        return EvidenceObligationVerifier.verify(EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                targets, reads, workspace, explicit);
    }

    private static List<ToolCallLoop.ToolOutcome> linkedReads() {
        return List.of(read("index.html"), read("script.js"));
    }

    private static ToolCallLoop.ToolOutcome read(String path) {
        return new ToolCallLoop.ToolOutcome("talos.read_file", path, true, false, false, "content", "");
    }

    private static void fixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"), "// Linked\n");
        Files.writeString(workspace.resolve("scripts.js"), "// Unlinked\n");
        Files.writeString(workspace.resolve("README.md"), "Documentation\n");
    }
}
