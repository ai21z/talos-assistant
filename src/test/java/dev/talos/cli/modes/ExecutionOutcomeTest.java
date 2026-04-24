package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
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
    }
}
