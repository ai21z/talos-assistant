package dev.talos.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JSON deterministic scenario pack")
class JsonScenarioPackTest {

    @Test
    @DisplayName("[json-scenario:scenarios/01-read-only-repo-question.json] 01: read-only repo question stays read-only and answers from fixture facts")
    void readOnlyRepoQuestion() {
        var loaded = JsonScenarioLoader.load("scenarios/01-read-only-repo-question.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertAnswerContains("README.md")
                    .assertAnswerContains("src/Main.java")
                    .assertAnswerContains("local-first knowledge engine")
                    .assertFileContains("README.md", "Talos")
                    .assertFileContains("src/Main.java", "class Main")
                    .assertFileNotContains("README.md", "mutated by test");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/02-single-safe-file-edit.json] 02: single safe file edit changes only the requested title")
    void singleSafeFileEdit() {
        var loaded = JsonScenarioLoader.load("scenarios/02-single-safe-file-edit.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.read_file")
                    .assertUsedTool("talos.edit_file")
                    .assertDidNotUseTool("talos.write_file")
                    .assertNoFailedCalls()
                    .assertFileContains("index.html", "<title>Night Signal</title>")
                    .assertFileNotContains("index.html", "<title>Night Drive</title>")
                    .assertFileContains("style.css", "background");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/03-off-scope-mutation-warning.json] 03: off-scope mutation surfaces a warning before approval")
    void offScopeMutationWarning() {
        var loaded = JsonScenarioLoader.load("scenarios/03-off-scope-mutation-warning.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertApprovalCounts(1, 1, 0, 0)
                    .assertAnyApprovalDetailContains("looks unrelated to the current task")
                    .assertAnyApprovalDetailContains("math_operations.py")
                    .assertFileExists("math_operations.py")
                    .assertFileContains("math_operations.py", "wrong scope");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/04-not-found-recovery.json] 04: not-found recovery retries with the real path and answers correctly")
    void notFoundRecovery() {
        var loaded = JsonScenarioLoader.load("scenarios/04-not-found-recovery.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertAnswerContains("Talos")
                    .assertAnswerNotContains("READMEE.md")
                    .assertFileContains("README.md", "Talos");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/05-approval-denied.json] 05: approval denied blocks the write and preserves the original file")
    void approvalDenied() {
        var loaded = JsonScenarioLoader.load("scenarios/05-approval-denied.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertApprovalCounts(1, 0, 1, 0)
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileNotContains("index.html", "<h1>denied</h1>");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/06-approval-remembered.json] 06: remembered approval asks once and lets later writes proceed")
    void approvalRememberedInSession() {
        var loaded = JsonScenarioLoader.load("scenarios/06-approval-remembered.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertNoFailedCalls()
                    .assertApprovalCounts(1, 1, 0, 1)
                    .assertFileContains("index.html", "<h1>remembered</h1>")
                    .assertFileContains("style.css", "color: cyan");

            assertEquals(2, result.toolNames().stream()
                    .filter("talos.write_file"::equals)
                    .count(), "Both writes should still execute");
            assertTrue(result.toolsInvoked() >= 2,
                    "Scenario should execute both write operations. Summary: "
                            + result.loopResult().summary());
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/09-read-only-workspace-no-unsolicited-mutation.json] 09: read-only workspace question rejects unsolicited edit before approval")
    void readOnlyWorkspaceQuestionRejectsUnsolicitedMutation() {
        var loaded = JsonScenarioLoader.load("scenarios/09-read-only-workspace-no-unsolicited-mutation.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("index.html")
                    .assertAnswerContains("script.js")
                    .assertAnswerContains("style.css")
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileNotContains("index.html", "<title>Welcome to My Modern Web Experience</title>");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/10-selector-mismatch-grounded.json] 10: selector mismatch analysis is grounded in actual files")
    void selectorMismatchAnalysisIsGrounded() {
        var loaded = JsonScenarioLoader.load("scenarios/10-selector-mismatch-grounded.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Mismatches found:")
                    .assertAnswerContains("`.cta-button`")
                    .assertAnswerNotContains("There are no mismatches")
                    .assertAnswerNotContains("present in both HTML and JavaScript");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/11-partial-mutation-summary-truthful.json] 11: partial mutation summary reports only verified outcomes")
    void partialMutationSummaryIsTruthful() {
        var loaded = JsonScenarioLoader.load("scenarios/11-partial-mutation-summary-truthful.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertAnswerContains("Succeeded:")
                    .assertAnswerContains("Failed:")
                    .assertAnswerContains("old_string not found")
                    .assertAnswerContains("style.css")
                    .assertAnswerNotContains("The title was changed to Melodic Horror Synthwave");
        }
    }
}
