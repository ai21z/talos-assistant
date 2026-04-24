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

@DisplayName("E2E summary task")
class E2eSummaryTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeE2eSummary reports no results when the candidate E2E lane produced no XMLs")
    void reportsNoResultsWhenNoXmlExists() throws Exception {
        Path projectDir = createBuildFixture();
        Path scenariosDir = Files.createDirectories(projectDir.resolve("src/e2eTest/resources/scenarios"));
        Files.createDirectories(projectDir.resolve("build/test-results/candidateE2eTest"));
        writeUtf8(scenariosDir.resolve("01-read-only.json"), """
                {
                  "id": "01",
                  "name": "read-only workspace",
                  "v1Pack": true,
                  "claims": ["read-only-requests-remain-read-only"]
                }
                """);

        runWriteE2eSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> testExecution = castMap(summary.get("testExecution"));
        Map<String, Object> jsonScenarioCoverage = castMap(summary.get("jsonScenarioCoverage"));
        Map<String, Object> v1ScenarioPack = castMap(summary.get("v1ScenarioPack"));

        assertEquals("no-results", testExecution.get("status"));
        assertEquals(0, testExecution.get("executedTestCaseCount"));
        assertEquals("no-testcases-executed", jsonScenarioCoverage.get("resourceTraceabilityStatus"));
        assertEquals("suite-did-not-execute", jsonScenarioCoverage.get("traceabilityScopeStatus"));
        assertEquals(0, jsonScenarioCoverage.get("executedTestCaseCount"));
        assertEquals(0, jsonScenarioCoverage.get("untaggedExecutedTestCaseCount"));
        assertEquals(0, jsonScenarioCoverage.get("passedResourceCount"));
        assertIterableEquals(
                List.of("scenarios/01-read-only.json"),
                castList(jsonScenarioCoverage.get("unexecutedResources"))
        );
        assertEquals(1, v1ScenarioPack.get("resourceCount"));
        assertEquals(0, v1ScenarioPack.get("executedResourceCount"));
        assertEquals(0, v1ScenarioPack.get("passedResourceCount"));
        assertEquals("suite-did-not-execute", v1ScenarioPack.get("coverageStatus"));
        assertIterableEquals(
                List.of("read-only-requests-remain-read-only"),
                castList(v1ScenarioPack.get("claims"))
        );
        assertIterableEquals(
                List.of("read-only-requests-remain-read-only"),
                castList(v1ScenarioPack.get("unprovenClaims"))
        );
    }

    @Test
    @DisplayName("writeE2eSummary distinguishes tagged scenario-pack coverage from untagged harness cases")
    void reportsMixedTaggedAndUntaggedHarnessCases() throws Exception {
        Path projectDir = createBuildFixture();
        Path scenariosDir = Files.createDirectories(projectDir.resolve("src/e2eTest/resources/scenarios"));
        Path resultsDir = Files.createDirectories(projectDir.resolve("build/test-results/candidateE2eTest"));

        writeUtf8(scenariosDir.resolve("01-read-only.json"), """
                {
                  "id": "01",
                  "name": "read-only path",
                  "v1Pack": true,
                  "claims": ["read-only-requests-remain-read-only"]
                }
                """);
        writeUtf8(scenariosDir.resolve("02-edit.json"), """
                {
                  "id": "02",
                  "name": "edit path",
                  "v1Pack": true,
                  "claims": ["narrow-file-edit-mutates-only-requested-target"]
                }
                """);
        writeUtf8(resultsDir.resolve("TEST-dev.talos.harness.Mixed.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="mixed" tests="3" failures="0" errors="0" skipped="0">
                  <testcase classname="dev.talos.harness.JsonScenarioPackTest"
                            name="[json-scenario:scenarios/01-read-only.json] read-only path"
                            time="0.011" />
                  <testcase classname="dev.talos.harness.JsonScenarioPackTest"
                            name="[json-scenario:scenarios/02-edit.json] edit path"
                            time="0.012" />
                  <testcase classname="dev.talos.harness.ScenarioResourcesSmokeTest"
                            name="harnessReadOnlyFollowUpStopsCleanlyAfterScriptedTurn()"
                            time="0.004" />
                </testsuite>
                """);

        runWriteE2eSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> testExecution = castMap(summary.get("testExecution"));
        Map<String, Object> jsonScenarioCoverage = castMap(summary.get("jsonScenarioCoverage"));
        Map<String, Object> v1ScenarioPack = castMap(summary.get("v1ScenarioPack"));

        assertEquals("passed", testExecution.get("status"));
        assertEquals(3, testExecution.get("executedTestCaseCount"));
        assertEquals(2, jsonScenarioCoverage.get("executedTestCaseCount"));
        assertEquals(1, jsonScenarioCoverage.get("untaggedExecutedTestCaseCount"));
        assertEquals(2, jsonScenarioCoverage.get("executedResourceCount"));
        assertEquals(2, jsonScenarioCoverage.get("passedResourceCount"));
        assertEquals(2, jsonScenarioCoverage.get("resourceCount"));
        assertEquals("partially-traceable-executed-cases", jsonScenarioCoverage.get("resourceTraceabilityStatus"));
        assertEquals("suite-mixes-json-scenario-backed-and-non-json-harness-cases",
                jsonScenarioCoverage.get("traceabilityScopeStatus"));
        assertIterableEquals(
                List.of("scenarios/01-read-only.json", "scenarios/02-edit.json"),
                castList(jsonScenarioCoverage.get("executedResources"))
        );
        assertIterableEquals(List.of(), castList(jsonScenarioCoverage.get("unexecutedResources")));
        assertEquals(2, v1ScenarioPack.get("resourceCount"));
        assertEquals(2, v1ScenarioPack.get("executedResourceCount"));
        assertEquals(2, v1ScenarioPack.get("passedResourceCount"));
        assertEquals("all-v1-pack-resources-passed", v1ScenarioPack.get("coverageStatus"));
        assertIterableEquals(
                List.of("narrow-file-edit-mutates-only-requested-target", "read-only-requests-remain-read-only"),
                castList(v1ScenarioPack.get("claims"))
        );
        assertIterableEquals(
                List.of("narrow-file-edit-mutates-only-requested-target", "read-only-requests-remain-read-only"),
                castList(v1ScenarioPack.get("passedClaims"))
        );
        assertIterableEquals(List.of(), castList(v1ScenarioPack.get("unprovenClaims")));
    }

    @Test
    @DisplayName("writeE2eSummary separates executed resources from passed resources for V1 claim coverage")
    void distinguishesPassedResourcesFromExecutedResources() throws Exception {
        Path projectDir = createBuildFixture();
        Path scenariosDir = Files.createDirectories(projectDir.resolve("src/e2eTest/resources/scenarios"));
        Path resultsDir = Files.createDirectories(projectDir.resolve("build/test-results/candidateE2eTest"));

        writeUtf8(scenariosDir.resolve("01-pass.json"), """
                {
                  "id": "01",
                  "name": "passing path",
                  "v1Pack": true,
                  "claims": ["claim-pass"]
                }
                """);
        writeUtf8(scenariosDir.resolve("02-fail.json"), """
                {
                  "id": "02",
                  "name": "failing path",
                  "v1Pack": true,
                  "claims": ["claim-fail"]
                }
                """);
        writeUtf8(resultsDir.resolve("TEST-dev.talos.harness.MixedStatus.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="mixed-status" tests="2" failures="1" errors="0" skipped="0">
                  <testcase classname="dev.talos.harness.JsonScenarioPackTest"
                            name="[json-scenario:scenarios/01-pass.json] pass path"
                            time="0.011" />
                  <testcase classname="dev.talos.harness.JsonScenarioPackTest"
                            name="[json-scenario:scenarios/02-fail.json] fail path"
                            time="0.012">
                    <failure message="boom">boom</failure>
                  </testcase>
                </testsuite>
                """);

        runWriteE2eSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> jsonScenarioCoverage = castMap(summary.get("jsonScenarioCoverage"));
        Map<String, Object> v1ScenarioPack = castMap(summary.get("v1ScenarioPack"));

        assertEquals(2, jsonScenarioCoverage.get("executedResourceCount"));
        assertEquals(1, jsonScenarioCoverage.get("passedResourceCount"));
        assertIterableEquals(
                List.of("scenarios/01-pass.json"),
                castList(jsonScenarioCoverage.get("passedResources"))
        );
        assertIterableEquals(
                List.of("scenarios/02-fail.json"),
                castList(jsonScenarioCoverage.get("failedResources"))
        );
        assertEquals(2, v1ScenarioPack.get("executedResourceCount"));
        assertEquals(1, v1ScenarioPack.get("passedResourceCount"));
        assertEquals("partially-proven-v1-pack", v1ScenarioPack.get("coverageStatus"));
        assertIterableEquals(
                List.of("claim-pass"),
                castList(v1ScenarioPack.get("passedClaims"))
        );
        assertIterableEquals(
                List.of("claim-fail"),
                castList(v1ScenarioPack.get("unprovenClaims"))
        );
    }

    @Test
    @DisplayName("writeE2eSummary writes a fail-soft payload when JUnit XML is malformed")
    void writesFailSoftPayloadWhenJUnitXmlIsMalformed() throws Exception {
        Path projectDir = createBuildFixture();
        Path scenariosDir = Files.createDirectories(projectDir.resolve("src/e2eTest/resources/scenarios"));
        Path resultsDir = Files.createDirectories(projectDir.resolve("build/test-results/candidateE2eTest"));

        writeUtf8(scenariosDir.resolve("01-read-only.json"), "{ \"id\": \"01\" }\n");
        writeUtf8(resultsDir.resolve("TEST-dev.talos.harness.Broken.xml"), "<testsuite><testcase");

        runWriteE2eSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("summary-generation-failed", summary.get("summaryStatus"));
        assertEquals("e2e-summary", summary.get("summaryName"));
        assertEquals("0.9.0", summary.get("version"));
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

    private BuildResult runWriteE2eSummary(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("writeE2eSummary", "-x", "candidateE2eTest", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private Map<String, Object> readSummary(Path projectDir) throws IOException {
        Path summaryFile = projectDir.resolve("build/reports/talos/e2e-summary.json");
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
