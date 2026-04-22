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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Version summary task")
class VersionSummaryTaskTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("writeVersionSummary reports a jar built in the current invocation")
    void reportsJarBuiltInCurrentInvocation() throws Exception {
        Path projectDir = createBuildFixture();
        writeUtf8(projectDir.resolve("src/main/java/dev/talos/fixture/App.java"), """
                package dev.talos.fixture;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("ok");
                    }
                }
                """);

        runWriteVersionSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> taskState = castMap(summary.get("jarTaskStateInCurrentInvocation"));
        Map<String, Object> artifact = castMap(castListOfMaps(summary.get("artifacts")).get(0));

        assertEquals("built-in-current-run", taskState.get("status"));
        assertEquals(Boolean.TRUE, taskState.get("jarTaskDidWork"));
        assertEquals(Boolean.FALSE, taskState.get("jarTaskUpToDate"));
        assertEquals(Boolean.TRUE, artifact.get("exists"));
        assertEquals("talos.jar", artifact.get("name"));
        assertNotNull(summary.get("jarBuiltAt"));
        assertTrue(((String) summary.get("jarBuiltAt")).contains("T"));
    }

    @Test
    @DisplayName("writeVersionSummary reports an up-to-date jar on a second unchanged invocation")
    void reportsUpToDateJarOnSecondRun() throws Exception {
        Path projectDir = createBuildFixture();
        writeUtf8(projectDir.resolve("src/main/java/dev/talos/fixture/App.java"), """
                package dev.talos.fixture;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("ok");
                    }
                }
                """);

        runWriteVersionSummary(projectDir);
        runWriteVersionSummary(projectDir);

        Map<String, Object> summary = readSummary(projectDir);
        Map<String, Object> taskState = castMap(summary.get("jarTaskStateInCurrentInvocation"));

        assertEquals("up-to-date-in-current-run", taskState.get("status"));
        assertEquals(Boolean.FALSE, taskState.get("jarTaskDidWork"));
        assertEquals(Boolean.TRUE, taskState.get("jarTaskUpToDate"));
        assertEquals(Boolean.TRUE, taskState.get("jarExists"));
        assertNotNull(taskState.get("jarLastModifiedIso"));
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

    private BuildResult runWriteVersionSummary(Path projectDir) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("writeVersionSummary", "--stacktrace")
                .forwardOutput()
                .build();
    }

    private Map<String, Object> readSummary(Path projectDir) throws IOException {
        Path summaryFile = projectDir.resolve("build/reports/talos/version-summary.json");
        return JSON.readValue(Files.readString(summaryFile, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castListOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void writeUtf8(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
