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

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeQualityMarkdownReports renders dated reviewer reports from summary JSON")
    void rendersDatedReviewerReportsFromSummaryJson() throws Exception {
        Path projectDir = createBuildFixture();
        Path summariesDir = Files.createDirectories(projectDir.resolve("build/reports/talos"));
        Path reportsDir = Files.createDirectories(projectDir.resolve("reports"));
        String staleDateStamp = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        writeUtf8(reportsDir.resolve("coverage-" + staleDateStamp + "-090.md"), "stale generated coverage report\n");
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
        assertFalse(Files.exists(reportsDir.resolve("coverage-" + staleDateStamp + "-090.md")));
        assertTrue(Files.exists(reportsDir.resolve("notes.md")));

        String coverage = Files.readString(coverageReport, StandardCharsets.UTF_8);
        String e2e = Files.readString(e2eReport, StandardCharsets.UTF_8);
        String qodana = Files.readString(qodanaReport, StandardCharsets.UTF_8);
        String version = Files.readString(versionReport, StandardCharsets.UTF_8);

        assertTrue(coverage.startsWith("# Coverage Report"));
        assertTrue(coverage.contains("This report is useful as a release gate snapshot"));
        assertFalse(coverage.contains("Usefulness Assessment"));
        assertTrue(coverage.contains("80.00%"));
        assertTrue(coverage.contains("82.00% gate"),
                "coverage report must describe the enforced 82% instruction gate");
        assertFalse(coverage.contains("65.00%"),
                "coverage report must not describe the stale 65% instruction gate");
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

    @Test
    @DisplayName("candidate coverage lane excludes report-only architecture intelligence tests")
    void candidateCoverageLaneExcludesReportOnlyArchitectureIntelligenceTests() throws Exception {
        String build = Files.readString(ROOT.resolve("build.gradle.kts"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        String candidateBlock = blockBetween(
                build,
                "val candidateTest by tasks.registering(Test::class) {",
                "tasks.test {");
        String reportBlock = blockBetween(
                build,
                "val architectureIntelligenceReport by tasks.registering(Test::class) {",
                "val candidateE2eTest by tasks.registering(Test::class) {");

        assertTrue(candidateBlock.contains("excludeTestsMatching(\"dev.talos.architecture.intelligence.*\")"),
                "candidateTest must not run report-only architecture intelligence tests");
        assertTrue(reportBlock.contains("includeTestsMatching(\"dev.talos.architecture.intelligence.*\")"),
                "architectureIntelligenceReport remains the owner for report-only architecture tests");
        assertTrue(reportBlock.contains("dependsOn(\"writeQodanaSummary\")"),
                "architectureIntelligenceReport must keep the Qodana summary dependency that candidateTest does not own");
    }

    @Test
    @DisplayName("qualityReportGate accepts passing release-quality summaries")
    void qualityReportGateAcceptsPassingSummaries() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "passed", 0, 0, "passed", "qodana-results-match-current-candidate");

        BuildResult result = runQualityReportGate(projectDir, false);

        assertTrue(result.getOutput().contains("Talos quality report gate passed"));
    }

    @Test
    @DisplayName("qualityReportGate fails when candidate tests failed")
    void qualityReportGateFailsWhenCandidateTestsFailed() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "failed", 1, 0, "passed", "qodana-results-match-current-candidate");

        BuildResult result = runQualityReportGate(projectDir, true);

        assertTrue(result.getOutput().contains("coverage-summary tests must have zero failures and errors"));
    }

    @Test
    @DisplayName("releaseQualityPacket is an explicit maintainer release-evidence lane")
    void releaseQualityPacketIsExplicitMaintainerLane() throws Exception {
        String build = Files.readString(ROOT.resolve("build.gradle.kts"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        String packetBlock = blockBetween(
                build,
                "tasks.register(\"releaseQualityPacket\") {",
                "tasks.register(\"talosQualityLocal\") {");

        assertTrue(packetBlock.contains("dependsOn(\"check\", \"talosQualityLocal\", \"qualityReportGate\")"),
                "releaseQualityPacket must run the full gate, fresh local quality, and the summary gate");
        assertTrue(packetBlock.contains("mustRunAfter(\"talosQualityLocal\")"),
                "qualityReportGate must validate summaries after fresh local quality evidence is written");
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

    private BuildResult runQualityReportGate(Path projectDir, boolean expectFailure) {
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qualityReportGate", "-x", "talosQualitySummaries", "--stacktrace")
                .forwardOutput();
        return expectFailure ? runner.buildAndFail() : runner.build();
    }

    private void writeQualityGateSummaries(
            Path projectDir,
            double instructionPercent,
            String coverageStatus,
            int coverageFailures,
            int coverageErrors,
            String e2eStatus,
            String qodanaStatus) throws IOException {
        Path summariesDir = Files.createDirectories(projectDir.resolve("build/reports/talos"));
        int coveragePassed = 10 - coverageFailures - coverageErrors;
        writeUtf8(summariesDir.resolve("coverage-summary.json"), """
                {
                  "version": "0.10.8",
                  "coverageDataStatus": "jacoco-xml-present",
                  "instructionCoverage": { "covered": 855, "missed": 145, "percent": %.1f },
                  "branchCoverage": { "covered": 70, "missed": 30, "percent": 70.0 },
                  "tests": {
                    "total": 10,
                    "passed": %d,
                    "failures": %d,
                    "errors": %d,
                    "skipped": 0,
                    "status": "%s"
                  }
                }
                """.formatted(instructionPercent, coveragePassed, coverageFailures, coverageErrors, coverageStatus));
        writeUtf8(summariesDir.resolve("e2e-summary.json"), """
                {
                  "version": "0.10.8",
                  "testExecution": {
                    "total": 2,
                    "passed": 2,
                    "failures": 0,
                    "errors": 0,
                    "skipped": 0,
                    "status": "%s"
                  },
                  "jsonScenarioCoverage": {
                    "resourceCount": 1,
                    "executedResourceCount": 1,
                    "passedResourceCount": 1,
                    "coverageStatus": "complete"
                  }
                }
                """.formatted(e2eStatus));
        writeUtf8(summariesDir.resolve("qodana-summary.json"), """
                {
                  "version": "0.10.8",
                  "summaryStatus": "%s",
                  "requiredArtifacts": { "status": "all-required-artifacts-present" },
                  "provenance": {
                    "revisionStatus": "matches-current-revision",
                    "branchStatus": "matches-current-branch"
                  },
                  "criticalIssues": 0,
                  "criticalIssuesStatus": "derived-from-problem-severities",
                  "highIssues": 0,
                  "newIssues": 0
                }
                """.formatted(qodanaStatus));
        writeUtf8(summariesDir.resolve("version-summary.json"), """
                {
                  "version": "0.10.8",
                  "jarTaskStateInCurrentInvocation": {
                    "jarExists": true,
                    "status": "built-in-current-run"
                  },
                  "artifacts": [
                    {
                      "name": "talos.jar",
                      "exists": true
                    }
                  ]
                }
                """);
    }

    private String blockBetween(String content, String start, String end) {
        int startIndex = content.indexOf(start);
        int endIndex = content.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0, "missing build script block start: " + start);
        assertTrue(endIndex > startIndex, "missing build script block end: " + end);
        return content.substring(startIndex, endIndex);
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
