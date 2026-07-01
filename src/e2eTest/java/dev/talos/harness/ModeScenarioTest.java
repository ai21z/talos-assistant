package dev.talos.harness;

import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TurnProcessor mode scenarios")
class ModeScenarioTest {

    @Test
    @DisplayName("Ask mode refuses mutation before tools or approval")
    void askModeRefusesMutationBeforeToolsOrApproval() {
        ScenarioDefinition scenario = ScenarioDefinition.named("ask read-only mutation")
                .withMode("ask")
                .withUserPrompt("Create notes.md with hello.")
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(scenario, List.of("should not be called"))) {
            result.assertActiveMode("ask")
                    .assertTraceMode("ask")
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Ask is read-only; switch to `/mode agent` to make changes.")
                    .assertAnswerNotContains("BLOCKED_BY_POLICY")
                    .assertFileAbsent("notes.md");
        }
    }

    @Test
    @DisplayName("Plan mode exposes read-only tools and does not consume mutation as apply")
    void planModeIsReadOnlyAndDoesNotApplyPendingMutation() {
        ScenarioDefinition scenario = ScenarioDefinition.named("plan read-only mutation")
                .withMode("plan")
                .withFile("README.md", "Talos\n")
                .withUserPrompt("Create notes.md with hello, but first plan the change.")
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(
                scenario,
                List.of("Plan:\n1. Inspect README.md.\n2. Switch to `/mode agent` to apply the file change."))) {
            result.assertActiveMode("plan")
                    .assertTraceMode("plan")
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerNotContains("BLOCKED_BY_POLICY")
                    .assertFileAbsent("notes.md");

            LocalTurnTrace trace = result.localTrace();
            assertFalse(trace.toolSurface().nativeTools().stream().anyMatch(ModeScenarioTest::isMutationOrCommandTool),
                    trace.toolSurface().nativeTools().toString());
            assertFalse(trace.promptAudit().nativeTools().stream().anyMatch(ModeScenarioTest::isMutationOrCommandTool),
                    trace.promptAudit().nativeTools().toString());
            assertEquals(trace.toolSurface().nativeTools(), trace.promptAudit().nativeTools(),
                    "Prompt-visible tools must match native tools under Plan");
        }
    }

    @Test
    @DisplayName("Agent mode executes approved mutation through TurnProcessor")
    void agentModeExecutesApprovedMutation() {
        ScenarioDefinition scenario = ScenarioDefinition.named("agent approved mutation")
                .withMode("agent")
                .withUserPrompt("Create notes.md with hello.")
                .withApprovalPolicy(ScenarioApprovalPolicy.APPROVE_ALL)
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(
                scenario,
                List.of(
                        """
                        ```json
                        {"name":"talos.write_file","parameters":{"path":"notes.md","content":"hello\\n"}}
                        ```
                        """,
                        "Created notes.md."))) {
            result.assertActiveMode("agent")
                    .assertTraceMode("agent")
                    .assertApprovalCounts(1, 1, 0, 0)
                    .assertFileContains("notes.md", "hello");
        }
    }

    @Test
    @DisplayName("Auto mode keeps structural commands on deterministic handler")
    void autoModeKeepsStructuralCommandsDeterministic() {
        ScenarioDefinition scenario = ScenarioDefinition.named("auto structural listing")
                .withMode("auto")
                .withFile("README.md", "Talos\n")
                .withUserPrompt("ls")
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(scenario, List.of("should not be called"))) {
            result.assertActiveMode("auto")
                    .assertTraceMode("auto")
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("README.md")
                    .assertFileContains("README.md", "Talos");
        }
    }

    @Test
    @DisplayName("Private mode omits retrieve from trace and prompt tool surfaces")
    void privateModeOmitsRetrieveFromTraceAndPromptSurfaces() {
        ScenarioDefinition scenario = ScenarioDefinition.named("private mode retrieval surface")
                .withPrivateMode()
                .withMode("auto")
                .withFile("README.md", "Talos public readme\n")
                .withUserPrompt("What is this project?")
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(
                scenario,
                List.of("I can inspect the workspace without using retrieval."))) {
            result.assertActiveMode("auto")
                    .assertTraceMode("auto")
                    .assertApprovalCounts(0, 0, 0, 0);

            LocalTurnTrace trace = result.localTrace();
            assertTrue(trace.toolSurface().nativeTools().contains("talos.read_file"),
                    trace.toolSurface().nativeTools().toString());
            assertFalse(trace.toolSurface().nativeTools().contains("talos.retrieve"),
                    trace.toolSurface().nativeTools().toString());
            assertFalse(trace.toolSurface().promptTools().contains("talos.retrieve"),
                    trace.toolSurface().promptTools().toString());
            assertFalse(trace.promptAudit().nativeTools().contains("talos.retrieve"),
                    trace.promptAudit().nativeTools().toString());
            assertFalse(trace.promptAudit().promptTools().contains("talos.retrieve"),
                    trace.promptAudit().promptTools().toString());
            assertEquals(trace.toolSurface().nativeTools(), trace.promptAudit().nativeTools(),
                    "Prompt-visible tools must match native tools in private mode");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "chat", "unified"})
    @DisplayName("Legacy aliases resolve to canonical Agent in TurnProcessor scenarios")
    void legacyAliasesResolveToCanonicalAgent(String alias) {
        ScenarioDefinition scenario = ScenarioDefinition.named("legacy alias " + alias)
                .withMode(alias)
                .withUserPrompt("Say hello.")
                .build();

        try (var result = ScenarioRunner.runThroughTurnProcessor(scenario, List.of("Hello from agent."))) {
            result.assertActiveMode("agent")
                    .assertTraceMode("agent")
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Hello from agent.");
        }
    }

    private static boolean isMutationOrCommandTool(String toolName) {
        return toolName != null && (
                toolName.equals("talos.write_file")
                        || toolName.equals("talos.edit_file")
                        || toolName.equals("talos.run_command"));
    }
}
