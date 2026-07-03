package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseQaGateContractTest {

    @Test
    void releaseQaGateDefinesArtifactTaxonomyAndBlocksPublicationBeforeManualQa() throws Exception {
        String workCycle = Files.readString(Path.of("work-cycle-docs", "work-test-cycle.md"));
        String stepByStep = Files.readString(Path.of("work-cycle-docs", "work-test-cycle-step-by-step.md"));

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

        assertTrue(stepByStep.contains("Release QA Gate"),
                "step-by-step runbook must include the release QA gate");
        assertTrue(stepByStep.contains("No GitHub Release asset"),
                "step-by-step runbook must block release assets before QA");
        assertTrue(stepByStep.contains("Local staging artifacts"),
                "step-by-step runbook must permit local staging only as non-release evidence");
        assertTrue(stepByStep.contains("CI staging artifacts"),
                "step-by-step runbook must permit CI staging only as non-release evidence");
        assertTrue(stepByStep.contains("all current Gradle tasks and CI jobs"),
                "step-by-step runbook must require current task/workflow inventory");
        assertTrue(stepByStep.contains("manual PTY transcript"),
                "step-by-step runbook must require a manual PTY transcript");
        assertTrue(stepByStep.contains("two-model large-scale live audit"),
                "step-by-step runbook must require the Qwen/GPT-OSS live audit gate");
    }
}
