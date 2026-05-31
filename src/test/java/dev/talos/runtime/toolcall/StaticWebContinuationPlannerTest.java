package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StaticWebContinuationPlannerTest {
    @TempDir
    Path workspace;

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

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
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
}
