package dev.talos.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

@DisplayName("Qodana summary task")
class QodanaSummaryTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeQodanaSummary reports missing results when .qodana is absent")
    void reportsMissingResultsWhenQodanaRootAbsent() throws Exception {
        Path projectDir = createBuildFixture();

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> requiredArtifacts = castMap(summary.get("requiredArtifacts"));

        assertEquals("qodana-results-missing", summary.get("summaryStatus"));
        assertEquals("qodana-results-missing", requiredArtifacts.get("status"));
        assertIterableEquals(
                List.of("metaInformation.json", "result-allProblems.json", "qodana.sarif.json"),
                castList(requiredArtifacts.get("missing"))
        );
    }

    @Test
    @DisplayName("writeQodanaSummary marks the packet incomplete when any required artifact is missing")
    void reportsIncompleteWhenAnyRequiredArtifactIsMissing() throws Exception {
        Path projectDir = createBuildFixture();
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));

        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "253.31821",
                  "total": 1,
                  "attributes": {}
                }
                """);
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                {
                  "listProblem": [
                    { "severity": "HIGH" }
                  ]
                }
                """);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> requiredArtifacts = castMap(summary.get("requiredArtifacts"));
        Map<String, Object> filePresence = castMap(requiredArtifacts.get("files"));

        assertEquals("qodana-results-incomplete", summary.get("summaryStatus"));
        assertEquals("required-artifacts-missing", requiredArtifacts.get("status"));
        assertIterableEquals(List.of("qodana.sarif.json"), castList(requiredArtifacts.get("missing")));
        assertEquals(Boolean.TRUE, filePresence.get("metaInformation"));
        assertEquals(Boolean.TRUE, filePresence.get("allProblems"));
        assertEquals(Boolean.FALSE, filePresence.get("sarif"));
    }

    @Test
    @DisplayName("writeQodanaSummary reports incomplete provenance when artifacts exist but candidate identity cannot be matched")
    void reportsIncompleteProvenanceWhenArtifactsExistWithoutIdentity() throws Exception {
        Path projectDir = createBuildFixture();
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));

        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "253.31821",
                  "total": 2,
                  "attributes": {}
                }
                """);
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                {
                  "listProblem": [
                    { "severity": "HIGH" },
                    { "severity": "MODERATE" }
                  ]
                }
                """);
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "results": [
                        { "level": "warning" },
                        { "level": "note" }
                      ]
                    }
                  ]
                }
                """);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> requiredArtifacts = castMap(summary.get("requiredArtifacts"));
        Map<String, Object> provenance = castMap(summary.get("provenance"));

        assertEquals("qodana-provenance-incomplete", summary.get("summaryStatus"));
        assertEquals("all-required-artifacts-present", requiredArtifacts.get("status"));
        assertEquals("qodana-revision-unavailable", provenance.get("revisionStatus"));
        assertEquals("qodana-branch-unavailable", provenance.get("branchStatus"));
        assertEquals(1, summary.get("highIssues"));
        assertEquals("unknown-no-baseline-state", summary.get("newIssuesStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary reports matching candidate identity when provenance aligns with current branch and revision")
    void reportsMatchingProvenanceWhenQodanaAgreesWithCurrentGit() throws Exception {
        Path projectDir = createBuildFixture();
        // Initialize a throwaway git repo inside the fixture so gitOutput(...) returns
        // deterministic values; the summary pulls branch+revision from `git rev-parse`.
        initGitFixture(projectDir);
        String currentRevision = runCommand(projectDir, "git", "rev-parse", "HEAD");
        String currentBranch = runCommand(projectDir, "git", "rev-parse", "--abbrev-ref", "HEAD");

        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));
        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "253.31821",
                  "total": 0,
                  "attributes": {
                    "vcs": {
                      "sarifIdea": {
                        "revisionId": "%s",
                        "branch": "%s"
                      }
                    }
                  }
                }
                """.formatted(currentRevision, currentBranch));
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                { "listProblem": [] }
                """);
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "results": [
                        { "level": "warning", "baselineState": "unchanged" }
                      ]
                    }
                  ]
                }
                """);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> provenance = castMap(summary.get("provenance"));

        assertEquals("qodana-results-match-current-candidate", summary.get("summaryStatus"));
        assertEquals("matches-current-revision", provenance.get("revisionStatus"));
        assertEquals("matches-current-branch", provenance.get("branchStatus"));
        assertEquals(0, summary.get("newIssues"));
        assertEquals("derived-from-sarif-baseline-state", summary.get("newIssuesStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary writes a fail-soft payload when the SARIF file is malformed")
    void writesFailSoftPayloadWhenSarifIsMalformed() throws Exception {
        Path projectDir = createBuildFixture();
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));

        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                { "linter": "QDJVM", "linterVersion": "253.31821", "total": 0, "attributes": {} }
                """);
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                { "listProblem": [] }
                """);
        // Deliberately malformed JSON - must not take the packet down.
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), "{ this is not valid json");

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("summary-generation-failed", summary.get("summaryStatus"));
        assertEquals("qodana-summary", summary.get("summaryName"));
        assertEquals(BuildTestVersions.currentTalosVersion(), summary.get("version"));
    }

    private void initGitFixture(Path projectDir) throws Exception {
        runCommand(projectDir, "git", "init", "-q");
        runCommand(projectDir, "git", "config", "user.email", "t@t");
        runCommand(projectDir, "git", "config", "user.name", "t");
        runCommand(projectDir, "git", "config", "commit.gpgsign", "false");
        runCommand(projectDir, "git", "add", "-A");
        runCommand(projectDir, "git", "commit", "-q", "-m", "fixture");
    }

    private String runCommand(Path projectDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    private Path createBuildFixture() throws IOException {
        Path projectDir = tempDir.resolve("fixture");
        Files.createDirectories(projectDir);
        copyProjectFile("build.gradle.kts", projectDir.resolve("build.gradle.kts"));
        copyProjectFile("settings.gradle", projectDir.resolve("settings.gradle"));
        copyProjectFile("gradle.properties", projectDir.resolve("gradle.properties"));
        return projectDir;
    }

    private void copyProjectFile(String sourceName, Path target) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Files.copy(root.resolve(sourceName), target);
    }

    private BuildResult runWriteQodanaSummary(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("writeQodanaSummary", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private Map<String, Object> readSummary(Path projectDir) throws IOException {
        Path summaryFile = projectDir.resolve("build/reports/talos/qodana-summary.json");
        return JSON.readValue(Files.readString(summaryFile, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
