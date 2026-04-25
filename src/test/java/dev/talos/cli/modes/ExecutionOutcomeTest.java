package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionOutcomeTest {

    @Test
    void toolLoopDeniedMutationIsClassifiedAsBlocked() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

        var loopResult = new ToolCallLoop.LoopResult(
                "manual replacement prose", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true,
                        "", "approval denied"
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "manual replacement prose", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION));
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().contract().mutationRequested());
        assertEquals(MutationOutcomeStatus.DENIED, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().denied().size());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.DENIED_MUTATION));
    }

    @Test
    void invalidMutationArgumentsAreClassifiedAsFailedWithoutApprovalDenial() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

        var loopResult = new ToolCallLoop.LoopResult(
                "I updated index.html.", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, false,
                        "", "Invalid talos.edit_file call: `old_string` must be present and non-empty. "
                        + "No approval was requested and no file was changed.",
                        null, ToolError.INVALID_PARAMS
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "I updated index.html.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertTrue(outcome.invalidMutation());
        assertFalse(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("invalid mutation arguments"));
        assertTrue(outcome.finalAnswer().contains("old_string"));
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.FAILED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.INVALID_MUTATION_ARGUMENTS));
    }

    @Test
    void toolLoopPartialMutationIsClassifiedAsPartial() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Update the html and css."));

        var loopResult = new ToolCallLoop.LoopResult(
                "assistant summary", 2, 2,
                List.of("talos.edit_file", "talos.edit_file"), List.of(),
                1, 0, false, 1, List.of(),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome("talos.edit_file", "index.html", true, true, false,
                                "headline updated", ""),
                        new ToolCallLoop.ToolOutcome("talos.edit_file", "style.css", false, true, false,
                                "", "old_string not found")
                ));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "assistant summary", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, outcome.completionStatus());
        assertTrue(outcome.partialMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION));
        assertEquals(TaskCompletionStatus.PARTIAL, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().successful().size());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().failed().size());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.PARTIAL_MUTATION));
    }

    @Test
    void selectorGroundedOverrideIsClassifiedAsGrounded() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-selector-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <body class="synthwave-theme">
                        <section id="hero">
                          <div class="hero-content"></div>
                        </section>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    body.synthwave-theme {}
                    #hero {}
                    .hero-content {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.cta-button');
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "unused", 4, 4,
                    List.of("talos.list_dir", "talos.read_file", "talos.read_file", "talos.read_file"),
                    List.of(), 0, 0, false, 0, List.of("index.html", "style.css", "script.js"),
                    0, 0, 0, 0);

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "There are no mismatches.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.GroundingStatus.GROUNDED, outcome.groundingStatus());
            assertTrue(outcome.selectorGroundedOverride());
            assertTrue(outcome.finalAnswer().contains("Mismatches found:"));
            assertFalse(outcome.finalAnswer().contains("#ff4500"));
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.SELECTOR_GROUNDED_OVERRIDE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplySelectorFailureIsClassifiedAsFailedVerification() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-verify-fail-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html><body><main id="hero"><p>No CTA yet</p></main></body></html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    #hero {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Now edit index.html so the CSS and JavaScript .cta-button selector has a matching element."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.edit_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "index.html", true, true, false,
                            "edited index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("⚠ [Static verification failed:"));
            assertTrue(outcome.finalAnswer().contains("`.cta-button`"));
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplySelectorSuccessIsClassifiedAsPassedVerification() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-verify-pass-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html><body><main id="hero"><a class="cta-button">Listen</a></main></body></html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    #hero {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Now edit index.html so the CSS and JavaScript .cta-button selector has a matching element."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.edit_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "index.html", true, true, false,
                            "edited index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Static verification: passed -"));
            assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(List.of("index.html"), outcome.taskOutcome().contract().expectedTargets().stream().toList());
            assertEquals(TaskVerificationStatus.PASSED, outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void streamingNoToolEvidenceAnswerIsAdvisoryAndUngrounded() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

        String fabricated = "Based on the workspace contents, index.html contains a CTA button, "
                + "style.css defines `.cta-button`, and script.js wires it up. "
                + "There are no mismatches. "
                + "x".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS);

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(fabricated, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNGROUNDED, outcome.groundingStatus());
        assertTrue(outcome.advisoryOnly());
        assertFalse(outcome.noToolMutationReplaced());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED));
    }

    @Test
    void streamingNoToolMutationNarrativeIsBlocked() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

        String fabricated = """
                Sure! Here is the updated index.html.

                ### Updated `index.html`
                Summary of changes:
                - updated index.html
                - these changes should ensure the selectors now match
                """;

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(fabricated, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.noToolMutationReplaced());
        assertEquals(AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT, outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.NOT_ATTEMPTED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED));
    }
}
