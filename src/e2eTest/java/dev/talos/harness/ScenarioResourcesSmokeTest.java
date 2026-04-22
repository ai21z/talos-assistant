package dev.talos.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioResourcesSmokeTest {

    @Test
    void sampleScenarioAndFixtureResourcesAreOnClasspath() {
        ClassLoader cl = getClass().getClassLoader();

        assertNotNull(cl.getResource("scenarios/sample-scenario.txt"),
                "e2eTest scenario resources should be available on the classpath");
        assertNotNull(cl.getResource("fixtures/sample-index.html"),
                "e2eTest fixture resources should be available on the classpath");
    }

    @Test
    void sampleScenarioRunnerPathRemainsDeterministic() {
        var scenario = ScenarioDefinition.named("resource lane smoke")
                .withFile("index.html", "<h1>before</h1>")
                .withScriptedResponse("""
                        ```json
                        {"name":"talos.write_file","parameters":{"path":"index.html","content":"<h1>after</h1>"}}
                        ```
                        """)
                .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertFileContains("index.html", "after")
                    .assertToolsInvoked(1)
                    .assertNoFailedCalls();
            assertTrue(result.finalAnswer().contains("Updated index.html")
                            || result.finalAnswer().contains("Wrote index.html")
                            || result.finalAnswer().contains("index.html"),
                    "Deterministic harness run should produce a real tool-loop result summary");
        }
    }

    @Test
    void harnessReadOnlyFollowUpStopsCleanlyAfterScriptedTurn() {
        var scenario = ScenarioDefinition.named("read-only follow-up terminator")
                .withFile("README.md", "# Talos\n")
                .withScriptedResponse("""
                        ```json
                        {"name":"talos.read_file","parameters":{"path":"README.md"}}
                        ```
                        """)
                .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                    .assertNoFailedCalls()
                    .assertUsedTool("talos.read_file");
        }
    }
}
