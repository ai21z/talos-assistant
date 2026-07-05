package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseQaGateContractTest {

    @Test
    void releaseQaGateDefinesArtifactTaxonomyAndBlocksPublicationBeforeManualQa() throws Exception {
        String workCycle = Files.readString(Path.of("work-cycle-docs", "work-test-cycle.md"));
        String releaseRunbook = Files.readString(Path.of("work-cycle-docs", "runbooks", "release-candidate.md"));

        assertTrue(workCycle.contains("Release QA Gate"),
                "work-test cycle must name the release QA gate");
        assertTrue(workCycle.contains("Local staging artifact"),
                "work-test cycle must distinguish local staging artifacts");
        assertTrue(workCycle.contains("CI staging artifact"),
                "work-test cycle must distinguish CI staging artifacts");
        assertTrue(workCycle.contains("Public release artifact"),
                "work-test cycle must distinguish public release artifacts");
        assertTrue(workCycle.contains("draft GitHub Release asset"),
                "draft GitHub Release assets must be treated as release assets");
        assertTrue(workCycle.contains("manual PTY"),
                "release QA must require manual PTY evidence");
        assertTrue(workCycle.contains("large-scale live audit"),
                "release QA must require large-scale live audit evidence");
        assertTrue(workCycle.contains("runtime artifact canary scan"),
                "release QA must require artifact canary scanning for manual roots");
        assertTrue(workCycle.contains("named exclusions"),
                "skipped release gates must require named exclusions");

        assertTrue(releaseRunbook.contains("Release QA Gate"),
                "release runbook must include the release QA gate");
        assertTrue(releaseRunbook.contains("No GitHub Release asset"),
                "release runbook must block release assets before QA");
        assertTrue(releaseRunbook.contains("Local staging artifacts"),
                "release runbook must permit local staging only as non-release evidence");
        assertTrue(releaseRunbook.contains("CI staging artifacts"),
                "release runbook must permit CI staging only as non-release evidence");
        assertTrue(releaseRunbook.contains("all current Gradle tasks and CI jobs"),
                "release runbook must require current task/workflow inventory");
        assertTrue(releaseRunbook.contains("manual PTY transcript"),
                "release runbook must require a manual PTY transcript");
        assertTrue(releaseRunbook.contains("two-model large-scale live audit"),
                "release runbook must require the Qwen/GPT-OSS live audit gate");
    }
}
