package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingMutationRetryTest {

    @TempDir
    Path workspace;

    @Test
    void invalidParamsMutatingFailureGetsOneCorrectedRetryWithErrorEchoed() {
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var invalid = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.md", false, true, false, "",
                "Invalid operations_json: expected an array or an object with operations.",
                null, ToolError.INVALID_PARAMS);
        var loopResult = new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), messages, 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, List.of(invalid));

        List<String> capturedRetryPrompts = new ArrayList<>();
        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                "I tried to create the file.", messages, safePlan, loopResult, workspace,
                testContext(),
                (retryMessages, plan, specs) -> {
                    retryMessages.forEach(m -> capturedRetryPrompts.add(m.content() == null ? "" : m.content()));
                    return new LlmClient.StreamResult("no tool calls in retry", List.of());
                });

        assertEquals(1, capturedRetryPrompts.stream()
                        .filter(c -> c.contains("rejected with invalid parameters")).count(),
                "invalid-params failure should fire exactly one corrected retry: " + capturedRetryPrompts);
        assertTrue(capturedRetryPrompts.stream().anyMatch(c ->
                        c.contains("Invalid operations_json: expected an array or an object with operations.")),
                "retry prompt must echo the tool error: " + capturedRetryPrompts);
        assertTrue(result.actionObligationFailed() || result.extraSummary() != null,
                "failed retry must surface obligation failure state");
    }

    @Test
    void deniedMutationStillSuppressesRetry() {
        String request = "Create notes.md with a single line of text.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        CurrentTurnPlan safePlan = CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(request),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var denied = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.md", false, true, true, "",
                "User did not approve the talos.write_file call.");
        var loopResult = new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), messages, 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, List.of(denied));

        List<String> captured = new ArrayList<>();
        MissingMutationRetry.Result result = MissingMutationRetry.retryIfNeeded(
                "Approval was denied.", messages, safePlan, loopResult, workspace,
                testContext(),
                (retryMessages, plan, specs) -> {
                    captured.add("retry-fired");
                    return new LlmClient.StreamResult("", List.of());
                });

        assertTrue(captured.isEmpty(), "denied mutation must never re-prompt");
        assertEquals("Approval was denied.", result.answer());
    }

    private Context testContext() {
        var registry = new ToolRegistry();
        registry.register(new FileWriteTool(new FileUndoStack()));
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        var loop = new ToolCallLoop(processor, 3);
        return Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
    }

    @Test
    void compactStaticRepairContextBelongsToMissingMutationRetry() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        The previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, scripts.js, styles.css

                        Missing expected targets: scripts.js

                        Previous static verification problems:
                        - scripts.js: expected target was not successfully mutated.
                        - HTML does not link JavaScript file: `scripts.js`
                        - Calculator/form task is missing a submit/calculate button.

                        Repair plan:
                        Full-file replacement targets: index.html, scripts.js, styles.css
                        - index.html: You must use talos.write_file with complete corrected file content for index.html.
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.

                        Cross-file coherence checklist:
                        - HTML must link every CSS and JavaScript file being written.
                        - Every JavaScript ID or selector must exist in HTML before the JavaScript uses it.
                        """
                        + "VERBOSE_REPAIR_PADDING ".repeat(200)));

        String content = compact.content();
        assertTrue(content.startsWith("[Static verification repair context]"), content);
        assertTrue(content.contains("Expected targets: index.html, scripts.js, styles.css"), content);
        assertTrue(content.contains("Missing expected targets: scripts.js"), content);
        assertTrue(content.contains("scripts.js: expected target was not successfully mutated."), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, scripts.js, styles.css"), content);
        assertFalse(content.contains("VERBOSE_REPAIR_PADDING"), content);
        assertFalse(content.contains("Cross-file coherence checklist"), content);
    }

    @Test
    void compactStaticRepairContextPreservesRequirementsAndDropsNonControllingSelectorInventory() {
        ChatMessage compact = MissingMutationRetry.compactStaticVerificationRepairInstructionForRetry(
                ChatMessage.system("""
                        [Static verification repair context]
                        Previous mutation task ended incomplete after static verification.

                        Expected targets: index.html, style.css, script.js

                        [StaticWebRequirements]
                        requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026
                        forbiddenArtifacts: tailwind.css, tailwind.min.css

                        Previous static verification problems:
                        - tailwind.css: local Tailwind artifact is unsupported without an explicit build/runtime path.
                        - style.css: expected target was not successfully mutated.

                        Repair plan:
                        Full-file replacement targets: index.html, style.css, script.js

                        [Current static selector facts]
                        HTML classes: %s
                        CSS classes: %s
                        JavaScript selectors: %s
                        """.formatted(
                        "class-token ".repeat(250),
                        "css-token ".repeat(250),
                        "js-token ".repeat(250))));

        String content = compact.content();
        assertTrue(content.contains("[StaticWebRequirements]"), content);
        assertTrue(content.contains("requiredVisibleFacts: Retrocats, Costanza, Merri, Rome 15 July 2026"),
                content);
        assertTrue(content.contains("forbiddenArtifacts: tailwind.css, tailwind.min.css"), content);
        assertTrue(content.contains("Full-file replacement targets: index.html, style.css, script.js"), content);
        assertFalse(content.contains("[Current static selector facts]"), content);
        assertFalse(content.contains("class-token"), content);
        assertTrue(content.length() < 1_800, "compact repair context too large: " + content.length());
    }
}
