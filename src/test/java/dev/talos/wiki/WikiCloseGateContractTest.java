package dev.talos.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Talos wiki evidence close gate contract")
class WikiCloseGateContractTest {

    @Test
    @DisplayName("Gradle exposes an explicit wiki evidence close gate without wiring it into check")
    void gradleExposesExplicitWikiEvidenceCloseGate() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("wikiEvidenceCloseGate"),
                "build.gradle.kts must register wikiEvidenceCloseGate");
        assertTrue(build.contains("dependsOn(wikiLintWithEvidence)"),
                "wikiEvidenceCloseGate must depend on wikiLintWithEvidence");
        assertFalse(build.matches("(?s)tasks\\.check\\s*\\{[^}]*wikiEvidenceCloseGate[^}]*}"),
                "wikiEvidenceCloseGate must not be wired into the normal check task");
        assertFalse(blockBetween(build, "val architectureIntelligenceReport", "val wikiLintStructural")
                        .contains("outputs.upToDateWhen { false }"),
                "architectureIntelligenceReport must not be globally forced out-of-date");
    }

    @Test
    @DisplayName("default test task excludes report generators and generated-report liveness checks")
    void defaultTestTaskExcludesGeneratedReportTests() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        String testBlock = blockBetween(build, "tasks.test {", "val cleanArchitectureIntelligenceReport");
        assertTrue(testBlock.contains("excludeTestsMatching(\"dev.talos.architecture.intelligence.*\")"),
                "default test must exclude architecture intelligence report tests");
        assertTrue(testBlock.contains("excludeTestsMatching(\"dev.talos.wiki.WikiEvidenceLivenessTest\")"),
                "default test must exclude generated-report wiki evidence liveness");
    }

    @Test
    @DisplayName("wiki evidence liveness cleans and regenerates architecture evidence before validation")
    void wikiEvidenceLivenessCleansAndRegeneratesArchitectureEvidence() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("cleanArchitectureIntelligenceReport"),
                "build.gradle.kts must register a clean task for architecture intelligence evidence");
        String lintBlock = blockBetween(build, "val wikiLintWithEvidence", "val wikiEvidenceCloseGate");
        assertTrue(lintBlock.contains("cleanArchitectureIntelligenceReport"),
                "wikiLintWithEvidence must depend on the architecture evidence clean task");
        assertTrue(build.contains("mustRunAfter(cleanArchitectureIntelligenceReport)"),
                "architectureIntelligenceReport must run after the clean task");
    }

    @Test
    @DisplayName("candidate cut script runs wiki evidence close gate after check and before quality summaries")
    void candidateCutRunsWikiEvidenceCloseGate() throws IOException {
        String script = Files.readString(Path.of("scripts", "cut-candidate.ps1"), StandardCharsets.UTF_8);
        String gateCommand = ".\\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon";
        assertTrue(script.contains(gateCommand),
                "cut-candidate.ps1 must run the wiki evidence close gate with --rerun-tasks");
        int checkIndex = script.indexOf(".\\gradlew.bat check --no-daemon");
        int gateIndex = script.indexOf(gateCommand);
        int summariesIndex = script.indexOf(".\\gradlew.bat talosQualitySummaries --no-daemon");
        assertTrue(checkIndex >= 0, "candidate script must run gradlew check");
        assertTrue(summariesIndex >= 0, "candidate script must run talosQualitySummaries");
        assertTrue(checkIndex < gateIndex,
                "wiki evidence close gate must run after the mandatory post-bump check");
        assertTrue(gateIndex < summariesIndex,
                "wiki evidence close gate must run before talosQualitySummaries");
        assertTrue(script.contains("wikiEvidenceCloseGate"),
                "candidate script header/dry-run output must name wikiEvidenceCloseGate");
    }

    @Test
    @DisplayName("candidate dry-run reports the plan before enforcing dirty-tree cut preconditions")
    void candidateDryRunPrecedesDirtyTreeCutPreconditions() throws IOException {
        String script = Files.readString(Path.of("scripts", "cut-candidate.ps1"), StandardCharsets.UTF_8);
        int dryRunIndex = script.indexOf("if ($DryRun)");
        int dirtyCheckIndex = script.indexOf("if ($dirty)");
        assertTrue(dryRunIndex >= 0, "candidate script must have a DryRun branch");
        assertTrue(dirtyCheckIndex >= 0, "candidate script must still enforce dirty-tree preconditions");
        assertTrue(dryRunIndex < dirtyCheckIndex,
                "DryRun must print the non-mutating plan before dirty-tree cut preconditions throw");
    }

    private static String blockBetween(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        int endIndex = text.indexOf(end);
        assertTrue(startIndex >= 0, "missing block start: " + start);
        assertTrue(endIndex > startIndex, "missing block end after " + start + ": " + end);
        return text.substring(startIndex, endIndex);
    }
}
