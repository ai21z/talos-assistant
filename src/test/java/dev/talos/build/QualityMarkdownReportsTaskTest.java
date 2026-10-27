package dev.talos.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Quality Markdown reports task")
class QualityMarkdownReportsTaskTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private static final long TERMINATION_TIMEOUT_SECONDS = 5;

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
                  "requiredArtifacts": { "status": "all-required-artifacts-present" },
                  "artifactBinding": { "status": "matches-run-binding" },
                  "resultSchema": { "status": "validated-consistent-results" },
                  "provenance": {
                    "qodanaSourceBranch": "main",
                    "currentGitBranch": "main",
                    "qodanaSourceRevision": "abcdef123456",
                    "currentGitRevision": "abcdef123456",
                    "branchStatus": "matches-current-branch",
                    "revisionStatus": "matches-current-revision"
                  },
                  "sourceSnapshot": {
                    "status": "matches-current-source-snapshot",
                    "current": {
                      "algorithm": "talos-qodana-current-source-v2",
                      "gitHead": "abcdef123456abcdef123456abcdef123456abcd",
                      "trackedFiles": 200,
                      "trackedBytes": 120000,
                      "changedTrackedFiles": 4,
                      "diffBytes": 120,
                      "diffSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "snapshotSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    }
                  },
                  "scanScope": {
                    "status": "required-source-roots-observed",
                    "main": "inspection-activity-observed",
                    "unitTest": "no-inspection-activity-observed",
                    "e2eTest": "inspection-activity-observed"
                  },
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
                  "totalIssues": 3,
                  "severityCounts": { "HIGH": 2, "MODERATE": 1 },
                  "sarifLevelCounts": { "warning": 2, "note": 1 },
                  "criticalIssues": 0,
                  "criticalIssuesStatus": "derived-from-validated-consistent-results"
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
        assertTrue(qodana.contains("## Observed Scan Scope"));
        assertTrue(qodana.contains("`src/test` | `no-inspection-activity-observed`"));
        assertTrue(qodana.contains("Unit-test correctness is owned by the `test` and `candidateTest` lanes"));
        assertTrue(qodana.contains("exact HEAD + tracked paths + raw file bytes match"));
        assertTrue(qodana.contains("the lane is current, its exact result bytes are authenticated"));
        assertTrue(qodana.contains("Trust the report as bound to the current source snapshot."));
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
    @DisplayName("qualityReportGate rejects every Qodana status outside the exact success allowlist")
    void qualityReportGateRejectsNonExactQodanaStatus() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "passed", 0, 0, "passed", "qodana-run-binding-missing");

        BuildResult result = runQualityReportGate(projectDir, true);

        assertTrue(result.getOutput().contains(
                "qodana-summary status must be exactly `qodana-results-match-current-candidate`"));
    }

    @Test
    @DisplayName("matching artifacts cannot make conflicting native provenance look acceptable in Markdown")
    void markdownAndGateRejectConflictingNativeProvenanceDespiteExactArtifacts() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "passed", 0, 0, "passed", "stale-qodana-provenance");
        Path qodanaSummary = projectDir.resolve("build/reports/talos/qodana-summary.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = JSON.readValue(qodanaSummary.toFile(), Map.class);
        summary.put("provenance", Map.of(
                "branchStatus", "mismatches-current-branch",
                "revisionStatus", "matches-current-revision",
                "qodanaSourceBranch", "different-branch",
                "currentGitBranch", "main"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(qodanaSummary.toFile(), summary);

        runWriteQualityMarkdownReports(projectDir);
        String qodana;
        try (var reports = Files.list(projectDir.resolve("reports"))) {
            Path report = reports.filter(path -> path.getFileName().toString().startsWith("qodana-"))
                    .findFirst().orElseThrow();
            qodana = Files.readString(report, StandardCharsets.UTF_8);
        }
        assertTrue(qodana.contains("exact HEAD + tracked paths + raw file bytes match"));
        assertTrue(qodana.contains("stale-qodana-provenance"));
        assertTrue(qodana.contains("the lane is not acceptable as current release evidence."));
        assertTrue(qodana.contains("Do not use this report as current release evidence."));
        assertFalse(qodana.contains("the lane is current, its exact result bytes are authenticated"));
        assertFalse(qodana.contains("Trust the report as bound to the current source snapshot."));

        BuildResult gate = runQualityReportGate(projectDir, true);
        assertTrue(gate.getOutput().contains(
                "qodana-summary status must be exactly `qodana-results-match-current-candidate`"));
    }

    @Test
    @DisplayName("qualityReportGate rejects a stale Qodana analysis-engine identity")
    void qualityReportGateRejectsStaleQodanaEngine() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "passed", 0, 0, "passed",
                "qodana-results-match-current-candidate");
        Path qodanaSummary = projectDir.resolve("build/reports/talos/qodana-summary.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = JSON.readValue(qodanaSummary.toFile(), Map.class);
        summary.put("linterVersion", "253.31821");
        JSON.writerWithDefaultPrettyPrinter().writeValue(qodanaSummary.toFile(), summary);

        BuildResult result = runQualityReportGate(projectDir, true);

        assertTrue(result.getOutput().contains(
                "qodana-summary must authenticate the configured QDJVM 2026.1 analysis engine"));
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
    @DisplayName("qualityReportGate rejects tracked-byte drift after the Qodana summary was written")
    void qualityReportGateRepinsCurrentSourceAtGateTime() throws Exception {
        Path projectDir = createBuildFixture();
        writeQualityGateSummaries(projectDir, 85.5, "passed", 0, 0, "passed",
                "qodana-results-match-current-candidate");
        Files.writeString(
                projectDir.resolve("gradle.properties"),
                "\nchangedAfterSummary=true\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND
        );

        BuildResult result = runQualityReportGate(projectDir, true);

        assertTrue(result.getOutput().contains(
                "qodana-summary current source snapshot must still match exact tracked bytes at gate time"));
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

    private Path createBuildFixture() throws Exception {
        Path projectDir = tempDir.resolve("fixture");
        Files.createDirectories(projectDir);
        copyProjectFile("build.gradle.kts", projectDir.resolve("build.gradle.kts"));
        copyProjectFile("settings.gradle", projectDir.resolve("settings.gradle"));
        copyProjectFile("gradle.properties", projectDir.resolve("gradle.properties"));
        copyProjectFile(".gitignore", projectDir.resolve(".gitignore"));
        initGitFixture(projectDir);
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
            String qodanaStatus) throws Exception {
        Path summariesDir = Files.createDirectories(projectDir.resolve("build/reports/talos"));
        String currentSourceSnapshot = JSON.writeValueAsString(sourceSnapshot(projectDir));
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
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
                  "requiredArtifacts": { "status": "all-required-artifacts-present" },
                  "artifactBinding": { "status": "matches-run-binding" },
                  "resultSchema": { "status": "validated-consistent-results" },
                  "provenance": {
                    "revisionStatus": "matches-current-revision",
                    "branchStatus": "matches-current-branch"
                  },
                  "sourceSnapshot": {
                    "status": "matches-current-source-snapshot",
                    "current": %s
                  },
                  "scanScope": {
                    "status": "required-source-roots-observed",
                    "main": "inspection-activity-observed",
                    "unitTest": "no-inspection-activity-observed",
                    "e2eTest": "inspection-activity-observed"
                  },
                  "criticalIssues": 0,
                  "criticalIssuesStatus": "derived-from-validated-consistent-results",
                  "highIssues": 0,
                  "newIssues": 0
                }
                """.formatted(qodanaStatus, currentSourceSnapshot));
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

    private void initGitFixture(Path projectDir) throws Exception {
        runCommand(projectDir, "git", "init", "-q");
        runCommand(projectDir, "git", "config", "user.email", "t@t");
        runCommand(projectDir, "git", "config", "user.name", "t");
        runCommand(projectDir, "git", "config", "commit.gpgsign", "false");
        runCommand(projectDir, "git", "add", "-A");
        runCommand(projectDir, "git", "commit", "-q", "-m", "fixture");
    }

    private String runCommand(Path projectDir, String... command) throws Exception {
        return new String(runCommandBytes(projectDir, command), StandardCharsets.UTF_8).trim();
    }

    private byte[] runCommandBytes(Path projectDir, String... command) throws Exception {
        Path stdoutFile = Files.createTempFile(tempDir, "quality-command-out-", ".bin");
        Path stderrFile = Files.createTempFile(tempDir, "quality-command-err-", ".bin");
        Throwable commandFailure = null;
        try {
            Process process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();
            int exit = awaitCommandExit(process, String.join(" ", command));
            byte[] stdout = Files.readAllBytes(stdoutFile);
            byte[] stderr = Files.readAllBytes(stderrFile);
            if (exit != 0) {
                throw new IOException("command failed (" + exit + "): "
                        + new String(stderr, StandardCharsets.UTF_8));
            }
            return stdout;
        } catch (Exception | Error failure) {
            commandFailure = failure;
            throw failure;
        } finally {
            try {
                deleteCommandOutputs(stdoutFile, stderrFile);
            } catch (IOException cleanupFailure) {
                if (commandFailure != null) {
                    commandFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private int awaitCommandExit(Process process, String description) throws InterruptedException, IOException {
        ProcessHandle root = process.toHandle();
        Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                retainDescendants(root, observedDescendants);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) throw new InterruptedException();
            }
            retainDescendants(root, observedDescendants);
            if (!process.isAlive()) {
                long drainDeadline = System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
                while (System.nanoTime() < drainDeadline) {
                    retainDescendants(root, observedDescendants);
                    if (observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                    if (Thread.interrupted()) throw new InterruptedException();
                }
                retainDescendants(root, observedDescendants);
            }
        } catch (InterruptedException interrupted) {
            terminateAfterCommandFailure(process, root, observedDescendants, interrupted);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (RuntimeException | Error failure) {
            terminateAfterCommandFailure(process, root, observedDescendants, failure);
            throw failure;
        }
        if (!process.isAlive()) {
            List<Long> survivors = observedDescendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .toList();
            if (survivors.isEmpty()) return process.exitValue();
            IOException failure = new IOException(
                    description + " left live descendant processes: " + survivors);
            terminateAfterCommandFailure(process, root, observedDescendants, failure);
            throw failure;
        }
        IOException failure = new IOException(
                description + " timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds");
        terminateAfterCommandFailure(process, root, observedDescendants, failure);
        throw failure;
    }

    private void retainDescendants(ProcessHandle root, Set<ProcessHandle> observedDescendants) {
        FixtureProcessOwnership.retainDescendants(root, observedDescendants);
    }

    private void terminateAfterCommandFailure(
            Process process,
            ProcessHandle root,
            Set<ProcessHandle> observedDescendants,
            Throwable originalFailure) {
        try {
            terminateProcessTree(process, root, observedDescendants);
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private void terminateProcessTree(
            Process process,
            ProcessHandle root,
            Set<ProcessHandle> observedDescendants) {
        boolean restoreInterrupt = Thread.interrupted();
        RuntimeException inspectionFailure = null;
        try {
            long gracefulDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
            while (System.nanoTime() < gracefulDeadline) {
                try {
                    retainDescendants(root, observedDescendants);
                } catch (RuntimeException failure) {
                    if (inspectionFailure == null) inspectionFailure = failure;
                }
                inspectionFailure = destroyObservedTree(
                        process, root, observedDescendants, false, inspectionFailure);
                if (!process.isAlive()
                        && observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    if (inspectionFailure != null) {
                        throw new AssertionError(
                                "process-tree inspection was incomplete during termination",
                                inspectionFailure);
                    }
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            }

            long forcedDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
            while (System.nanoTime() < forcedDeadline) {
                try {
                    retainDescendants(root, observedDescendants);
                } catch (RuntimeException failure) {
                    if (inspectionFailure == null) inspectionFailure = failure;
                }
                inspectionFailure = destroyObservedTree(
                        process, root, observedDescendants, true, inspectionFailure);
                if (!process.isAlive()
                        && observedDescendants.stream().noneMatch(ProcessHandle::isAlive)) {
                    if (inspectionFailure != null) {
                        throw new AssertionError(
                                "process-tree inspection was incomplete during forced termination",
                                inspectionFailure);
                    }
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            }

            List<Long> survivors = new ArrayList<>();
            if (process.isAlive()) survivors.add(process.pid());
            observedDescendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::pid)
                    .forEach(survivors::add);
            AssertionError failure = new AssertionError(
                    "process tree did not terminate after forced destruction; survivors=" + survivors);
            if (inspectionFailure != null) failure.addSuppressed(inspectionFailure);
            throw failure;
        } finally {
            if (restoreInterrupt || Thread.interrupted()) Thread.currentThread().interrupt();
        }
    }

    private RuntimeException destroyObservedTree(
            Process process,
            ProcessHandle root,
            Set<ProcessHandle> observedDescendants,
            boolean forcibly,
            RuntimeException firstFailure) {
        List<ProcessHandle> descendants;
        try {
            descendants = observedDescendants.stream()
                    .filter(ProcessHandle::isAlive)
                    .sorted(Comparator.comparingInt(
                            (ProcessHandle handle) -> processDepth(handle, root)).reversed())
                    .toList();
        } catch (RuntimeException failure) {
            if (firstFailure == null) firstFailure = failure;
            descendants = List.copyOf(observedDescendants);
        }
        for (ProcessHandle handle : descendants) {
            try {
                if (!handle.isAlive()) continue;
                if (forcibly) handle.destroyForcibly(); else handle.destroy();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (process.isAlive()) {
            try {
                if (forcibly) process.destroyForcibly(); else process.destroy();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        return firstFailure;
    }

    private int processDepth(ProcessHandle handle, ProcessHandle root) {
        int depth = 1;
        ProcessHandle current = handle;
        while (depth < 128) {
            var parent = current.parent();
            if (parent.isEmpty() || parent.get().pid() == root.pid()) return depth;
            current = parent.get();
            depth++;
        }
        return depth;
    }

    private void deleteCommandOutputs(Path... paths) throws IOException {
        IOException aggregate = null;
        for (Path path : paths) {
            try {
                deleteEventually(path);
            } catch (IOException failure) {
                if (aggregate == null) aggregate = failure; else aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private void deleteEventually(Path path) throws IOException {
        IOException lastFailure = null;
        boolean restoreInterrupt = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATION_TIMEOUT_SECONDS);
        try {
            do {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (IOException failure) {
                    lastFailure = failure;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                if (Thread.interrupted()) restoreInterrupt = true;
            } while (System.nanoTime() < deadline);
        } finally {
            if (restoreInterrupt || Thread.interrupted()) Thread.currentThread().interrupt();
        }
        throw lastFailure;
    }

    private Map<String, Object> sourceSnapshot(Path projectDir) throws Exception {
        String head = new String(
                runCommandBytes(projectDir, "git", "rev-parse", "--verify", "HEAD"),
                StandardCharsets.US_ASCII
        ).trim().toLowerCase(Locale.ROOT);
        byte[] staged = runCommandBytes(projectDir, "git", "diff", "--cached", "--binary", "--full-index",
                "--no-ext-diff", "--no-textconv", "--no-renames", "HEAD", "--");
        byte[] untracked = runCommandBytes(projectDir, "git", "ls-files", "--others", "--exclude-standard", "-z");
        if (staged.length != 0 || untracked.length != 0) {
            throw new IOException("quality gate fixture source must not contain staged or untracked files");
        }
        byte[] diff = runCommandBytes(projectDir, "git", "diff", "--binary", "--full-index",
                "--no-ext-diff", "--no-textconv", "--no-renames", "HEAD", "--");
        byte[] changedNames = runCommandBytes(projectDir, "git", "diff", "--name-only", "-z",
                "--no-ext-diff", "--no-renames", "HEAD", "--");
        List<byte[]> trackedPaths = splitNulPaths(
                runCommandBytes(projectDir, "git", "ls-files", "-z", "--")
        );
        trackedPaths.sort(this::compareUnsignedBytes);
        MessageDigest snapshotDigest = MessageDigest.getInstance("SHA-256");
        updateDigestField(snapshotDigest, "talos-qodana-current-source-v2".getBytes(StandardCharsets.US_ASCII));
        updateDigestField(snapshotDigest, head.getBytes(StandardCharsets.US_ASCII));
        snapshotDigest.update(ByteBuffer.allocate(8).putLong(trackedPaths.size()).array());
        long trackedBytes = 0L;
        for (byte[] rawPath : trackedPaths) {
            byte[] contents = Files.readAllBytes(projectDir.resolve(new String(rawPath, StandardCharsets.UTF_8)));
            updateDigestField(snapshotDigest, rawPath);
            updateDigestField(snapshotDigest, contents);
            trackedBytes = Math.addExact(trackedBytes, contents.length);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("algorithm", "talos-qodana-current-source-v2");
        snapshot.put("gitHead", head);
        snapshot.put("trackedFiles", trackedPaths.size());
        snapshot.put("trackedBytes", trackedBytes);
        snapshot.put("changedTrackedFiles", splitNulPaths(changedNames).size());
        snapshot.put("diffBytes", diff.length);
        snapshot.put("diffSha256", sha256(diff));
        snapshot.put("snapshotSha256", hex(snapshotDigest.digest()));
        return snapshot;
    }

    private List<byte[]> splitNulPaths(byte[] raw) throws IOException {
        List<byte[]> paths = new ArrayList<>();
        if (raw.length == 0) return paths;
        if (raw[raw.length - 1] != 0) throw new IOException("Git path list is not NUL-terminated");
        int start = 0;
        for (int index = 0; index < raw.length; index++) {
            if (raw[index] == 0) {
                paths.add(java.util.Arrays.copyOfRange(raw, start, index));
                start = index + 1;
            }
        }
        return paths;
    }

    private int compareUnsignedBytes(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private void updateDigestField(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(8).putLong(value.length).array());
        digest.update(value);
    }

    private String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append("%02x".formatted(value & 0xff));
        return result.toString();
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
