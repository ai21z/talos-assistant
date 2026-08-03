package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

class StaticWebContinuationPlannerTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearContractCapture() {
        TurnTaskContractCapture.clear();
    }

    @Test
    void directoryOnlyPlanPrefersWriteFileAndPreservesContinuationFrame() {
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "bmi-website",
                true,
                true,
                false,
                "Created directory bmi-website",
                ""));
        state.mutatingToolSuccesses = 1;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.nextPlan(state, baseTools());

        assertTrue(plan.isPresent(), "directory-only web mutations should continue to real file writes");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals("static-web-directory-only-continuation", continuation.retryName());
        assertEquals(List.of("talos.write_file"), toolNames(continuation.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, continuation.controls().toolChoice());
        assertEquals(List.of("static-web-directory-only-continuation"), continuation.controls().debugTags());
        assertTrue(continuation.pendingActionObligation().isEmpty());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("[StaticWebCreationContinuation]"), prompt);
        assertTrue(prompt.contains("Successful directory mutation: Created directory bmi-website"), prompt);
        assertTrue(prompt.contains("Call talos.write_file now for the actual static web files."), prompt);
    }

    @Test
    void blockedUnrequestedDirectoryContinuesWithWriteFileOnly() {
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "bmi-website",
                false,
                true,
                false,
                "",
                "The requested talos.mkdir operation is not authorized by the current typed task plan.",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED));

        StaticWebContinuationPlanner.Plan continuation =
                StaticWebContinuationPlanner.blockedUnrequestedDirectoryPlan(
                                state,
                                baseTools())
                        .orElseThrow();

        assertEquals(
                "static-web-blocked-directory-continuation",
                continuation.retryName());
        assertEquals(List.of("talos.write_file"), toolNames(continuation.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, continuation.controls().toolChoice());
        assertEquals(
                List.of("static-web-blocked-directory-continuation"),
                continuation.controls().debugTags());
        assertTrue(continuation.pendingActionObligation().isEmpty());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("[StaticWebBlockedDirectoryContinuation]"), prompt);
        assertTrue(prompt.contains("must not be retried"), prompt);
        assertTrue(prompt.contains(
                "Call talos.write_file now for the actual static web files."),
                prompt);
    }

    @Test
    void blockedDirectoryContinuationDoesNotGeneralizePastExactTypedBlock() {
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "bmi-website",
                false,
                true,
                false,
                "",
                "directory failed",
                null,
                ToolError.INVALID_PARAMS));

        assertTrue(StaticWebContinuationPlanner
                .blockedUnrequestedDirectoryPlan(state, baseTools())
                .isEmpty());
    }

    @Test
    void blockedDirectoryContinuationCannotAbandonAuthorizedTypedMkdir() {
        String request = "Create index.html for a modern BMI calculator "
                + "website, then create a directory named bmi-website.";
        TaskResolution resolution = TaskContractResolver
                .resolveFromUserRequest(request);
        assertEquals(
                dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan
                        .State.AUTHORIZED_SINGLE,
                resolution.workspaceOperationPlan().state(),
                resolution.workspaceOperationPlan().reason());
        TurnTaskContractCapture.set(resolution);
        LoopState state = state(request);
        state.toolOutcomes.add(blockedMkdir("wrong-directory"));

        assertTrue(StaticWebContinuationPlanner
                .blockedUnrequestedDirectoryPlan(state, baseTools())
                .isEmpty());
    }

    @Test
    void blockedDirectoryContinuationCannotDiscardObservedBlockedMkdir() {
        String request = "Create a modern BMI calculator website, then create "
                + "directories named assets and images.";
        TaskResolution resolution = TaskContractResolver
                .resolveFromUserRequest(request);
        assertEquals(
                dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan
                        .State.BLOCKED,
                resolution.workspaceOperationPlan().state(),
                resolution.workspaceOperationPlan().reason());
        TurnTaskContractCapture.set(resolution);
        LoopState state = state(request);
        state.toolOutcomes.add(blockedMkdir("assets"));

        assertTrue(StaticWebContinuationPlanner
                .blockedUnrequestedDirectoryPlan(state, baseTools())
                .isEmpty());
    }

    @Test
    void incompleteTargetScanCannotCreateDirectoryOrVerificationContinuation()
            throws Exception {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(
                "Create a complete static website. "
                        + "a.txt ".repeat(8_193));
        TaskContract quarantined = resolution.contract();
        assertFalse(quarantined.mutationAllowed());
        assertTrue(quarantined.expectedTargets().isEmpty());
        TurnTaskContractCapture.set(resolution);
        LoopState state = state(
                "Create a complete static website. "
                        + "a.txt ".repeat(8_193));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "site",
                true,
                true,
                false,
                "Created directory site",
                ""));
        state.mutatingToolSuccesses = 1;

        assertTrue(StaticWebContinuationPlanner
                .directoryOnlyPlan(state, baseTools()).isEmpty());

        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><head><link rel="stylesheet" href="styles.css"></head>
                <body><script src="script.js"></script></body></html>
                """);
        LoopState verificationState = state(
                "Create a complete static website. "
                        + "a.txt ".repeat(8_193));
        verificationState.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        verificationState.mutatingToolSuccesses = 1;
        assertTrue(StaticWebContinuationPlanner
                .verificationFailurePlan(
                        verificationState,
                        baseTools()).isEmpty());
        assertTrue(StaticWebContinuationPlanner
                .nextPlan(verificationState, baseTools()).isEmpty());
    }

    @Test
    void directoryOnlyPlanRetainsReadToolForCapturedCompositeSourceObligation() {
        TurnTaskContractCapture.set(new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of("style.css"),
                Set.of(),
                "Improve the functional static website. Read style.css before updating "
                        + "index.html, style.css, and script.js.",
                "captured-composite-static-web-contract"));
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "bmi-website",
                true,
                true,
                false,
                "Created directory bmi-website",
                ""));
        state.mutatingToolSuccesses = 1;

        StaticWebContinuationPlanner.Plan continuation =
                StaticWebContinuationPlanner.directoryOnlyPlan(state, baseTools())
                        .orElseThrow();

        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                toolNames(continuation.tools()));
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains(
                "Read these exact source target path(s) before any file mutation: style.css"),
                prompt);
        assertTrue(prompt.contains(
                "Call talos.read_file for style.css first, then call talos.write_file"),
                prompt);
    }

    @Test
    void directoryOnlyPlanDoesNotRunAfterSmallWebFileMutation() {
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.mutatingToolSuccesses = 1;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.directoryOnlyPlan(state, baseTools());

        assertTrue(plan.isEmpty(),
                "directory-only continuation must not trigger after an actual static web file mutation");
    }

    @Test
    void verificationFailurePlanCarriesMissingTargetObligationContext() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button id="calculate">Calculate BMI</button>
                  <p id="result"></p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.mutatingToolSuccesses = 1;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "partial static web writes with missing linked assets should continue");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals("static-web-verification-continuation", continuation.retryName());
        assertEquals(List.of("talos.write_file", "talos.edit_file"), toolNames(continuation.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, continuation.controls().toolChoice());
        assertEquals(List.of("static-web-directory-only-continuation"), continuation.controls().debugTags());
        assertEquals(List.of("script.js", "styles.css"), continuation.missingTargets());
        assertTrue(continuation.pendingActionObligation().isPresent());
        PendingActionObligation obligation = continuation.pendingActionObligation().orElseThrow();
        assertEquals(List.of("script.js", "styles.css"), obligation.targets());
        assertTrue(obligation.failureContext().contains("[Task incomplete: Static verification failed -"),
                obligation.failureContext());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("[StaticWebVerificationContinuation]"), prompt);
        assertTrue(prompt.contains("Missing or unmutated target files: script.js, styles.css"), prompt);
        assertTrue(prompt.contains("Call talos.write_file or talos.edit_file now"), prompt);
    }

    @Test
    void verificationFailurePlanRetainsReadToolForCapturedCompositeSourceObligation()
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="style.css">
                </head>
                <body>
                  <button id="calculate">Calculate BMI</button>
                  <p id="result"></p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        TurnTaskContractCapture.set(capturedCompositeStaticContract());
        LoopState state = state("Continue the current task.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.mutatingToolSuccesses = 1;

        StaticWebContinuationPlanner.Plan continuation =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools())
                        .orElseThrow();

        assertEquals(
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                toolNames(continuation.tools()));
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains(
                "Read these exact source target path(s) before any file mutation: style.css"),
                prompt);
        assertTrue(prompt.contains(
                "Call talos.read_file for style.css first. After those reads, call "
                        + "talos.write_file or talos.edit_file"),
                prompt);
    }

    @Test
    void verificationFailurePlanStopsWhenRequiredReadSurfaceIsUnavailable()
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="style.css">
                </head>
                <body>
                  <button id="calculate">Calculate BMI</button>
                  <p id="result"></p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        TurnTaskContractCapture.set(capturedCompositeStaticContract());
        LoopState state = state("Continue the current task.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.mutatingToolSuccesses = 1;
        List<ToolSpec> mutationOnlyTools = List.of(
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));

        assertTrue(
                StaticWebContinuationPlanner
                        .verificationFailurePlan(state, mutationOnlyTools)
                        .isEmpty(),
                "an unread source obligation must not produce a mutation-only continuation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void linkedExternalMutatedHtmlCannotAddProviderContinuationTargets()
            throws Exception {
        Path external = Files.createTempFile(
                "talos-t1170-continuation-external-",
                ".html");
        try {
            Files.writeString(
                    external,
                    "<script src=\"T1170_EXTERNAL_CONTINUATION_CANARY.js\"></script>");
            createSymbolicLinkOrAbort(
                    workspace.resolve("linked-fragment.html"),
                    external);
            LoopState state = state(
                    "Create a modern static website and complete every missing asset.");
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                    "talos.write_file",
                    "linked-fragment.html",
                    true,
                    true,
                    false,
                    "Wrote linked-fragment.html",
                    ""));
            state.mutatingToolSuccesses = 1;
            java.util.LinkedHashSet<String> targets =
                    new java.util.LinkedHashSet<>();
            var reader = StaticWebContinuationPlanner.class.getDeclaredMethod(
                    "addLinkedMissingStaticWebAssetsFromMutatedHtml",
                    LoopState.class,
                    Set.class);
            reader.setAccessible(true);

            Set<String> added =
                    (Set<String>) reader.invoke(null, state, targets);

            assertFalse(added.contains(
                    "T1170_EXTERNAL_CONTINUATION_CANARY.js"));
            assertFalse(targets.contains(
                    "T1170_EXTERNAL_CONTINUATION_CANARY.js"));
        } finally {
            Files.deleteIfExists(external);
        }
    }

    @Test
    void verificationFailurePlanExcludesAlreadySatisfiedSmallWebTargets() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button id="calculate">Calculate BMI</button>
                  <p id="result"></p>
                  <script src="script.js"></script>
                </body>
                </html>
                """);
        LoopState state = state(
                "I want to create a modern BMI calculator website to use! Can you make it?");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "styles.css",
                true,
                true,
                false,
                "Wrote styles.css",
                ""));
        state.mutatingToolSuccesses = 2;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "missing script.js should still require continuation");
        assertEquals(List.of("script.js"), plan.get().missingTargets());
    }

    @Test
    void verificationFailurePlanPreservesExactLinkedPluralScriptTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <form id="bmiForm">
                    <input id="height" type="number">
                    <input id="weight" type="number">
                    <button type="submit">Calculate BMI</button>
                  </form>
                  <p id="result"></p>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "form { display: grid; gap: 0.5rem; }\n");
        LoopState state = state(
                "Create index.html, styles.css, and scripts.js for a BMI calculator.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "styles.css",
                true,
                true,
                false,
                "Wrote styles.css",
                ""));
        state.mutatingToolSuccesses = 2;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "missing linked scripts.js should require continuation");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals(List.of("scripts.js"), continuation.missingTargets());
        assertTrue(continuation.pendingActionObligation().isPresent());
        assertEquals(List.of("scripts.js"), continuation.pendingActionObligation().orElseThrow().targets());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("Missing or unmutated target files: scripts.js"), prompt);
        assertFalse(prompt.contains("Missing or unmutated target files: script.js"), prompt);
    }

    @Test
    void verificationFailurePlanPreservesExactPlainProblemPrefixPluralScriptTarget() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>Neon Meridian</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="stage">
                    <button id="teaser-button" type="button">Play teaser</button>
                    <p id="teaser-status">Waiting.</p>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".stage { padding: 2rem; }\n");
        Files.writeString(workspace.resolve("scripts.js"), "// Existing content\n");
        LoopState state = state(
                "Update index.html and scripts.js so #teaser-button updates #teaser-status when clicked.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "styles.css",
                true,
                true,
                false,
                "Wrote styles.css",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "scripts.js",
                true,
                true,
                false,
                "Wrote scripts.js",
                ""));
        state.mutatingToolSuccesses = 3;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "placeholder scripts.js should require exact-path repair continuation");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals(List.of("index.html", "scripts.js"), continuation.missingTargets());
        assertEquals(List.of("talos.write_file"), toolNames(continuation.tools()));
        assertTrue(continuation.pendingActionObligation().isPresent());
        assertEquals(List.of("index.html", "scripts.js"),
                continuation.pendingActionObligation().orElseThrow().targets());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("Static web repair target files: index.html, scripts.js"), prompt);
        assertFalse(prompt.contains("Missing or unmutated target files: script.js"), prompt);
        assertFalse(prompt.contains("Static web repair target files: script.js"), prompt);
        assertTrue(prompt.contains("scripts.js: JavaScript file appears to be placeholder content."), prompt);
    }

    @Test
    void fullRewriteInteractionRepairExposesOnlyWriteFileAndDoesNotInviteEditFile() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head>
                  <title>Neon Meridian</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="stage">
                    <button id="teaser-button" type="button">Play teaser</button>
                    <p id="teaser-status">Waiting.</p>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".stage { padding: 2rem; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textC;
                });
                """);
        LoopState state = state(
                "Update index.html and scripts.js so #teaser-button updates #teaser-status when clicked.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "scripts.js",
                true,
                true,
                false,
                "Wrote scripts.js",
                ""));
        state.mutatingToolSuccesses = 2;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "failed explicit interaction verification should continue to full rewrite repair");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals(List.of("talos.write_file"), toolNames(continuation.tools()));
        assertTrue(continuation.pendingActionObligation().isPresent());
        assertEquals(List.of("index.html", "scripts.js"), continuation.pendingActionObligation().orElseThrow().targets());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("Static web repair target files: index.html, scripts.js"), prompt);
        assertTrue(prompt.contains("Call talos.write_file now"), prompt);
        assertFalse(prompt.contains("talos.edit_file"), prompt);
    }

    @Test
    void fullRewriteRepairDoesNotPromoteOptionalCssBeforeT1202() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head>
                  <title>Neon Meridian</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="hero">
                    <button id="teaser-button" type="button">Play teaser</button>
                    <p id="teaser-status">Waiting.</p>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), ".stage { padding: 2rem; }\n");
        Files.writeString(workspace.resolve("scripts.js"), """
                document.getElementById('teaser-button').addEventListener('click', function() {
                  document.getElementById('teaser-status').textContent = 'Teaser unlocked.';
                });
                """);
        LoopState state = state(
                "Update index.html and scripts.js so Neon Meridian is a polished synthwave band landing page. "
                        + "Adjust styles.css as needed. Make #teaser-button update #teaser-status with a visible teaser message.");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                true,
                true,
                false,
                "Wrote index.html",
                ""));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "scripts.js",
                true,
                true,
                false,
                "Wrote scripts.js",
                ""));
        state.mutatingToolSuccesses = 2;

        Optional<StaticWebContinuationPlanner.Plan> plan =
                StaticWebContinuationPlanner.verificationFailurePlan(state, baseTools());

        assertTrue(plan.isPresent(), "required static-web targets still need repair");
        StaticWebContinuationPlanner.Plan continuation = plan.get();
        assertEquals(List.of("talos.write_file"), toolNames(continuation.tools()));
        assertEquals(List.of("index.html", "scripts.js"), continuation.missingTargets());
        assertTrue(continuation.pendingActionObligation().isPresent());
        assertEquals(List.of("index.html", "scripts.js"),
                continuation.pendingActionObligation().orElseThrow().targets());
        String prompt = prompt(continuation.messages());
        assertTrue(prompt.contains("Static web repair target files: index.html, scripts.js"), prompt);
        assertFalse(prompt.contains(
                "Static web repair target files: index.html, scripts.js, styles.css"), prompt);
        assertTrue(prompt.contains("CSS references missing class selectors: `.stage`"), prompt);
        assertTrue(prompt.contains("[StaticRepairReadbacks]"), prompt);
        assertFalse(prompt.contains("\"path\":\"styles.css\""), prompt);
        assertFalse(prompt.contains(".stage { padding: 2rem; }"), prompt);
        ChatMessage last = continuation.messages().get(continuation.messages().size() - 1);
        assertEquals("user", last.role());
        assertTrue(last.content().contains(
                "Repair exactly the listed static-web target path(s): index.html, scripts.js"),
                last.content());
        assertTrue(last.content().contains("Do not write any other file in this continuation."), last.content());
        assertFalse(prompt.contains("Missing or unmutated target files: styles.css"), prompt);
    }

    private LoopState state(String request) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        var llm = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of())),
                16_384).client();
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
    }

    private static ToolCallLoop.ToolOutcome blockedMkdir(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                path,
                false,
                true,
                false,
                "",
                "The requested talos.mkdir operation is not authorized by "
                        + "the current typed task plan.",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED);
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }

    private static TaskContract capturedCompositeStaticContract() {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of("style.css"),
                Set.of(),
                "Improve the functional BMI calculator website. Read style.css before updating "
                        + "index.html, style.css, and script.js.",
                "captured-composite-static-web-contract");
    }

    private static List<String> toolNames(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target)
            throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
