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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Coverage summary task")
class CoverageSummaryTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeCoverageSummary reports missing JaCoCo XML explicitly")
    void reportsMissingJacocoXmlExplicitly() throws Exception {
        Path projectDir = createBuildFixture();
        Files.createDirectories(projectDir.resolve("build/test-results/candidateTest"));

        runWriteCoverageSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> tests = castMap(summary.get("tests"));
        Map<String, Object> instructionCoverage = castMap(summary.get("instructionCoverage"));

        assertEquals("jacoco-xml-missing", summary.get("coverageDataStatus"));
        assertEquals("no-results", tests.get("status"));
        assertEquals(0, tests.get("total"));
        assertEquals(0, instructionCoverage.get("covered"));
        assertEquals(0, instructionCoverage.get("missed"));
        assertNull(instructionCoverage.get("percent"));
    }

    @Test
    @DisplayName("writeCoverageSummary reports computed percentages and passed-with-skips from synthetic evidence")
    void reportsCoveragePercentagesAndSkippedTests() throws Exception {
        Path projectDir = createBuildFixture();
        Path jacocoDir = Files.createDirectories(projectDir.resolve("build/reports/jacoco/candidateTest"));
        Path testResultsDir = Files.createDirectories(projectDir.resolve("build/test-results/candidateTest"));

        writeUtf8(jacocoDir.resolve("candidateJacocoTestReport.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="candidate">
                  <counter type="INSTRUCTION" missed="20" covered="80"/>
                  <counter type="BRANCH" missed="1" covered="3"/>
                </report>
                """);
        writeUtf8(testResultsDir.resolve("TEST-dev.talos.fixture.SampleTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="sample" tests="4" failures="0" errors="0" skipped="1">
                  <testcase classname="dev.talos.fixture.SampleTest" name="one" time="0.001" />
                  <testcase classname="dev.talos.fixture.SampleTest" name="two" time="0.001" />
                  <testcase classname="dev.talos.fixture.SampleTest" name="three" time="0.001" />
                  <testcase classname="dev.talos.fixture.SampleTest" name="four" time="0.001">
                    <skipped />
                  </testcase>
                </testsuite>
                """);

        runWriteCoverageSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> tests = castMap(summary.get("tests"));
        Map<String, Object> instructionCoverage = castMap(summary.get("instructionCoverage"));
        Map<String, Object> branchCoverage = castMap(summary.get("branchCoverage"));

        assertEquals("jacoco-xml-present", summary.get("coverageDataStatus"));
        assertEquals(80, instructionCoverage.get("covered"));
        assertEquals(20, instructionCoverage.get("missed"));
        assertEquals(80.0, instructionCoverage.get("percent"));
        assertEquals(3, branchCoverage.get("covered"));
        assertEquals(1, branchCoverage.get("missed"));
        assertEquals(75.0, branchCoverage.get("percent"));
        assertEquals("passed-with-skips", tests.get("status"));
        assertEquals(4, tests.get("total"));
        assertEquals(3, tests.get("passed"));
        assertEquals(1, tests.get("skipped"));
    }

    @Test
    @DisplayName("writeCoverageSummary writes a fail-soft payload when JaCoCo XML is malformed")
    void writesFailSoftPayloadWhenJacocoXmlIsMalformed() throws Exception {
        Path projectDir = createBuildFixture();
        Path jacocoDir = Files.createDirectories(projectDir.resolve("build/reports/jacoco/candidateTest"));

        writeUtf8(jacocoDir.resolve("candidateJacocoTestReport.xml"), "<report><counter");

        runWriteCoverageSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        assertEquals("summary-generation-failed", summary.get("summaryStatus"));
        assertEquals("coverage-summary", summary.get("summaryName"));
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

    private BuildResult runWriteCoverageSummary(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("writeCoverageSummary", "-x", "candidateJacocoTestReport", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private Map<String, Object> readSummary(Path projectDir) throws IOException {
        Path summaryFile = projectDir.resolve("build/reports/talos/coverage-summary.json");
        return JSON.readValue(Files.readString(summaryFile, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
