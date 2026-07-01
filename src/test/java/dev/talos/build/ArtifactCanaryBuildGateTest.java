package dev.talos.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCanaryBuildGateTest {

    @Test
    void checkRunsGeneratedArtifactCanaryScan() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("checkGeneratedArtifactCanaries"), build);
        assertTrue(build.contains("build/reports"), build);
        assertTrue(build.contains("build/test-results"), build);
        assertTrue(build.contains("dependsOn(tasks.test, e2eTest, tasks.jacocoTestReport)"), build);
        assertTrue(build.contains("dependsOn(tasks.test, e2eTest, tasks.jacocoTestCoverageVerification, checkGeneratedArtifactCanaries)"),
                build);
    }
}
