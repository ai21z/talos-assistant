package dev.talos.build;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Quality Markdown reports task")
class QualityMarkdownReportsTaskTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeQualityMarkdownReports renders dated reviewer reports from summary JSON")
    void rendersDatedReviewerReportsFromSummaryJson() throws Exception {
        Path projectDir = createBuildFixture();
        Path summariesDir = Files.createDirectories(projectDir.resolve("build/reports/talos"));
        Path reportsDir = Files.createDirectories(projectDir.resolve("reports"));
        writeUtf8(reportsDir.resolve("coverage-01052026-090.md"), "stale generated coverage report\n");
        writeUtf8(reportsDir.resolve("notes.md"), "manual notes must be preserved\n");

        writeUtf8(summariesDir.resolve("coverage-summary.json"), """
                {
                  "version": "0.9.0",
                  "coverageDataStatus": "jacoco-xml-present",
                  "instructionCoverage": { "covered": 80, "missed": 20, "percent": 80.0 },
                  "branchCoverage": { "covered": 3, "missed": 1, "percent": 75.0 },
                  "tests": { "total": 4, "passed": 3, "failures": 0, "errors": 0, "skipped": 1, "status": "passed-with-skips" }
                }
                """);
        writeUtf8(summariesDir.resolve("e2e-summary.json"), """
                {
                  "version": "0.9.0",
                  "testExecution": { "total": 2, "passed": 2, "failures": 0, "errors": 0, "skipped": 0, "status": "passed" },
                  "scenarioResources": { "jsonScenarioFiles": ["01-sample-flow.json"] },
                  "jsonScenarioCoverage": {
                    "executedTestCaseCount": 1,
                    "untaggedExecutedTestCaseCount": 1,
                    "executedResourceCount": 1,
                    "passedResourceCount": 1,
                    "resourceCount": 1,
                    "resourceStatuses": [
                      {
                        "resource": "scenarios/01-sample-flow.json",
                        "status": "passed"
                      }
                    ]
                  },
                  "v1ScenarioPack": {
                    "resources": [
                      {
                        "resource": "scenarios/01-sample-flow.json",
                        "name": "sample flow",
                        "runner": "executor",
                        "v1Pack": true,
                        "claims": ["read-only-requests-remain-read-only", "inspect-first-analysis-is-grounded"]
                      }
                    ],
                    "passedClaims": ["read-only-requests-remain-read-only"],
                    "unprovenClaims": ["inspect-first-analysis-is-grounded"]
                  }
                }
                """);
        writeUtf8(summariesDir.resolve("qodana-summary.json"), """
                {
                  "version": "0.9.0",
                  "summaryStatus": "qodana-results-match-current-candidate",
                  "requiredArtifacts": { "status": "sarif-only-results-present" },
                  "provenance": {
                    "qodanaSourceBranch": "main",
                    "currentGitBranch": "main",
                    "qodanaSourceRevision": "abcdef123456",
                    "currentGitRevision": "abcdef123456",
                    "branchStatus": "matches-current-branch",
                    "revisionStatus": "matches-current-revision"
                  },
                  "linter": "QDJVM",
                  "linterVersion": "253.31821",
                  "totalIssues": 3,
                  "severityCounts": { "HIGH": 2, "MODERATE": 1 },
                  "sarifLevelCounts": { "warning": 2, "note": 1 }
                }
                """);
        writeUtf8(summariesDir.resolve("version-summary.json"), """
                {
                  "version": "0.9.0",
                  "jarBuiltAt": "2026-04-23T10:45:50.241Z",
                  "artifacts": [
                    {
                      "name": "talos.jar",
                      "exists": true,
                      "lastModifiedEpochMs": 1776941150241
                    }
                  ],
                  "jarTaskStateInCurrentInvocation": {
                    "jarExists": true,
                    "jarLastModifiedIso": "2026-04-23T10:45:50.241Z",
                    "status": "built-in-current-run"
                  }
                }
                """);

        runWriteQualityMarkdownReports(projectDir);

        String dateStamp = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        Path coverageReport = projectDir.resolve("reports/coverage-" + dateStamp + "-090.md");
        Path e2eReport = projectDir.resolve("reports/e2e-" + dateStamp + "-090.md");
        Path qodanaReport = projectDir.resolve("reports/qodana-" + dateStamp + "-090.md");
        Path versionReport = projectDir.resolve("reports/version-" + dateStamp + "-090.md");

        assertTrue(Files.exists(coverageReport));
        assertTrue(Files.exists(e2eReport));
        assertTrue(Files.exists(qodanaReport));
        assertTrue(Files.exists(versionReport));
        assertFalse(Files.exists(reportsDir.resolve("coverage-01052026-090.md")));
        assertTrue(Files.exists(reportsDir.resolve("notes.md")));

        String coverage = Files.readString(coverageReport, StandardCharsets.UTF_8);
        String e2e = Files.readString(e2eReport, StandardCharsets.UTF_8);
        String qodana = Files.readString(qodanaReport, StandardCharsets.UTF_8);
        String version = Files.readString(versionReport, StandardCharsets.UTF_8);

        assertTrue(coverage.startsWith("# Coverage Report"));
        assertTrue(coverage.contains("This report is useful as a release gate snapshot"));
        assertFalse(coverage.contains("Usefulness Assessment"));
        assertTrue(coverage.contains("80.00%"));
        assertTrue(e2e.contains("sample flow"));
        assertTrue(e2e.contains("## V1 Scenario Pack"));
        assertTrue(e2e.contains("PASSED"));
        assertTrue(e2e.contains("Did every JSON scenario resource pass?"));
        assertTrue(e2e.contains("Proven V1 claims"));
        assertTrue(e2e.contains("read-only-requests-remain-read-only"));
        assertTrue(e2e.contains("inspect-first-analysis-is-grounded"));
        assertTrue(qodana.contains("3 Qodana findings"));
        assertTrue(qodana.contains("Yes, `2` high"));
        assertTrue(version.contains("artifact is fresh for this packet"));
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

    private BuildResult runWriteQualityMarkdownReports(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("writeQualityMarkdownReports", "-x", "talosQualitySummaries", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
