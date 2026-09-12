package dev.talos.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Qodana summary task")
class QodanaSummaryTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private static final long TERMINATION_TIMEOUT_SECONDS = 5;

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
                List.of("metaInformation.json", "result-allProblems.json", "qodana.sarif.json",
                        "qodana_directories_inspection_groups.csv"),
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
                  "linterVersion": "261.25881",
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
        assertIterableEquals(List.of("qodana.sarif.json", "qodana_directories_inspection_groups.csv"),
                castList(requiredArtifacts.get("missing")));
        assertEquals(Boolean.TRUE, filePresence.get("metaInformation"));
        assertEquals(Boolean.TRUE, filePresence.get("allProblems"));
        assertEquals(Boolean.FALSE, filePresence.get("sarif"));
    }

    @Test
    @DisplayName("writeQodanaSummary accepts missing native VCS metadata when the exact source snapshot matches")
    void acceptsMissingNativeVcsWhenExactSourceSnapshotMatches() throws Exception {
        Path projectDir = createBuildFixture();
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));

        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
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
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "results": [
                        { "level": "warning", "properties": { "qodanaSeverity": "HIGH" } },
                        { "level": "note", "properties": { "qodanaSeverity": "MODERATE" } }
                      ]
                    }
                  ]
                }
                """);
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> requiredArtifacts = castMap(summary.get("requiredArtifacts"));
        Map<String, Object> provenance = castMap(summary.get("provenance"));
        Map<String, Object> sourceSnapshot = castMap(summary.get("sourceSnapshot"));
        Map<String, Object> scanScope = castMap(summary.get("scanScope"));

        assertEquals("qodana-results-match-current-candidate", summary.get("summaryStatus"));
        assertEquals("all-required-artifacts-present", requiredArtifacts.get("status"));
        assertEquals("qodana-revision-unavailable", provenance.get("revisionStatus"));
        assertEquals("qodana-branch-unavailable", provenance.get("branchStatus"));
        assertEquals("matches-current-source-snapshot", sourceSnapshot.get("status"));
        assertEquals("required-source-roots-observed", scanScope.get("status"));
        assertEquals("no-inspection-activity-observed", scanScope.get("unitTest"));
        assertEquals(1, summary.get("highIssues"));
        assertEquals("unknown-no-baseline-state", summary.get("newIssuesStatus"));
    }

    @Test
    @DisplayName("mixed missing SARIF baseline state remains unknown in either result order")
    void keepsMissingBaselineStateStickyAcrossResultOrder() throws Exception {
        assertMixedBaselineStateRemainsUnknown("baseline-missing-first", true);
        assertMixedBaselineStateRemainsUnknown("baseline-missing-last", false);
    }

    @Test
    @DisplayName("writeQodanaSummary reports matching candidate identity when provenance aligns with current branch and revision")
    void reportsMatchingProvenanceWhenQodanaAgreesWithCurrentGit() throws Exception {
        Path projectDir = createBuildFixture();
        String currentRevision = runCommand(projectDir, "git", "rev-parse", "HEAD");
        String currentBranch = runCommand(projectDir, "git", "rev-parse", "--abbrev-ref", "HEAD");

        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));
        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
                  "total": 1,
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
                { "listProblem": [ { "severity": "HIGH" } ] }
                """);
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "results": [
                        {
                          "level": "warning",
                          "baselineState": "unchanged",
                          "properties": { "qodanaSeverity": "HIGH" }
                        }
                      ]
                    }
                  ]
                }
                """);
        writeValidRunBinding(projectDir, true);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> provenance = castMap(summary.get("provenance"));
        Map<String, Object> scanScope = castMap(summary.get("scanScope"));

        assertEquals("qodana-results-match-current-candidate", summary.get("summaryStatus"));
        assertEquals("matches-current-revision", provenance.get("revisionStatus"));
        assertEquals("matches-current-branch", provenance.get("branchStatus"));
        assertEquals("inspection-activity-observed", scanScope.get("unitTest"));
        assertEquals(0, summary.get("newIssues"));
        assertEquals("derived-from-sarif-baseline-state", summary.get("newIssuesStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary binds an unstaged tracked-file change and records its file count")
    void bindsDirtyTrackedSourceAndRecordsChangedFileCount() throws Exception {
        Path projectDir = createBuildFixture();
        Files.writeString(projectDir.resolve("gradle.properties"), "\nqodanaFixtureMarker=true\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        writeCompleteQodanaResults(projectDir, null, null);
        SourceSnapshot bound = writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Path summaryFile = projectDir.resolve("build/reports/talos/qodana-summary.json");
        byte[] firstSummary = Files.readAllBytes(summaryFile);
        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        Map<String, Object> current = castMap(snapshot.get("current"));
        assertEquals("qodana-results-match-current-candidate", summary.get("summaryStatus"));
        assertEquals("matches-current-source-snapshot", snapshot.get("status"));
        assertEquals(1, current.get("changedTrackedFiles"));
        assertEquals(bound.snapshotSha256(), current.get("snapshotSha256"));

        runWriteQodanaSummary(projectDir);
        assertArrayEquals(firstSummary, Files.readAllBytes(summaryFile),
                "identical HEAD and raw diff must produce byte-identical summary framing");
    }

    @Test
    @DisplayName("writeQodanaSummary rejects a source change made after the Qodana run binding")
    void rejectsSourceChangeAfterRunBinding() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        SourceSnapshot bound = writeValidRunBinding(projectDir, false);
        Files.writeString(projectDir.resolve("gradle.properties"), "\nchangedAfterQodana=true\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        Map<String, Object> current = castMap(snapshot.get("current"));
        assertEquals("stale-qodana-source-snapshot", summary.get("summaryStatus"));
        assertEquals("source-snapshot-mismatch", snapshot.get("status"));
        assertNotEquals(bound.snapshotSha256(), current.get("snapshotSha256"));
    }

    @Test
    @DisplayName("writeQodanaSummary detects raw line-ending drift hidden by Git text normalization")
    void detectsRawByteDriftHiddenByGitDiffNormalization() throws Exception {
        Path projectDir = createBuildFixture();
        runCommand(projectDir, "git", "config", "core.autocrlf", "true");
        Path byteFixture = projectDir.resolve("byte-fixture.txt");
        writeUtf8(byteFixture, "alpha\nbeta\n");
        runCommand(projectDir, "git", "add", "byte-fixture.txt");
        runCommand(projectDir, "git", "commit", "-q", "-m", "byte fixture");
        writeCompleteQodanaResults(projectDir, null, null);
        SourceSnapshot bound = writeValidRunBinding(projectDir, false);

        writeUtf8(byteFixture, "alpha\r\nbeta\r\n");
        byte[] normalizedDiff = runCommandBytes(projectDir, "git", "diff", "--binary", "--full-index",
                "--no-ext-diff", "--no-textconv", "--no-renames", "HEAD", "--", "byte-fixture.txt");
        assertEquals(0, normalizedDiff.length, "fixture must prove Git normalized away the byte-only drift");

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        Map<String, Object> current = castMap(snapshot.get("current"));
        assertEquals("stale-qodana-source-snapshot", summary.get("summaryStatus"));
        assertEquals("source-snapshot-mismatch", snapshot.get("status"));
        assertNotEquals(bound.snapshotSha256(), current.get("snapshotSha256"));
        assertEquals(0, current.get("changedTrackedFiles"),
                "raw-byte binding, not Git's normalized diff, must detect this drift");
    }

    @Test
    @DisplayName("writeQodanaSummary rejects explicit native VCS metadata that conflicts with the current source")
    void rejectsExplicitNativeVcsMismatch() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(
                projectDir,
                "0000000000000000000000000000000000000000",
                "definitely-not-the-current-branch"
        );
        writeValidRunBinding(projectDir, true);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> provenance = castMap(summary.get("provenance"));
        assertEquals("stale-qodana-provenance", summary.get("summaryStatus"));
        assertEquals("revision-mismatch", provenance.get("revisionStatus"));
        assertEquals("branch-mismatch", provenance.get("branchStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects stale or conflicting analysis-engine identities")
    void rejectsStaleOrConflictingAnalysisEngineIdentity() throws Exception {
        Path staleProject = createBuildFixture("stale-engine");
        writeCompleteQodanaResults(staleProject, null, null);
        Path staleMetadata = staleProject.resolve(".qodana/report/results/metaInformation.json");
        writeUtf8(
                staleMetadata,
                Files.readString(staleMetadata, StandardCharsets.UTF_8)
                        .replace("261.25881", "253.31821")
        );
        writeValidRunBinding(staleProject, false);
        runWriteQodanaSummary(staleProject);
        Map<String, Object> staleSummary = readSummary(staleProject);
        assertEquals("qodana-results-invalid", staleSummary.get("summaryStatus"));
        assertTrue(castMap(staleSummary.get("resultSchema")).get("diagnostic").toString()
                .contains("configured 2026.1 engine family"));

        Path conflictingProject = createBuildFixture("conflicting-engine");
        writeCompleteQodanaResults(conflictingProject, null, null);
        Path conflictingSarif = conflictingProject.resolve(".qodana/report/results/qodana.sarif.json");
        writeUtf8(
                conflictingSarif,
                Files.readString(conflictingSarif, StandardCharsets.UTF_8)
                        .replace("\"name\": \"QDJVM\"", "\"name\": \"OTHER\"")
        );
        writeValidRunBinding(conflictingProject, false);
        runWriteQodanaSummary(conflictingProject);
        Map<String, Object> conflictingSummary = readSummary(conflictingProject);
        assertEquals("qodana-results-invalid", conflictingSummary.get("summaryStatus"));
        assertTrue(castMap(conflictingSummary.get("resultSchema")).get("diagnostic").toString()
                .contains("driver identity"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects conflicting explicit VCS fields from every SARIF run")
    void rejectsConflictingExplicitVcsAcrossAllArtifactsAndRuns() throws Exception {
        Path projectDir = createBuildFixture();
        String currentRevision = runCommand(projectDir, "git", "rev-parse", "HEAD");
        String currentBranch = runCommand(projectDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
        writeCompleteQodanaResults(projectDir, currentRevision, currentBranch);
        writeUtf8(projectDir.resolve(".qodana/report/results/qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "versionControlProvenance": [
                        { "revisionId": "%s", "branch": "%s" }
                      ],
                      "results": [
                        {
                          "level": "warning",
                          "baselineState": "unchanged",
                          "properties": { "qodanaSeverity": "HIGH" }
                        }
                      ]
                    },
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "versionControlProvenance": [
                        {
                          "revisionId": "0000000000000000000000000000000000000000",
                          "branch": "conflicting-branch"
                        }
                      ],
                      "results": []
                    }
                  ]
                }
                """.formatted(currentRevision, currentBranch));
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> provenance = castMap(summary.get("provenance"));
        assertEquals("stale-qodana-provenance", summary.get("summaryStatus"));
        assertEquals("validated-consistent-results", castMap(summary.get("resultSchema")).get("status"));
        assertEquals("qodana-revision-conflict", provenance.get("revisionStatus"));
        assertEquals("qodana-branch-conflict", provenance.get("branchStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects complete Qodana results without a run binding")
    void rejectsMissingRunBinding() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        Files.delete(projectDir.resolve(".qodana/report/results/talos-run-binding.json"));

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        assertEquals("qodana-run-binding-missing", summary.get("summaryStatus"));
        assertEquals("qodana-run-binding-missing", snapshot.get("status"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects malformed run-binding JSON without trusting partial fields")
    void rejectsMalformedRunBinding() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        writeUtf8(projectDir.resolve(".qodana/report/results/talos-run-binding.json"), "{ not-json");

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        assertEquals("qodana-run-binding-invalid", summary.get("summaryStatus"));
        assertEquals("qodana-run-binding-invalid", snapshot.get("status"));
        assertEquals("run-binding-json-invalid", snapshot.get("diagnostic"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects string-typed numeric run-binding fields")
    void rejectsStringTypedRunBindingNumbers() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        Path bindingFile = projectDir.resolve(".qodana/report/results/talos-run-binding.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> binding = JSON.readValue(bindingFile.toFile(), Map.class);
        binding.put("schemaVersion", "1");
        JSON.writerWithDefaultPrettyPrinter().writeValue(bindingFile.toFile(), binding);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> snapshot = castMap(summary.get("sourceSnapshot"));
        assertEquals("qodana-run-binding-invalid", summary.get("summaryStatus"));
        assertEquals("qodana-run-binding-invalid", snapshot.get("status"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects malformed UTF-8 even when telemetry tuples are rebound")
    void rejectsMalformedUtf8ScopeTelemetry() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        Path telemetry = projectDir.resolve(".qodana/results/log/qodana_directories_inspection_groups.csv");
        byte[] malformed = java.util.Arrays.copyOf(Files.readAllBytes(telemetry),
                Math.toIntExact(Files.size(telemetry)) + 1);
        malformed[malformed.length - 1] = (byte) 0x80;
        Files.write(telemetry, malformed);

        Path bindingFile = projectDir.resolve(".qodana/report/results/talos-run-binding.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> binding = JSON.readValue(bindingFile.toFile(), Map.class);
        Map<String, Object> observedScope = castMap(binding.get("observedScope"));
        observedScope.put("telemetryBytes", malformed.length);
        observedScope.put("telemetrySha256", sha256(malformed));
        updateArtifactManifestTuple(binding, "results/log/qodana_directories_inspection_groups.csv", malformed);
        JSON.writerWithDefaultPrettyPrinter().writeValue(bindingFile.toFile(), binding);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> scope = castMap(summary.get("scanScope"));
        assertEquals("qodana-scope-incomplete", summary.get("summaryStatus"));
        assertTrue(scope.get("diagnostic").toString().contains("strict UTF-8"), summary.toString());
    }

    @Test
    @DisplayName("writeQodanaSummary rejects scope telemetry changed after the run binding")
    void rejectsChangedScopeTelemetry() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        Files.writeString(
                projectDir.resolve(".qodana/results/log/qodana_directories_inspection_groups.csv"),
                "tampered;row\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND
        );

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> artifactBinding = castMap(summary.get("artifactBinding"));
        assertEquals("stale-qodana-artifacts", summary.get("summaryStatus"));
        assertEquals("bound-artifact-mismatch", artifactBinding.get("status"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects mutation of each exact-byte bound Qodana artifact")
    void rejectsMutationOfEveryBoundArtifact() throws Exception {
        List<String> paths = List.of(
                "report/results/metaInformation.json",
                "report/results/result-allProblems.json",
                "report/results/qodana.sarif.json",
                "results/log/qodana_directories_inspection_groups.csv"
        );
        for (int index = 0; index < paths.size(); index++) {
            Path projectDir = createBuildFixture("artifact-mutation-" + index);
            writeCompleteQodanaResults(projectDir, null, null);
            writeValidRunBinding(projectDir, false);
            Files.write(
                    projectDir.resolve(".qodana").resolve(paths.get(index)),
                    new byte[]{' '},
                    java.nio.file.StandardOpenOption.APPEND
            );

            runWriteQodanaSummary(projectDir);

            Map<String, Object> summary = readSummary(projectDir);
            Map<String, Object> artifactBinding = castMap(summary.get("artifactBinding"));
            assertEquals("stale-qodana-artifacts", summary.get("summaryStatus"), paths.get(index));
            assertEquals("bound-artifact-mismatch", artifactBinding.get("status"), paths.get(index));
        }
    }

    @Test
    @DisplayName("writeQodanaSummary rejects unknown severities without reporting zero critical issues")
    void rejectsUnknownSeverityWithoutFalsePass() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        Path problems = projectDir.resolve(".qodana/report/results/result-allProblems.json");
        writeUtf8(problems, "{ \"listProblem\": [ { \"severity\": \"UNKNOWN\" } ] }\n");
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("qodana-results-invalid", summary.get("summaryStatus"));
        assertEquals(null, summary.get("criticalIssues"));
        assertEquals("unknown-invalid-or-unauthenticated-results", summary.get("criticalIssuesStatus"));
    }

    @Test
    @DisplayName("writeQodanaSummary rejects non-object items and inconsistent result counts")
    void rejectsMalformedItemsAndInconsistentCounts() throws Exception {
        Path malformedProject = createBuildFixture("non-object-item");
        writeCompleteQodanaResults(malformedProject, null, null);
        writeUtf8(
                malformedProject.resolve(".qodana/report/results/result-allProblems.json"),
                "{ \"listProblem\": [ 7 ] }\n"
        );
        writeValidRunBinding(malformedProject, false);
        runWriteQodanaSummary(malformedProject);
        assertEquals("qodana-results-invalid", readSummary(malformedProject).get("summaryStatus"));

        Path countProject = createBuildFixture("count-mismatch");
        writeCompleteQodanaResults(countProject, null, null);
        Path metadata = countProject.resolve(".qodana/report/results/metaInformation.json");
        writeUtf8(metadata, "{ \"linter\": \"QDJVM\", \"linterVersion\": \"261.25881\", \"total\": 2, \"attributes\": {} }\n");
        writeValidRunBinding(countProject, false);
        runWriteQodanaSummary(countProject);
        assertEquals("qodana-results-invalid", readSummary(countProject).get("summaryStatus"));
    }

    @Test
    @DisplayName("strict JSON validation rejects escaped-equivalent duplicate keys recursively")
    void rejectsEscapedEquivalentDuplicateKeys() throws Exception {
        Path bindingProject = createBuildFixture("binding-duplicate");
        writeCompleteQodanaResults(bindingProject, null, null);
        writeValidRunBinding(bindingProject, false);
        Path bindingFile = bindingProject.resolve(".qodana/report/results/talos-run-binding.json");
        String originalBinding = Files.readString(bindingFile, StandardCharsets.UTF_8);
        String algorithm = "\"algorithm\" : \"talos-qodana-current-source-v2\",";
        String escapedAlgorithm = "\"\\" + "u0061lgorithm\" : \"talos-qodana-current-source-v2\",";
        String binding = originalBinding.replaceFirst(java.util.regex.Pattern.quote(algorithm),
                java.util.regex.Matcher.quoteReplacement(algorithm + System.lineSeparator() + escapedAlgorithm));
        assertNotEquals(originalBinding, binding, "fixture must inject a nested escaped-equivalent key");
        writeUtf8(bindingFile, binding);
        runWriteQodanaSummary(bindingProject);
        assertEquals("qodana-run-binding-invalid", readSummary(bindingProject).get("summaryStatus"));

        Path resultProject = createBuildFixture("result-duplicate");
        Path resultsDir = Files.createDirectories(resultProject.resolve(".qodana/report/results"));
        writeUtf8(resultsDir.resolve("metaInformation.json"),
                "{ \"linter\": \"QDJVM\", \"linterVersion\": \"261.25881\", \"total\": 0, \"attributes\": {} }\n");
        writeUtf8(resultsDir.resolve("result-allProblems.json"), "{ \"listProblem\": [] }\n");
        String escapedResults = "\\" + "u0072esults";
        writeUtf8(resultsDir.resolve("qodana.sarif.json"),
                "{ \"runs\": [ { \"tool\": { \"driver\": { \"name\": \"QDJVM\", \"version\": \"261.25881\" } }, "
                        + "\"results\": [], \"" + escapedResults + "\": [] } ] }\n");
        writeValidRunBinding(resultProject, false);
        runWriteQodanaSummary(resultProject);
        assertEquals("qodana-results-invalid", readSummary(resultProject).get("summaryStatus"));
    }

    @Test
    @DisplayName("five-column scope telemetry requires positive performed counts for required roots")
    void requiresPositivePerformedCountForObservedRoot() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false, 0, 11);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> scope = castMap(summary.get("scanScope"));
        assertEquals("qodana-scope-incomplete", summary.get("summaryStatus"));
        assertEquals("required-source-roots-not-observed", scope.get("status"));
        assertEquals("no-inspection-activity-observed", scope.get("main"));
    }

    @Test
    @DisplayName("five-column scope telemetry rejects traversal-shaped directory rows")
    void rejectsNonCanonicalScopeDirectory() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeValidRunBinding(projectDir, false);
        Path telemetry = projectDir.resolve(".qodana/results/log/qodana_directories_inspection_groups.csv");
        String root = projectDir.toFile().getCanonicalFile().toPath().toAbsolutePath().normalize()
                .toString().replace('\\', '/');
        String changed = Files.readString(telemetry, StandardCharsets.UTF_8)
                .replace(root + "/src/main;", root + "/src/main/../outside;");
        writeUtf8(telemetry, changed);
        byte[] telemetryBytes = Files.readAllBytes(telemetry);
        Path bindingFile = projectDir.resolve(".qodana/report/results/talos-run-binding.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> binding = JSON.readValue(bindingFile.toFile(), Map.class);
        updateArtifactManifestTuple(
                binding,
                "results/log/qodana_directories_inspection_groups.csv",
                telemetryBytes
        );
        JSON.writerWithDefaultPrettyPrinter().writeValue(bindingFile.toFile(), binding);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> scope = castMap(summary.get("scanScope"));
        assertEquals("qodana-scope-incomplete", summary.get("summaryStatus"));
        assertTrue(scope.get("diagnostic").toString().contains("canonical absolute path"), summary.toString());
    }

    @Test
    @DisplayName("strict result validation rejects unknown SARIF baseline states")
    void rejectsUnknownSarifBaselineState() throws Exception {
        Path projectDir = createBuildFixture();
        writeCompleteQodanaResults(projectDir, null, null);
        writeUtf8(
                projectDir.resolve(".qodana/report/results/qodana.sarif.json"),
                """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "results": [
                        {
                          "level": "warning",
                          "baselineState": "garbage",
                          "properties": { "qodanaSeverity": "HIGH" }
                        }
                      ]
                    }
                  ]
                }
                """
        );
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("qodana-results-invalid", summary.get("summaryStatus"));
        assertEquals(null, summary.get("criticalIssues"));
        assertEquals("unknown-invalid-or-unauthenticated-results", summary.get("criticalIssuesStatus"));
    }

    @Test
    @DisplayName("qodanaNativeFreshLocal rejects a linked output component before deleting outside content")
    void refusesLinkedOutputBeforeMutation() throws Exception {
        Path projectDir = createBuildFixture();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path sentinel = outside.resolve("sentinel.txt");
        writeUtf8(sentinel, "must survive\n");
        Path qodanaRoot = Files.createDirectories(projectDir.resolve(".qodana"));
        try {
            Files.createSymbolicLink(qodanaRoot.resolve("report"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symbolic-link creation is unavailable: " + e.getMessage());
            return;
        }

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qodanaNativeFreshLocal", "--stacktrace")
                .forwardOutput()
                .buildAndFail();

        assertTrue(result.getOutput().contains("symbolic link, junction, or reparse point"), result.getOutput());
        assertEquals("must survive\n", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("qodanaNativeFreshLocal rejects tracked files below cleanup targets before mutation")
    void refusesTrackedCleanupIntersectionBeforeMutation() throws Exception {
        Path projectDir = createBuildFixture();
        Path sentinel = projectDir.resolve(".qodana/report/sentinel.txt");
        writeUtf8(sentinel, "tracked sentinel must survive\n");
        runCommand(projectDir, "git", "add", "-f", "--", ".qodana/report/sentinel.txt");
        runCommand(projectDir, "git", "commit", "-q", "-m", "tracked qodana sentinel");

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qodanaNativeFreshLocal", "--stacktrace")
                .forwardOutput()
                .buildAndFail();

        assertTrue(result.getOutput().contains("cleanup target intersects tracked Git path"), result.getOutput());
        assertEquals("tracked sentinel must survive\n", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(projectDir.resolve(".qodana/results")));
    }

    @Test
    @DisplayName("qodanaNativeFreshLocal rejects a Windows junction before deleting its target")
    void refusesWindowsJunctionBeforeMutation() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "Windows junction fixture"
        );
        Path projectDir = createBuildFixture();
        Path outside = Files.createDirectories(tempDir.resolve("junction-outside"));
        Path sentinel = outside.resolve("sentinel.txt");
        writeUtf8(sentinel, "junction target must survive\n");
        Path qodanaRoot = Files.createDirectories(projectDir.resolve(".qodana"));
        Path junction = qodanaRoot.resolve("report");
        String mklink = "mklink /J \"" + junction + "\" \"" + outside + "\"";
        try {
            runCommandBytes(projectDir, "cmd.exe", "/d", "/c", mklink);
        } catch (IOException e) {
            Assumptions.assumeTrue(false, "junction creation is unavailable: " + e.getMessage());
            return;
        }

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qodanaNativeFreshLocal", "--stacktrace")
                .forwardOutput()
                .buildAndFail();

        assertTrue(result.getOutput().contains("symbolic link, junction, or reparse point"), result.getOutput());
        assertEquals("junction target must survive\n", Files.readString(sentinel, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("path-safety rejection helper rejects no-op and unrelated failures without a link fixture")
    void verifiesPathSafetyRejectionHelperNegativeControls() throws Exception {
        Path projectDir = createBuildFixture();
        appendPathSafetyProbeTask(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qodanaPathSafetyHelperContract", "--stacktrace")
                .forwardOutput()
                .build();

        assertTrue(result.getOutput().contains("QODANA_PATH_SAFETY_HELPER_CONTRACT_PASS"));
    }

    @Test
    @DisplayName("portable no-follow validation covers ordinary, missing, escaped, linked, and non-directory paths")
    void exercisesPortableNoFollowPathSafetyContract() throws Exception {
        Path projectDir = createBuildFixture();
        Path cases = Files.createDirectories(projectDir.resolve("path-cases"));
        Files.createDirectories(cases.resolve("ordinary/child"));
        Path boundedTree = Files.createDirectories(cases.resolve("bounded-tree"));
        writeUtf8(boundedTree.resolve("one.txt"), "one\n");
        writeUtf8(boundedTree.resolve("two.txt"), "two\n");
        writeUtf8(cases.resolve("ordinary/file-parent"), "not a directory\n");
        Path outside = Files.createDirectories(tempDir.resolve("outside-path-cases"));
        writeUtf8(outside.resolve("sentinel.txt"), "outside\n");
        try {
            Files.createSymbolicLink(cases.resolve("linked-ancestor"), outside);
            Files.createSymbolicLink(cases.resolve("linked-target"), outside.resolve("sentinel.txt"));
            Path stateTree = Files.createDirectories(cases.resolve("state-tree"));
            Files.createSymbolicLink(stateTree.resolve("linked-descendant"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "symbolic-link creation is unavailable: " + e.getMessage());
            return;
        }
        appendPathSafetyProbeTask(projectDir);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("qodanaPathSafetyContract", "--stacktrace")
                .forwardOutput()
                .build();

        assertTrue(result.getOutput().contains("QODANA_PATH_SAFETY_CONTRACT_PASS"));
        assertEquals("outside\n", Files.readString(outside.resolve("sentinel.txt"), StandardCharsets.UTF_8));
        assertEquals("one\n", Files.readString(boundedTree.resolve("one.txt"), StandardCharsets.UTF_8));
        assertEquals("two\n", Files.readString(boundedTree.resolve("two.txt"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("writeQodanaSummary writes a fail-soft payload when the SARIF file is malformed")
    void writesFailSoftPayloadWhenSarifIsMalformed() throws Exception {
        Path projectDir = createBuildFixture();
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));

        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                { "linter": "QDJVM", "linterVersion": "261.25881", "total": 0, "attributes": {} }
                """);
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                { "listProblem": [] }
                """);
        // Deliberately malformed JSON - must not take the packet down.
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), "{ this is not valid json");
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("qodana-results-invalid", summary.get("summaryStatus"));
        Map<String, Object> resultSchema = castMap(summary.get("resultSchema"));
        assertEquals("invalid-or-unauthenticated-results", resultSchema.get("status"));
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
        Path stdoutFile = Files.createTempFile(tempDir, "qodana-command-out-", ".bin");
        Path stderrFile = Files.createTempFile(tempDir, "qodana-command-err-", ".bin");
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

            List<Long> survivors = new java.util.ArrayList<>();
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

    private void appendPathSafetyProbeTask(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("build.gradle.kts"), """

                fun requireRejected(fragment: String, action: () -> Unit) {
                    try {
                        action()
                    } catch (failure: org.gradle.api.GradleException) {
                        if (failure.message?.contains(fragment) != true) throw failure
                        return
                    }
                    throw org.gradle.api.GradleException("expected path-safety rejection: $fragment")
                }

                tasks.register("qodanaPathSafetyHelperContract") {
                    doLast {
                        val noOpFailure = runCatching { requireRejected("expected fixture rejection") {} }
                            .exceptionOrNull()
                        check(noOpFailure is org.gradle.api.GradleException &&
                            noOpFailure.message == "expected path-safety rejection: expected fixture rejection") {
                            "a no-op must fail the rejection helper"
                        }
                        val unrelated = org.gradle.api.GradleException("unrelated fixture failure")
                        check(runCatching {
                            requireRejected("expected fixture rejection") { throw unrelated }
                        }.exceptionOrNull() === unrelated) {
                            "the helper must propagate a rejection with an unrelated message"
                        }
                        val wrongType = IllegalStateException("expected fixture rejection")
                        check(runCatching {
                            requireRejected("expected fixture rejection") { throw wrongType }
                        }.exceptionOrNull() === wrongType) {
                            "the helper must propagate an unrelated exception type"
                        }
                        requireRejected("expected fixture rejection") {
                            throw org.gradle.api.GradleException("expected fixture rejection: denied")
                        }
                        logger.lifecycle("QODANA_PATH_SAFETY_HELPER_CONTRACT_PASS")
                    }
                }

                tasks.register("qodanaPathSafetyContract") {
                    dependsOn("qodanaPathSafetyHelperContract")
                    doLast {
                        val base = file("path-cases").toPath().toAbsolutePath().normalize()
                        requireNoFollowOrdinaryComponents(base, base.resolve("ordinary/child"), "ordinary")
                        requireNoFollowOrdinaryTree(base.resolve("ordinary"), "ordinary tree")
                        requireNoFollowOrdinaryComponents(base, base.resolve("missing/child"), "missing")
                        requireRejected("must remain below") {
                            requireNoFollowOrdinaryComponents(base, file("../outside-path-cases").toPath(), "escape")
                        }
                        requireRejected("must not traverse a symbolic link, junction, or reparse point") {
                            requireNoFollowOrdinaryComponents(base, base.resolve("linked-ancestor/child"), "linked ancestor")
                        }
                        requireRejected("must not traverse a symbolic link, junction, or reparse point") {
                            requireNoFollowOrdinaryComponents(base, base.resolve("linked-target"), "linked target")
                        }
                        requireRejected("contains a symbolic link, junction, or reparse point") {
                            requireNoFollowOrdinaryTree(base.resolve("state-tree"), "state tree")
                        }
                        requireRejected("has a non-directory ancestor") {
                            requireNoFollowOrdinaryComponents(base, base.resolve("ordinary/file-parent/child"), "creation parent")
                        }
                        requireRejected("exceeds the bounded cleanup entry limit") {
                            deleteNoFollowOrdinaryTree(base, base.resolve("bounded-tree"), "bounded tree", 2)
                        }
                        logger.lifecycle("QODANA_PATH_SAFETY_CONTRACT_PASS")
                    }
                }
                """, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    private SourceSnapshot writeValidRunBinding(Path projectDir, boolean unitObserved) throws Exception {
        return writeValidRunBinding(projectDir, unitObserved, 17, 11);
    }

    private SourceSnapshot writeValidRunBinding(
            Path projectDir,
            boolean unitObserved,
            long mainPerformed,
            long e2ePerformed) throws Exception {
        Path telemetry = projectDir.resolve(".qodana/results/log/qodana_directories_inspection_groups.csv");
        String root = projectDir.toFile().getCanonicalFile().toPath().toAbsolutePath().normalize()
                .toString().replace('\\', '/');
        String telemetryText = "Directory;Absolute Time spent on Inspection Group 'all', s;"
                + "Percent of Time spent on Inspection Group 'all';"
                + "Inspections Problem Count in Group 'all';"
                + "Times Inspection from Group 'all' was Performed\n"
                + root + "/src/main;1.25;50.00%;1;" + mainPerformed + "\n"
                + root + "/src/test;0;0%;0;" + (unitObserved ? "3" : "0") + "\n"
                + root + "/src/e2eTest;1.25;50.00%;1;" + e2ePerformed + "\n";
        writeUtf8(telemetry, telemetryText);

        SourceSnapshot snapshot = sourceSnapshot(projectDir);
        Map<String, Object> observedScope = new LinkedHashMap<>();
        observedScope.put("telemetryStatus", "telemetry-present");
        observedScope.put("telemetryBytes", Files.size(telemetry));
        observedScope.put("telemetrySha256", sha256(Files.readAllBytes(telemetry)));
        observedScope.put("main", mainPerformed > 0
                ? "inspection-activity-observed"
                : "no-inspection-activity-observed");
        observedScope.put("unitTest", unitObserved
                ? "inspection-activity-observed"
                : "no-inspection-activity-observed");
        observedScope.put("e2eTest", e2ePerformed > 0
                ? "inspection-activity-observed"
                : "no-inspection-activity-observed");

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("schemaVersion", 1);
        binding.put("sourceSnapshotStatus", "stable");
        binding.put("sourceSnapshotBefore", snapshot.asMap());
        binding.put("sourceSnapshotAfter", snapshot.asMap());
        binding.put("artifactManifest", artifactManifest(projectDir));
        binding.put("observedScope", observedScope);
        Path bindingFile = projectDir.resolve(".qodana/report/results/talos-run-binding.json");
        Files.createDirectories(bindingFile.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(bindingFile.toFile(), binding);
        return snapshot;
    }

    private SourceSnapshot sourceSnapshot(Path projectDir) throws Exception {
        String head = new String(
                runCommandBytes(projectDir, "git", "rev-parse", "--verify", "HEAD"),
                StandardCharsets.US_ASCII
        ).trim().toLowerCase(Locale.ROOT);
        byte[] staged = runCommandBytes(projectDir, "git", "diff", "--cached", "--binary", "--full-index",
                "--no-ext-diff", "--no-textconv", "--no-renames", "HEAD", "--");
        byte[] untracked = runCommandBytes(projectDir, "git", "ls-files", "--others", "--exclude-standard", "-z");
        if (staged.length != 0 || untracked.length != 0) {
            throw new IOException("fixture source snapshot must not contain staged or untracked files");
        }
        byte[] diff = runCommandBytes(projectDir, "git", "diff", "--binary", "--full-index",
                "--no-ext-diff", "--no-textconv", "--no-renames", "HEAD", "--");
        byte[] names = runCommandBytes(projectDir, "git", "diff", "--name-only", "-z",
                "--no-ext-diff", "--no-renames", "HEAD", "--");
        int changedTrackedFiles = splitNulPaths(names).size();
        List<byte[]> trackedPaths = splitNulPaths(
                runCommandBytes(projectDir, "git", "ls-files", "-z", "--")
        );
        trackedPaths.sort(this::compareUnsignedBytes);
        List<byte[]> trackedContents = new java.util.ArrayList<>();
        long trackedBytes = 0;
        for (byte[] rawPath : trackedPaths) {
            Path path = projectDir.resolve(new String(rawPath, StandardCharsets.UTF_8)).normalize();
            byte[] contents = Files.readAllBytes(path);
            trackedContents.add(contents);
            trackedBytes = Math.addExact(trackedBytes, contents.length);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateDigestField(digest, "talos-qodana-current-source-v2".getBytes(StandardCharsets.US_ASCII));
        updateDigestField(digest, head.getBytes(StandardCharsets.US_ASCII));
        digest.update(ByteBuffer.allocate(8).putLong(trackedPaths.size()).array());
        for (int index = 0; index < trackedPaths.size(); index++) {
            updateDigestField(digest, trackedPaths.get(index));
            updateDigestField(digest, trackedContents.get(index));
        }
        return new SourceSnapshot(
                "talos-qodana-current-source-v2",
                head,
                trackedPaths.size(),
                trackedBytes,
                changedTrackedFiles,
                diff.length,
                sha256(diff),
                hex(digest.digest())
        );
    }

    private List<byte[]> splitNulPaths(byte[] raw) throws IOException {
        List<byte[]> paths = new java.util.ArrayList<>();
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

    private void updateDigestField(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(8).putLong(value.length).array());
        digest.update(value);
    }

    private int compareUnsignedBytes(byte[] left, byte[] right) {
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private void assertMixedBaselineStateRemainsUnknown(String fixtureName, boolean missingFirst)
            throws Exception {
        Path projectDir = createBuildFixture(fixtureName);
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));
        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
                  "total": 2,
                  "attributes": {}
                }
                """);
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                {
                  "listProblem": [
                    { "severity": "HIGH" },
                    { "severity": "HIGH" }
                  ]
                }
                """);
        String missing = """
                { "level": "warning", "properties": { "qodanaSeverity": "HIGH" } }
                """.trim();
        String present = """
                {
                  "level": "warning",
                  "baselineState": "new",
                  "properties": { "qodanaSeverity": "HIGH" }
                }
                """.trim();
        String first = missingFirst ? missing : present;
        String second = missingFirst ? present : missing;
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "results": [
                        %s,
                        %s
                      ]
                    }
                  ]
                }
                """.formatted(first, second));
        writeValidRunBinding(projectDir, false);

        runWriteQodanaSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("qodana-results-match-current-candidate", summary.get("summaryStatus"));
        assertEquals(null, summary.get("newIssues"));
        assertEquals("unknown-no-baseline-state", summary.get("newIssuesStatus"));
    }

    private void writeCompleteQodanaResults(Path projectDir, String revision, String branch) throws IOException {
        Path resultsDir = Files.createDirectories(projectDir.resolve(".qodana/report/results"));
        String attributes = revision == null || branch == null
                ? "{}"
                : """
                  { "vcs": { "sarifIdea": { "revisionId": "%s", "branch": "%s" } } }
                  """.formatted(revision, branch).trim();
        writeUtf8(resultsDir.resolve("metaInformation.json"), """
                {
                  "linter": "QDJVM",
                  "linterVersion": "261.25881",
                  "total": 1,
                  "attributes": %s
                }
                """.formatted(attributes));
        writeUtf8(resultsDir.resolve("result-allProblems.json"), """
                { "listProblem": [ { "severity": "HIGH" } ] }
                """);
        writeUtf8(resultsDir.resolve("qodana.sarif.json"), """
                {
                  "runs": [
                    {
                      "tool": { "driver": { "name": "QDJVM", "version": "261.25881" } },
                      "results": [
                        {
                          "level": "warning",
                          "baselineState": "unchanged",
                          "properties": { "qodanaSeverity": "HIGH" }
                        }
                      ]
                    }
                  ]
                }
                """);
    }

    private List<Map<String, Object>> artifactManifest(Path projectDir) throws Exception {
        List<String> paths = List.of(
                "report/results/metaInformation.json",
                "report/results/result-allProblems.json",
                "report/results/qodana.sarif.json",
                "results/log/qodana_directories_inspection_groups.csv"
        );
        List<Map<String, Object>> manifest = new java.util.ArrayList<>();
        for (String relativePath : paths) {
            byte[] bytes = Files.readAllBytes(projectDir.resolve(".qodana").resolve(relativePath));
            Map<String, Object> tuple = new LinkedHashMap<>();
            tuple.put("path", relativePath);
            tuple.put("bytes", bytes.length);
            tuple.put("sha256", sha256(bytes));
            manifest.add(tuple);
        }
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private void updateArtifactManifestTuple(Map<String, Object> binding, String relativePath, byte[] bytes)
            throws Exception {
        List<Map<String, Object>> manifest = (List<Map<String, Object>>) binding.get("artifactManifest");
        Map<String, Object> tuple = manifest.stream()
                .filter(candidate -> relativePath.equals(candidate.get("path")))
                .findFirst()
                .orElseThrow();
        tuple.put("bytes", bytes.length);
        tuple.put("sha256", sha256(bytes));
    }

    private String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append("%02x".formatted(value & 0xff));
        return result.toString();
    }

    private record SourceSnapshot(
            String algorithm,
            String gitHead,
            int trackedFiles,
            long trackedBytes,
            int changedTrackedFiles,
            int diffBytes,
            String diffSha256,
            String snapshotSha256) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", algorithm);
            result.put("gitHead", gitHead);
            result.put("trackedFiles", trackedFiles);
            result.put("trackedBytes", trackedBytes);
            result.put("changedTrackedFiles", changedTrackedFiles);
            result.put("diffBytes", diffBytes);
            result.put("diffSha256", diffSha256);
            result.put("snapshotSha256", snapshotSha256);
            return result;
        }
    }

    private Path createBuildFixture() throws Exception {
        return createBuildFixture("fixture");
    }

    private Path createBuildFixture(String name) throws Exception {
        Path projectDir = tempDir.resolve(name);
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
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
