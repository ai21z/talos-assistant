package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    .assertAnswerContains("Repeated empty or missing talos.edit_file arguments")
                    .assertAnswerNotContains("[iteration limit reached]")
                    .assertAnswerNotContains("This response should not be reached")
                    .assertFileContains("index.html", "<title>Horror Synthwave Band</title>")
                    .assertFileNotContains("index.html", "class=\"cta-button\"");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/46-write-file-missing-content-before-approval.json] 46: missing write_file content is blocked before approval")
    void writeFileMissingContentBlocksBeforeApproval() {
        var loaded = JsonScenarioLoader.load("scenarios/46-write-file-missing-content-before-approval.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertFailedCalls(1)
                    .assertApprovalCounts(0, 0, 0, 0)
                    .assertFileContains("style.css", "background: #111")
                    .assertFileNotContains("style.css", "brighter");

            assertTrue(result.anyToolResultContains("Invalid talos.write_file call"));
            assertTrue(result.anyToolResultContains("missing required parameter `content`"));
            assertTrue(result.anyToolResultContains("No approval was requested"));
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/47-fenced-write-json-with-backticks-executes.json] 47: fenced write_file JSON with backticks executes")
    void fencedWriteJsonWithBackticksExecutes() {
        var loaded = JsonScenarioLoader.load("scenarios/47-fenced-write-json-with-backticks-executes.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.write_file")
                    .assertNoFailedCalls()
                    .assertApprovalCounts(1, 1, 0, 0)
                    .assertFileContains("scripts.js", "`Your BMI is ${bmi.toFixed(2)}`")
                    .assertAnswerNotContains("talos.write_file")
                    .assertAnswerNotContains("```json");
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
    @DisplayName("[json-scenario:scenarios/24-small-talk-direct-no-tools.json] 24: small talk answers directly without tools")
    void smallTalkAnswersDirectlyWithoutTools() {
        var loaded = JsonScenarioLoader.load("scenarios/24-small-talk-direct-no-tools.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Hi.")
                    .assertAnswerNotContains("Used ")
                    .assertAnswerNotContains("iteration limit reached");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/35-no-tool-mutation-retry-create-file-alias.json] 35: no-tool mutation retry executes create_file alias")
    void noToolMutationRetryExecutesCreateFileAlias() {
        var loaded = JsonScenarioLoader.load("scenarios/35-no-tool-mutation-retry-create-file-alias.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("File write/readback passed")
                    .assertAnswerContains("task completion was not verified")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertAnswerContains("script.js")
                    .assertFileContains("script.js", "retry-create-file-alias");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/25-empty-edit-args-recovers-after-read.json] 25: empty edit args recover after read")
    void emptyEditArgsRecoverAfterRead() {
        var loaded = JsonScenarioLoader.load("scenarios/25-empty-edit-args-recovers-after-read.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Static verification: passed")
                    .assertAnswerNotContains("Tool loop stopped by failure policy")
                    .assertAnswerNotContains("This response should not be reached")
                    .assertFileContains("index.html", "class=\"cta-button\"")
                    .assertFileContains("index.html", "Listen now");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/26-scoped-negation-allows-edit.json] 26: scoped no-other-files language still allows explicit edit")
    void scopedNegationAllowsExplicitEdit() {
        var loaded = JsonScenarioLoader.load("scenarios/26-scoped-negation-allows-edit.json");

        try (var result = ScenarioRunner.run(loaded.definition())) {
            result.assertUsedTool("talos.read_file")
                    .assertUsedTool("talos.edit_file")
                    .assertApprovalCounts(1, 1, 0, 0)
                    .assertNoFailedCalls()
                    .assertFileContains("index.html", "<title>Night Signal</title>")
                    .assertFileNotContains("index.html", "<title>Night Drive</title>")
                    .assertFileContains("style.css", "background");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/27-static-verifier-missing-script-downgrades-incomplete.json] 27: missing script target downgrades completion")
    void staticVerifierMissingScriptDowngradesIncomplete() {
        var loaded = JsonScenarioLoader.load("scenarios/27-static-verifier-missing-script-downgrades-incomplete.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(2, 2, 0, 2)
                    .assertAnswerContains("Task incomplete: Static verification failed")
                    .assertAnswerContains("The requested task is not verified complete.")
                    .assertAnswerContains("script.js: expected target was not successfully mutated.")
                    .assertAnswerContains("Expected web-app build to successfully mutate a JavaScript file.")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertFileContains("index.html", "BMI Calculator")
                    .assertFileContains("style.css", ".calculator")
                    .assertFileAbsent("script.js");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/28-pre-approval-path-sandbox-blocks-escape.json] 28: path escape is blocked before approval")
    void preApprovalPathSandboxBlocksEscape() {
        var loaded = JsonScenarioLoader.load("scenarios/28-pre-approval-path-sandbox-blocks-escape.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION)
                    .assertAnswerContains("Path not allowed before approval")
                    .assertAnswerContains("No approval was requested")
                    .assertAnswerNotContains("approval was denied")
                    .assertFileAbsent("outside-talos-qa.txt");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/29-stale-edit-retry-requires-reread.json] 29: stale same-file edit retry requires reread")
    void staleEditRetryRequiresReread() {
        var loaded = JsonScenarioLoader.load("scenarios/29-stale-edit-retry-requires-reread.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(2, 2, 0, 0)
                    .assertAnswerContains("some requested file changes succeeded and some failed")
                    .assertAnswerContains("Call talos.read_file for `README.md`")
                    .assertAnswerContains("separate follow-up")
                    .assertAnswerNotContains("This response should not be reached")
                    .assertFileContains("README.md", "# Talos Local")
                    .assertFileContains("README.md", "Talos is a local-first knowledge engine.")
                    .assertFileNotContains("README.md", "disciplined local-first");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/30-partial-mutation-static-verification-surfaces-problems.json] 30: partial mutation surfaces static verification problems")
    void partialMutationStaticVerificationSurfacesProblems() {
        var loaded = JsonScenarioLoader.load("scenarios/30-partial-mutation-static-verification-surfaces-problems.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Partial verification: static checks failed")
                    .assertAnswerContains("The turn remains partial")
                    .assertAnswerContains("Remaining static verification problems")
                    .assertAnswerContains("file-level verification reported warning")
                    .assertAnswerContains("some requested file changes succeeded and some failed")
                    .assertFileContains("index.html", "<title>Broken Repair</title>")
                    .assertFileContains("index.html", "<script src=\"script.js\">")
                    .assertFileNotContains("index.html", "<script src=\"script.js\"></script>");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/31-read-only-web-diagnostics-grounded.json] 31: read-only web diagnostics are grounded")
    void readOnlyWebDiagnosticsAreGrounded() {
        var loaded = JsonScenarioLoader.load("scenarios/31-read-only-web-diagnostics-grounded.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Static web diagnostics found:")
                    .assertAnswerContains("index.html: malformed closing tag `</button>`")
                    .assertAnswerContains("index.html: malformed closing tag `</script>`")
                    .assertAnswerContains("`calculator-container` should probably be `.calculator-container`")
                    .assertAnswerContains("No files were changed.")
                    .assertAnswerNotContains("script.js` file is missing a closing script tag")
                    .assertFileContains("index.html", "<button type=\"submit\">Calculate BMI</button")
                    .assertFileContains("index.html", "<script src=\"script.js\"></script");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/36-natural-site-diagnostic-grounded.json] 36: natural site diagnostic prompt is grounded")
    void naturalSiteDiagnosticPromptIsGrounded() {
        var loaded = JsonScenarioLoader.load("scenarios/36-natural-site-diagnostic-grounded.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Static web diagnostics found:")
                    .assertAnswerContains("index.html: malformed closing tag `</button>`")
                    .assertAnswerContains("index.html: malformed closing tag `</script>`")
                    .assertAnswerNotContains("newer browser")
                    .assertAnswerNotContains("There are no static HTML, CSS, or JavaScript problems");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/37-identity-small-talk-talos.json] 37: identity small talk answers as Talos")
    void identitySmallTalkAnswersAsTalos() {
        var loaded = JsonScenarioLoader.load("scenarios/37-identity-small-talk-talos.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Talos")
                    .assertAnswerNotContains("Qwen")
                    .assertAnswerNotContains("Alibaba")
                    .assertAnswerNotContains("Used ");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/38-no-tool-local-access-claim-corrected.json] 38: no-tool local access denial is corrected")
    void noToolLocalAccessClaimIsCorrected() {
        var loaded = JsonScenarioLoader.load("scenarios/38-no-tool-local-access-claim-corrected.json");

        try (var result = ScenarioRunner.runThroughExecutorStreaming(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains(AssistantTurnExecutor.LOCAL_ACCESS_CAPABILITY_CORRECTION)
                    .assertAnswerContains("I can read, list, and search files")
                    .assertAnswerNotContains("don't have direct access")
                    .assertAnswerNotContains("As an AI language model");

            assertFalse(result.streamed(),
                    "workspace-evidence turns are buffered so no-tool corrections happen before display");
            assertTrue(result.streamedText().isEmpty(),
                    "buffered workspace-evidence turn should not stream the bad first answer");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/39-natural-workspace-explain-no-tool-retry.json] 39: natural workspace explain retries with read tools")
    void naturalWorkspaceExplainNoToolRetryUsesReadTools() {
        var loaded = JsonScenarioLoader.load("scenarios/39-natural-workspace-explain-no-tool-retry.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("[Used 4 tool(s): talos.list_dir, talos.read_file")
                    .assertAnswerContains("Night Drive web page")
                    .assertAnswerContains("index.html loads style.css")
                    .assertAnswerNotContains("provide the path");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/43-workspace-explain-list-only-underinspection-retry.json] 43: list-only workspace explain retries with primary reads")
    void workspaceExplainListOnlyUnderinspectionRetriesWithPrimaryReads() {
        var loaded = JsonScenarioLoader.load("scenarios/43-workspace-explain-list-only-underinspection-retry.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("[Used 1 tool(s): talos.list_dir")
                    .assertAnswerContains("[Used 3 tool(s): talos.read_file")
                    .assertAnswerContains("Night Drive landing page")
                    .assertAnswerContains("style.css supplies the visual design")
                    .assertAnswerNotContains("basic website");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/40-verify-confirm-no-tool-retry.json] 40: verify-only confirmation retries before answering")
    void verifyOnlyConfirmNoToolRetryUsesReadTools() {
        var loaded = JsonScenarioLoader.load("scenarios/40-verify-confirm-no-tool-retry.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("[Used 3 tool(s): talos.list_dir, talos.read_file")
                    .assertAnswerContains("Confirmed from the files")
                    .assertAnswerContains("references script.js")
                    .assertAnswerNotContains("without being able to see");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/45-status-question-blocks-mutation.json] 45: status question blocks mutation before approval")
    void statusQuestionBlocksMutationBeforeApproval() {
        var loaded = JsonScenarioLoader.load("scenarios/45-status-question-blocks-mutation.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("blocked")
                    .assertFileContains("index.html", "<title>Night Drive</title>")
                    .assertFileNotContains("index.html", "Status Question Regression");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/44-verify-web-complete-static-diagnostics.json] 44: verify web completion uses static diagnostics")
    void verifyWebCompletionUsesStaticDiagnostics() {
        var loaded = JsonScenarioLoader.load("scenarios/44-verify-web-complete-static-diagnostics.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Static web diagnostics found")
                    .assertAnswerContains(".cta-button")
                    .assertAnswerContains("No files were changed.")
                    .assertAnswerNotContains("appears complete");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/41-capability-small-talk-talos.json] 41: capability small talk answers as Talos")
    void capabilitySmallTalkAnswersAsTalos() {
        var loaded = JsonScenarioLoader.load("scenarios/41-capability-small-talk-talos.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Talos")
                    .assertAnswerContains("local workspace")
                    .assertAnswerContains("approval")
                    .assertAnswerNotContains("As an AI language model")
                    .assertAnswerNotContains("poems");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/56-chat-small-talk-no-workspace-tools.json] 56: chat small talk does not execute workspace tools")
    void chatSmallTalkDoesNotExecuteWorkspaceTools() {
        var loaded = JsonScenarioLoader.load("scenarios/56-chat-small-talk-no-workspace-tools.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Talos")
                    .assertAnswerNotContains("ALPHA-742")
                    .assertAnswerNotContains("talos.read_file")
                    .assertAnswerNotContains("Used ");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/57-chat-privacy-negation-no-workspace-tools.json] 57: chat privacy negation does not execute workspace tools")
    void chatPrivacyNegationDoesNotExecuteWorkspaceTools() {
        var loaded = JsonScenarioLoader.load("scenarios/57-chat-privacy-negation-no-workspace-tools.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerNotContains("ALPHA-742")
                    .assertAnswerNotContains("talos.list_dir")
                    .assertAnswerNotContains("talos.read_file")
                    .assertAnswerNotContains("Used ");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/58-chat-explicit-workspace-request-still-inspects.json] 58: chat explicit workspace request still inspects")
    void chatExplicitWorkspaceRequestStillInspects() {
        var loaded = JsonScenarioLoader.load("scenarios/58-chat-explicit-workspace-request-still-inspects.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("[Used 1 tool(s): talos.grep")
                    .assertAnswerContains("ALPHA-742");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/42-partial-followup-summary-uses-verified-history.json] 42: follow-up summary uses verified partial history")
    void partialFollowupSummaryUsesVerifiedHistory() {
        var loaded = JsonScenarioLoader.load("scenarios/42-partial-followup-summary-uses-verified-history.json");
        List<ChatMessage> history = new ArrayList<>();
        var historyNode = loaded.raw().path("history");
        for (var node : historyNode) {
            history.add(new ChatMessage(
                    node.path("role").asText(),
                    node.path("content").asText()));
        }

        try (var result = ScenarioRunner.runThroughExecutorWithHistory(
                loaded.definition(),
                history,
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("partial")
                    .assertAnswerContains("not verified complete")
                    .assertAnswerContains(".cta-button")
                    .assertAnswerNotContains("I added the Listen Now button")
                    .assertAnswerNotContains("wired script.js");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/48-repair-followup-after-incomplete-outcome-applies.json] 48: repair follow-up after incomplete outcome is apply capable")
    void repairFollowupAfterIncompleteOutcomeApplies() {
        var loaded = JsonScenarioLoader.load("scenarios/48-repair-followup-after-incomplete-outcome-applies.json");
        List<ChatMessage> history = new ArrayList<>();
        var historyNode = loaded.raw().path("history");
        for (var node : historyNode) {
            history.add(new ChatMessage(
                    node.path("role").asText(),
                    node.path("content").asText()));
        }

        try (var result = ScenarioRunner.runThroughExecutorWithHistory(
                loaded.definition(),
                history,
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertFileContains("scripts.js", "BMI repaired")
                    .assertAnswerContains("Created scripts.js");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/49-status-question-after-incomplete-outcome-stays-verify-only.json] 49: status question after incomplete outcome stays verify only")
    void statusQuestionAfterIncompleteOutcomeStaysVerifyOnly() {
        var loaded = JsonScenarioLoader.load("scenarios/49-status-question-after-incomplete-outcome-stays-verify-only.json");
        List<ChatMessage> history = new ArrayList<>();
        var historyNode = loaded.raw().path("history");
        for (var node : historyNode) {
            history.add(new ChatMessage(
                    node.path("role").asText(),
                    node.path("content").asText()));
        }

        try (var result = ScenarioRunner.runThroughExecutorWithHistory(
                loaded.definition(),
                history,
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertFileAbsent("scripts.js")
                    .assertAnswerNotContains("Created scripts.js");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/53-status-followup-preserves-partial-outcome.json] 53: status follow-up preserves previous partial outcome")
    void statusFollowupPreservesPartialOutcome() {
        var loaded = JsonScenarioLoader.load("scenarios/53-status-followup-preserves-partial-outcome.json");
        List<ChatMessage> history = new ArrayList<>();
        var historyNode = loaded.raw().path("history");
        for (var node : historyNode) {
            history.add(new ChatMessage(
                    node.path("role").asText(),
                    node.path("content").asText()));
        }

        try (var result = ScenarioRunner.runThroughExecutorWithHistory(
                loaded.definition(),
                history,
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("partial")
                    .assertAnswerContains("not complete")
                    .assertAnswerContains("HTML does not link JavaScript file")
                    .assertAnswerContains("submit/calculate button")
                    .assertAnswerNotContains("functional 3-file BMI calculator")
                    .assertAnswerNotContains("changes applied successfully");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/54-scoped-target-limiter-blocks-forbidden-target.json] 54: scoped target limiter blocks forbidden target")
    void scopedTargetLimiterBlocksForbiddenTarget() {
        var loaded = JsonScenarioLoader.load("scenarios/54-scoped-target-limiter-blocks-forbidden-target.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Succeeded:")
                    .assertAnswerContains("styles.css")
                    .assertAnswerContains("Failed:")
                    .assertAnswerContains("index.html")
                    .assertAnswerContains("forbidden")
                    .assertFileContains("styles.css", "background: #101820")
                    .assertFileContains("styles.css", "border: 1px solid #f2aa4c")
                    .assertFileContains("index.html", "<title>Scoped Check</title>")
                    .assertFileNotContains("index.html", "forbidden mutation")
                    .assertFileContains("scripts.js", "scoped check");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/55-post-denial-retry-reissues-write.json] 55: post-denial retry reissues write")
    void postDenialRetryReissuesWrite() {
        var loaded = JsonScenarioLoader.load("scenarios/55-post-denial-retry-reissues-write.json");
        List<ChatMessage> history = new ArrayList<>();
        var historyNode = loaded.raw().path("history");
        for (var node : historyNode) {
            history.add(new ChatMessage(
                    node.path("role").asText(),
                    node.path("content").asText()));
        }

        try (var result = ScenarioRunner.runThroughExecutorWithHistory(
                loaded.definition(),
                history,
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertFileContains("scripts.js", "console.log(\"repair ok\");")
                    .assertAnswerContains("[Used 1 tool(s): talos.write_file")
                    .assertAnswerNotContains("cannot assist");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/50-static-verifier-placeholder-web-app-fails.json] 50: placeholder JavaScript prevents web app verification")
    void staticVerifierPlaceholderWebAppFails() {
        var loaded = JsonScenarioLoader.load("scenarios/50-static-verifier-placeholder-web-app-fails.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(3, 3, 0, 3)
                    .assertAnswerContains("Static verification failed")
                    .assertAnswerContains("scripts.js: JavaScript file appears to be placeholder content")
                    .assertAnswerContains("The requested task is not verified complete.")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertFileContains("index.html", "<script src=\"scripts.js\"></script>")
                    .assertFileContains("scripts.js", "// Your JavaScript logic here");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("[json-scenario:scenarios/51-windows-expected-target-case-normalization.json] 51: Windows expected target matching ignores case-only differences")
    void windowsExpectedTargetCaseNormalization() {
        var loaded = JsonScenarioLoader.load("scenarios/51-windows-expected-target-case-normalization.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(3, 3, 0, 3)
                    .assertAnswerContains("Static verification failed")
                    .assertAnswerContains("scripts.js: JavaScript file appears to be placeholder content")
                    .assertAnswerNotContains("Index.html: expected target was not successfully mutated.")
                    .assertAnswerNotContains("index.html: expected target was not successfully mutated.")
                    .assertFileContains("index.html", "<script src=\"scripts.js\"></script>")
                    .assertFileContains("scripts.js", "// Your JavaScript logic here");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/52-repeated-stylesheet-insertion-fails-verification.json] 52: repeated stylesheet insertion fails static verification")
    void repeatedStylesheetInsertionFailsVerification() {
        var loaded = JsonScenarioLoader.load("scenarios/52-repeated-stylesheet-insertion-fails-verification.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(1, 1, 0, 0)
                    .assertAnswerContains("Static verification failed")
                    .assertAnswerContains("HTML links CSS file more than once: `style.css`")
                    .assertAnswerNotContains("Static verification: passed")
                    .assertFileContains("index.html", "<link rel=\"stylesheet\" href=\"style.css\">\n    <link rel=\"stylesheet\" href=\"style.css\">");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/32-unsupported-binary-document-honesty.json] 32: unsupported binary document reads are capability-limited")
    void unsupportedBinaryDocumentHonesty() {
        var loaded = JsonScenarioLoader.load("scenarios/32-unsupported-binary-document-honesty.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("[Document capability note:")
                    .assertAnswerContains("sample.pdf")
                    .assertAnswerContains("sample.xlsx")
                    .assertAnswerContains("current local text-tool surface")
                    .assertAnswerContains("notes.txt says Talos should summarize supported text files")
                    .assertAnswerNotContains("do not contain any extractable text")
                    .assertAnswerNotContains("These files are empty");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/33-read-only-web-diagnostics-short-circuit.json] 33: read-only web diagnostics stop before iteration cap")
    void readOnlyWebDiagnosticsShortCircuit() {
        var loaded = JsonScenarioLoader.load("scenarios/33-read-only-web-diagnostics-short-circuit.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("Static web diagnostics found:")
                    .assertAnswerContains("index.html: malformed closing tag `</button>`")
                    .assertAnswerContains("index.html: malformed closing tag `</script>`")
                    .assertAnswerContains("1 iteration(s)")
                    .assertAnswerNotContains("iteration limit reached")
                    .assertAnswerNotContains("10 iteration(s)")
                    .assertAnswerNotContains("failure policy stopped")
                    .assertAnswerNotContains("This response should not be reached");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/34-empty-edit-args-cross-path-stop.json] 34: empty edit args across paths stop before iteration cap")
    void emptyEditArgsAcrossPathsStop() {
        var loaded = JsonScenarioLoader.load("scenarios/34-empty-edit-args-cross-path-stop.json");

        try (var result = ScenarioRunner.runThroughExecutor(
                loaded.definition(),
                loaded.definition().userPrompt(),
                loaded.scriptedResponses())) {
            result.assertApprovalCounts(0, 0, 0, 0)
                    .assertAnswerContains("No file changes were applied")
                    .assertAnswerContains("empty or missing talos.edit_file argument failure")
                    .assertAnswerContains("across 3 path(s)")
                    .assertAnswerContains("No approval was requested")
                    .assertAnswerNotContains("iteration limit reached")
                    .assertAnswerNotContains("This response should not be reached")
                    .assertFileContains("index.html", "<button type=\"submit\">Calculate BMI</button")
                    .assertFileContains("styles.css", "calculator-container")
                    .assertFileContains("script.js", "bmi-form");
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

            assertFalse(result.streamed(),
                    "workspace-evidence turns are buffered before final truth shaping");
            assertTrue(result.streamedText().isEmpty(),
                    "buffered workspace-evidence turn should not stream the ungrounded first answer");
        }
    }
}
