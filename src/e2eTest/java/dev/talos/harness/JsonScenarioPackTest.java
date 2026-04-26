package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;
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
    @DisplayName("[json-scenario:scenarios/14-approval-denial-stops-loop.json] 14: approval denial stops without re-prompting for another mutating retry")
    void approvalDenialStopsLoopWithoutRetry() {
        var loaded = JsonScenarioLoader.load("scenarios/14-approval-denial-stops-loop.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 0, 1, 0)
                    .assertAnswerContains(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION)
                    .assertAnswerContains("No file changes were applied because approval was denied")
                    .assertAnswerContains("index.html: approval denied")
                    .assertAnswerNotContains("iteration limit reached")
                    .assertAnswerNotContains("I'll retry the edit")
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileContains("index.html", "<h1>Night Drive</h1>")
                    .assertFileNotContains("index.html", "Denied Retry Regression");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/15-inspect-phase-blocks-mutation.json] 15: inspect phase blocks mutation before approval")
    void inspectPhaseBlocksMutationBeforeApproval() {
        var loaded = JsonScenarioLoader.load("scenarios/15-inspect-phase-blocks-mutation.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertFailedCalls(1)
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileNotContains("index.html", "Inspect Phase Regression");

            assertTrue(result.anyToolResultContains(
                    "Phase policy blocked talos.write_file during INSPECT"));
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/16-verify-phase-blocks-mutation.json] 16: verify phase blocks mutation before approval")
    void verifyPhaseBlocksMutationBeforeApproval() {
        var loaded = JsonScenarioLoader.load("scenarios/16-verify-phase-blocks-mutation.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertFailedCalls(1)
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileNotContains("index.html", "Verify Phase Regression");

            assertTrue(result.anyToolResultContains(
                    "Phase policy blocked talos.write_file during VERIFY"));
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/17-static-verifier-selector-fails-after-wrong-edit.json] 17: static verifier fails unresolved selector linkage after mutation")
    void staticVerifierFailsWrongSelectorEdit() {
        var loaded = JsonScenarioLoader.load("scenarios/17-static-verifier-selector-fails-after-wrong-edit.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Static verification failed")
                    .assertAnswerContains("`.cta-button`")
                    .assertFileContains("index.html", "<title>Horror Synthwave Fixed</title>")
                    .assertFileNotContains("index.html", "class=\"cta-button\"");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/18-static-verifier-selector-passes-after-cta-fix.json] 18: static verifier passes after cta selector fix")
    void staticVerifierPassesAfterCtaFix() {
        var loaded = JsonScenarioLoader.load("scenarios/18-static-verifier-selector-passes-after-cta-fix.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Static verification: passed")
                    .assertAnswerNotContains("Static verification failed")
                    .assertFileContains("index.html", "class=\"cta-button\"");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/19-static-verifier-partial-mutation-not-verified-complete.json] 19: partial mutation is not blessed as statically verified complete")
    void staticVerifierDoesNotBlessPartialMutationAsComplete() {
        var loaded = JsonScenarioLoader.load("scenarios/19-static-verifier-partial-mutation-not-verified-complete.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertAnswerContains("Succeeded:")
                    .assertAnswerContains("Failed:")
                    .assertAnswerContains("style.css")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertFileContains("index.html", "class=\"cta-button\"");
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
    @DisplayName("[json-scenario:scenarios/20-selector-mismatch-grep-only-grounded.json] 20: grep-only selector underinspection is grounded")
    void selectorMismatchGrepOnlyUnderinspectionIsGrounded() {
        var loaded = JsonScenarioLoader.load("scenarios/20-selector-mismatch-grep-only-grounded.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Mismatches found:")
                    .assertAnswerContains("`.cta-button`")
                    .assertAnswerNotContains("There are no mismatches")
                    .assertAnswerNotContains("No further action is needed");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/21-mutation-prompt-empty-edit-args-stops-cleanly.json] 21: repeated empty edit args stop without approval or mutation")
    void mutationPromptEmptyEditArgsStopsCleanly() {
        var loaded = JsonScenarioLoader.load("scenarios/21-mutation-prompt-empty-edit-args-stops-cleanly.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION)
                    .assertAnswerContains("No file changes were applied")
                    .assertAnswerContains("Repeated empty talos.edit_file arguments")
                    .assertAnswerNotContains("[iteration limit reached]")
                    .assertAnswerNotContains("This response should not be reached")
                    .assertFileContains("index.html", "<title>Horror Synthwave Band</title>")
                    .assertFileNotContains("index.html", "class=\"cta-button\"");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/22-build-website-prompt-allows-apply.json] 22: build website prompt is apply-capable")
    void buildWebsitePromptAllowsApply() {
        var loaded = JsonScenarioLoader.load("scenarios/22-build-website-prompt-allows-apply.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(3, 3, 0, 3)
                    .assertAnswerContains("Static verification: passed")
                    .assertFileContains("index.html", "BMI Calculator")
                    .assertFileContains("index.html", "styles.css")
                    .assertFileContains("index.html", "script.js")
                    .assertFileContains("styles.css", ".calculator")
                    .assertFileContains("script.js", "dataset.ready");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/23-static-verifier-web-app-build-fails-broken-linkage.json] 23: broad web app build fails broken static linkage")
    void staticVerifierFailsBrokenWebAppBuildLinkage() {
        var loaded = JsonScenarioLoader.load("scenarios/23-static-verifier-web-app-build-fails-broken-linkage.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(3, 3, 0, 3)
                    .assertAnswerContains("Static verification failed")
                    .assertAnswerContains("JavaScript references missing IDs")
                    .assertAnswerContains("`#bmi-form`")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertFileContains("index.html", "No form was added")
                    .assertFileContains("styles.css", ".calculator")
                    .assertFileContains("script.js", "getElementById('bmi-form')");
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

    @Test
    @DisplayName("[json-scenario:scenarios/12-repeated-missing-path-stops-at-loop-cap.json] 12: repeated missing-path failure stops by failure policy")
    void repeatedMissingPathFailureStopsByFailurePolicy() {
        var loaded = JsonScenarioLoader.load("scenarios/12-repeated-missing-path-stops-at-loop-cap.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Tool loop stopped by failure policy")
                    .assertAnswerContains("[failure policy stopped]")
                    .assertAnswerNotContains("[iteration limit reached]")
                    .assertFileContains("README.md", "Talos");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/13-streaming-no-tool-grounding-visible.json] 13: streaming no-tool fabricated evidence answer is visibly marked ungrounded")
    void streamingNoToolEvidenceAnswerIsVisiblyUngrounded() {
        var loaded = JsonScenarioLoader.load("scenarios/13-streaming-no-tool-grounding-visible.json");

        try (var result = ScenarioRunner.runThroughExecutorStreaming(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)
                    .assertAnswerContains("There are no mismatches")
                    .assertAnswerContains("cta-button")
                    .assertFileContains("index.html", "<title>Horror Synthwave Band</title>");

            assertTrue(result.streamed(),
                    "runThroughExecutorStreaming should drive the streaming branch");
        }
    }
}
